package me.river.remoter.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.river.remoter.core.crypto.PromptCopy
import me.river.remoter.core.crypto.RequestSigner
import me.river.remoter.core.crypto.SignResult
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.AuditEntry
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.LocalStore
import me.river.remoter.core.net.Paths
import me.river.remoter.core.net.Prefs
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.toAppError
import javax.inject.Inject

data class SettingsUi(
    val state: LocalState = LocalState(),
    val locked: Boolean = false,
    val busy: Boolean = false,
    val unpairing: Boolean = false,
    val lockError: AppError? = null,
    val unpairError: AppError? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: LocalStore,
    private val api: RemoterApi,
    private val monitor: ConnectionMonitor,
    private val signer: RequestSigner,
    private val clock: Clock,
) : ViewModel() {
    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()
    private val _unpaired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unpaired: SharedFlow<Unit> = _unpaired

    private val host get() = monitor.health.value?.hostname ?: store.state.value?.laptop?.hostname ?: "the laptop"

    init {
        viewModelScope.launch { store.state.collect { s -> if (s != null) _ui.update { it.copy(state = s) } } }
        viewModelScope.launch { monitor.account.collect { a -> _ui.update { it.copy(locked = a?.locked == true) } } }
    }

    fun set(f: (Prefs) -> Prefs) {
        viewModelScope.launch { store.update { it.copy(prefs = f(it.prefs)) } }
    }

    /** Making things safer never needs a fingerprint: mTLS only. */
    fun lockLaptop() {
        // no second lock while the first is in flight
        if (_ui.value.busy || _ui.value.locked) return
        _ui.update { it.copy(busy = true, lockError = null) }
        viewModelScope.launch {
            try {
                api.lock()
                _ui.update { it.copy(busy = false, locked = true) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, lockError = e.toAppError(clock.nowMs())) }
            }
        }
    }

    fun unpair() {
        if (_ui.value.unpairing) return
        _ui.update { it.copy(unpairing = true, unpairError = null) }
        viewModelScope.launch {
            val r = signer.sign("DELETE", Paths.deviceSelf, ByteArray(0), PromptCopy("Unpair this phone from $host"))
            if (r !is SignResult.Ok) {
                _ui.update { it.copy(unpairing = false) }
                return@launch
            }
            try {
                api.unpair(r.signed)
                store.update { it.copy(laptop = null, snapshot = null, pinned = emptyList()) }
                _unpaired.tryEmit(Unit)
            } catch (e: Exception) {
                _ui.update { it.copy(unpairing = false, unpairError = e.toAppError(clock.nowMs())) }
            }
        }
    }

}

data class AuditDay(val label: String, val entries: ImmutableList<AuditEntry>)

data class AuditUi(
    val days: ImmutableList<AuditDay> = persistentListOf(),
    val loading: Boolean = true,
    val end: Boolean = false,
    val error: AppError? = null,
)

@HiltViewModel
class AuditViewModel @Inject constructor(private val api: RemoterApi, private val clock: Clock) : ViewModel() {
    private val _ui = MutableStateFlow(AuditUi())
    val ui: StateFlow<AuditUi> = _ui.asStateFlow()
    private val all = mutableListOf<AuditEntry>()
    private var before: Long? = null
    private var paging: Job? = null

    init {
        more()
    }

    /**
     * 50 at a time; the list asks for more when its skeleton row comes into view,
     * and Retry comes through here too. The skeleton shows up while init's first
     * page is still out, so a call in flight swallows the next one.
     */
    fun more() {
        if (_ui.value.end || paging?.isActive == true) return
        _ui.update { it.copy(loading = true, error = null) }
        paging = viewModelScope.launch { page() }
    }

    private suspend fun page() {
        try {
            val page = api.audit(before)
            // Fixture pages repeat; a real laptop pages by time, so dedupe either way. By the whole
            // entry, not the request id: pairing and lock entries all share "-" and would vanish.
            val fresh = page.entries.filter { e -> e !in all }
            all += fresh
            before = page.nextBefore
            _ui.update { it.copy(days = group(all), loading = false, end = page.nextBefore == null || fresh.isEmpty()) }
        } catch (e: Exception) {
            _ui.update { it.copy(loading = false, error = e.toAppError(clock.nowMs())) }
        }
    }

    private fun group(entries: List<AuditEntry>): ImmutableList<AuditDay> {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(clock.nowMs()).atZone(zone).toLocalDate()
        return entries.sortedByDescending { it.ts }
            .groupBy { java.time.Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() }
            .map { (d, es) ->
                val label = when (d) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> "%d %s".format(d.dayOfMonth, d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()))
                }
                AuditDay(label, es.toImmutableList())
            }.toImmutableList()
    }
}
