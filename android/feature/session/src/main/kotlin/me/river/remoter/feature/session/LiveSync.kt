package me.river.remoter.feature.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.LiveEvent
import me.river.remoter.core.net.Link
import me.river.remoter.core.net.RemoterApi
import javax.inject.Inject
import javax.inject.Singleton

// a drop empties nothing, the next connect replaces it whole
@Singleton
class LiveSync @Inject constructor(
    private val api: RemoterApi,
    private val monitor: ConnectionMonitor,
    private val hub: SessionsHub,
) {
    private val _connected = MutableStateFlow(false)

    // detail polls only while this is false
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _connected.value = false
    }

    private suspend fun run() {
        var attempt = 0
        while (true) {
            // tunnel down = nothing to retry until it's back
            monitor.link.first { it != Link.VpnOff }
            var got = false
            try {
                api.live().collect { ev ->
                    if (!got) {
                        got = true
                        attempt = 0
                        _connected.value = true
                        // beat the monitor's next probe
                        if (monitor.link.value !is Link.Up) monitor.probeNow()
                    }
                    when (ev) {
                        is LiveEvent.Sessions -> hub.publish(ev.sessions)
                        is LiveEvent.Health -> monitor.onLiveHealth(ev.health)
                        is LiveEvent.Resources -> monitor.onLiveResources(ev.resources)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            _connected.value = false
            // kicking on every failed attempt restarts the monitor's retries, so it never says the laptop is down
            if (got) monitor.probeNow()
            delay(BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.size - 1)])
            attempt++
        }
    }

    companion object {
        val BACKOFF_MS = longArrayOf(500, 1_000, 2_000, 4_000)
    }
}
