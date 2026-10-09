package me.river.remoter.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.river.remoter.core.crypto.PromptCopy
import me.river.remoter.core.crypto.RequestSigner
import me.river.remoter.core.crypto.SignResult
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.Paths
import me.river.remoter.core.net.Proc
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.RemoterJson
import me.river.remoter.core.net.Resources
import me.river.remoter.core.net.Signal
import me.river.remoter.core.net.SignalRequest
import me.river.remoter.core.net.toAppError
import javax.inject.Inject

enum class ProcSort { Cpu, Memory }

data class ProcsUi(
    val host: String = "the laptop",
    val resources: Resources? = null,
    val procs: ImmutableList<Proc> = persistentListOf(),
    val sort: ProcSort = ProcSort.Cpu,
    val loaded: Boolean = false,
    // last poll failed, the list stays as it was
    val error: AppError? = null,
    val truncated: Boolean = false,
    val selected: Proc? = null,
    val sending: Signal? = null,
    val signalError: AppError? = null,
    val note: String? = null,
)

internal fun List<Proc>.sortedFor(s: ProcSort): List<Proc> = when (s) {
    ProcSort.Cpu -> sortedWith(compareByDescending<Proc> { it.cpuPct }.thenByDescending { it.rss }.thenBy { it.pid })
    ProcSort.Memory -> sortedWith(compareByDescending<Proc> { it.rss }.thenByDescending { it.cpuPct }.thenBy { it.pid })
}

internal fun Signal.verb() = if (this == Signal.Term) "Quit" else "Kill"
internal fun Signal.unix() = if (this == Signal.Term) "SIGTERM" else "SIGKILL"

@HiltViewModel
class ProcessesViewModel @Inject constructor(
    private val api: RemoterApi,
    private val signer: RequestSigner,
    private val monitor: ConnectionMonitor,
    private val clock: Clock,
) : ViewModel() {
    private val _ui = MutableStateFlow(ProcsUi(host = monitor.health.value?.hostname ?: "the laptop", resources = monitor.resources.value))
    val ui: StateFlow<ProcsUi> = _ui.asStateFlow()
    private var raw: List<Proc> = emptyList()
    private val loading = Mutex()

    suspend fun poll() {
        while (true) {
            load()
            delay(PollMs)
        }
    }

    suspend fun load() = loading.withLock {
        try {
            val r = api.procs()
            raw = r.procs
            _ui.update { u ->
                u.copy(
                    host = monitor.health.value?.hostname ?: u.host,
                    resources = r.resources,
                    procs = raw.sortedFor(u.sort).toImmutableList(),
                    loaded = true,
                    error = null,
                    truncated = r.truncated,
                    // a process that went away closes its sheet
                    selected = u.selected?.let { s -> raw.firstOrNull { it.pid == s.pid && it.start == s.start } },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.toAppError(clock.nowMs()), loaded = true) }
        }
    }

    fun sort(s: ProcSort) = _ui.update { it.copy(sort = s, procs = raw.sortedFor(s).toImmutableList()) }

    fun select(p: Proc?) = _ui.update { it.copy(selected = p, signalError = null) }

    /** The fingerprint prompt is the confirmation, like End on a session. */
    fun signal(p: Proc, sig: Signal) {
        if (_ui.value.sending != null) return
        viewModelScope.launch {
            val body = RemoterJson.encodeToString(SignalRequest.serializer(), SignalRequest(p.start, sig)).toByteArray()
            val host = _ui.value.host
            val what = p.session?.let { "${p.name} in ${it.name}" } ?: p.name
            val r = signer.sign("POST", Paths.signal(p.pid), body, PromptCopy("${sig.verb()} $what on $host", "${sig.unix()} to pid ${p.pid}"))
            val signed = when (r) {
                is SignResult.Ok -> r.signed
                SignResult.Cancelled -> return@launch
                SignResult.LockedOut -> return@launch _ui.update { it.copy(signalError = AppError.FingerprintLockedOut) }
                SignResult.KeyInvalidated -> return@launch _ui.update { it.copy(signalError = AppError.KeyInvalidated) }
            }
            _ui.update { it.copy(sending = sig, signalError = null) }
            try {
                api.signal(signed)
                _ui.update { it.copy(sending = null, selected = null, note = "Sent ${sig.unix()} to ${p.name}") }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(sending = null, signalError = e.toAppError(clock.nowMs())) }
            }
        }
    }

    fun noteShown() = _ui.update { it.copy(note = null) }

    companion object {
        const val PollMs = 2_000L
    }
}
