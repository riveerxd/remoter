package me.river.remoter.feature.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.river.remoter.core.crypto.PromptCopy
import me.river.remoter.core.crypto.RequestSigner
import me.river.remoter.core.crypto.SignResult
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.Paths
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.SessionSummary
import me.river.remoter.core.net.toAppError
import javax.inject.Inject
import javax.inject.Singleton

/** How an End went. Sent and Cancelled both carry no error, which is why they are told apart here. */
sealed interface EndOutcome {
    data object Sent : EndOutcome
    data object Cancelled : EndOutcome
    data class Failed(val error: AppError) : EndOutcome
}

/**
 * Running sessions and the view token, shared by home and detail. The token
 * lives in memory only: terminal output is never stored.
 */
@Singleton
class SessionsHub @Inject constructor(
    private val api: RemoterApi,
    private val signer: RequestSigner,
    private val clock: Clock,
) {
    private val _sessions = MutableStateFlow<List<SessionSummary>?>(null)
    val sessions: StateFlow<List<SessionSummary>?> = _sessions.asStateFlow()
    private val _ending = MutableStateFlow<Set<String>>(emptySet())
    val ending: StateFlow<Set<String>> = _ending.asStateFlow()

    private var token: Pair<String, Long>? = null

    fun viewToken(): String? = token?.takeIf { it.second > clock.nowMs() }?.first

    fun setViewToken(t: String, expiresMs: Long) {
        token = t to expiresMs
    }

    suspend fun refresh(): Result<List<SessionSummary>> = runCatching { api.sessions().sessions }.onSuccess(::publish)

    /** The list as the laptop has it, from a fetch or the live stream. An equal list changes nothing. */
    fun publish(list: List<SessionSummary>) {
        _sessions.value = list
        // Once the laptop stops listing a session it is gone and the banner collapses.
        _ending.update { e -> e.filterTo(mutableSetOf()) { id -> list.any { it.id == id } } }
    }

    fun seed(list: List<SessionSummary>) {
        if (_sessions.value == null) _sessions.value = list
    }

    /** The fingerprint prompt is the confirmation. No "are you sure" on top. */
    suspend fun end(s: SessionSummary, host: String): EndOutcome {
        val r = signer.sign("DELETE", Paths.session(s.id), ByteArray(0), PromptCopy("End ${s.name} on $host"))
        if (r !is SignResult.Ok) return if (r is SignResult.KeyInvalidated) EndOutcome.Failed(AppError.KeyInvalidated) else EndOutcome.Cancelled
        _ending.update { it + s.id }
        return try {
            api.kill(r.signed)
            EndOutcome.Sent
        } catch (e: Exception) {
            _ending.update { it - s.id }
            EndOutcome.Failed(e.toAppError(clock.nowMs()))
        }
    }
}
