package me.river.remoter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.LocalStore
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.SessionSummary
import me.river.remoter.feature.session.LiveSync
import me.river.remoter.feature.session.EndOutcome
import me.river.remoter.feature.session.SessionsHub
import me.river.remoter.feature.session.copy
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    private val monitor: ConnectionMonitor,
    private val store: LocalStore,
    private val clock: Clock,
    private val api: RemoterApi,
    private val hub: SessionsHub,
    private val attester: me.river.remoter.core.crypto.Attester,
    private val live: LiveSync,
) : ViewModel() {
    val state: StateFlow<LocalState?> = store.state
    private val _snack = MutableStateFlow<String?>(null)
    val snack: StateFlow<String?> = _snack.asStateFlow()
    private val _locking = MutableStateFlow(false)
    val locking: StateFlow<Boolean> = _locking.asStateFlow()
    private val _ending = MutableStateFlow<Set<String>>(emptySet())
    val ending: StateFlow<Set<String>> = _ending.asStateFlow()
    private val _ended = MutableStateFlow<Set<String>>(emptySet())
    val ended: StateFlow<Set<String>> = _ended.asStateFlow()
    val host get() = monitor.health.value?.hostname ?: store.state.value?.laptop?.hostname ?: "the laptop"

    /** Foreground only: probes and the live stream run while the app is visible, never in the background. */
    fun foreground() {
        monitor.start(viewModelScope)
        live.start(viewModelScope)
        // daily re-attest, quietly, once the laptop says freshness ran out
        viewModelScope.launch {
            monitor.account.collect { a ->
                val fresh = a?.freshUntilMs
                if (a != null && (fresh == null || fresh < clock.nowMs())) attester.refresh()
            }
        }
    }

    fun background() {
        monitor.stop()
        live.stop()
        viewModelScope.launch { store.update { it.copy(backgroundedAtMs = clock.nowMs()) } }
    }

    fun lockLaptop() {
        // The flag is the guard: a second hold while the first request is out does nothing.
        if (!_locking.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                runCatching { api.lock() }.onSuccess { _snack.value = "$host is locked" }.onFailure { _snack.value = "Couldn't reach $host" }
            } finally {
                _locking.value = false
            }
        }
    }

    fun end(s: SessionSummary) {
        if (s.id in _ending.value || s.id in _ended.value) return
        _ending.update { it + s.id }
        viewModelScope.launch {
            val o = try {
                hub.end(s, host)
            } finally {
                _ending.update { it - s.id }
            }
            when (o) {
                EndOutcome.Sent -> _ended.update { it + s.id }
                EndOutcome.Cancelled -> {}
                is EndOutcome.Failed -> _snack.value = if (o.error == AppError.KeyInvalidated) {
                    "A new fingerprint wiped remoter's key. Pair again to end ${s.name}"
                } else {
                    "Couldn't end ${s.name}: ${o.error.copy(host).title}"
                }
            }
        }
    }

    fun say(msg: String) {
        _snack.value = msg
    }

    fun snackShown() {
        _snack.value = null
    }
}
