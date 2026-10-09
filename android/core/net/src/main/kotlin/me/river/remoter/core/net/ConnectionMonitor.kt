package me.river.remoter.core.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Link and account are separate: a locked laptop still has a healthy link. */
sealed interface Link {
    data class Up(val latencyMs: Int) : Link
    data object Reconnecting : Link
    data object VpnOff : Link
    data class LaptopDown(val lastSeenMs: Long?) : Link
}

data class Account(val locked: Boolean, val paired: Boolean, val clockSkewMs: Long, val freshUntilMs: Long?)

/**
 * Probes `/health` every 10 s while in the foreground, and at once on any VPN
 * network change. WireGuard handshakes lazily, so the first probe after a
 * network change often fails: a failure means Reconnecting and retries at 1,
 * 2 and 4 s, and only then LaptopDown. Worst case to notice the laptop went
 * away is about 21 s.
 */
class ConnectionMonitor(
    private val api: RemoterApi,
    private val vpn: VpnNetworks,
    private val clock: Clock,
) {
    private val _link = MutableStateFlow<Link>(Link.Reconnecting)
    val link: StateFlow<Link> = _link.asStateFlow()
    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()
    private val _health = MutableStateFlow<Health?>(null)
    val health: StateFlow<Health?> = _health.asStateFlow()
    private val _resources = MutableStateFlow<Resources?>(null)

    /** Only from the live stream. A laptop too old to send it leaves this null. */
    val resources: StateFlow<Resources?> = _resources.asStateFlow()

    private val latencies = ArrayDeque<Int>()
    private var lastSeenMs: Long? = null
    private var job: Job? = null
    private val kick = MutableStateFlow(0)

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            vpn.link.collectLatest { v ->
                when (v) {
                    VpnLink.Absent -> _link.value = Link.VpnOff
                    is VpnLink.Present -> kick.collectLatest { loop() }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun probeNow() {
        kick.value++
    }

    /**
     * A `health` event from the live stream: battery and lock show at once instead of on the next
     * probe. The skew stays the probe's, so the session age doesn't shift with every push.
     */
    fun onLiveHealth(h: Health) {
        _health.value = h
        val skew = _account.value?.clockSkewMs ?: (h.serverTime - clock.nowMs())
        _account.value = Account(h.locked, paired = true, clockSkewMs = skew, freshUntilMs = h.freshUntil)
    }

    fun onLiveResources(r: Resources) {
        _resources.value = r
    }

    private suspend fun loop() {
        while (true) {
            if (!probe()) {
                _link.value = Link.Reconnecting
                var ok = false
                for (wait in RETRIES_MS) {
                    delay(wait)
                    if (probe()) {
                        ok = true
                        break
                    }
                }
                if (!ok) _link.value = Link.LaptopDown(lastSeenMs)
            }
            delay(INTERVAL_MS)
        }
    }

    private suspend fun probe(): Boolean {
        val t0 = clock.uptimeMs()
        val h = try {
            api.health()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        val ms = (clock.uptimeMs() - t0).toInt().coerceAtLeast(1)
        latencies.addLast(ms)
        while (latencies.size > 3) latencies.removeFirst()
        val now = clock.nowMs()
        lastSeenMs = now
        _health.value = h
        _account.value = Account(h.locked, paired = true, clockSkewMs = h.serverTime - now, freshUntilMs = h.freshUntil)
        _link.value = Link.Up(latencies.sorted()[latencies.size / 2])
        return true
    }

    companion object {
        const val INTERVAL_MS = 10_000L
        val RETRIES_MS = longArrayOf(1_000, 2_000, 4_000)
    }
}
