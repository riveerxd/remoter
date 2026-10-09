package me.river.remoter.feature.session

import kotlinx.collections.immutable.ImmutableList
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ClaudeLink
import me.river.remoter.core.net.StuckReason

@JvmInline value class SessionId(val value: String)

data class Step(val label: String, val done: Boolean)

// the signed request stays in the ViewModel, never in here
sealed interface StartState {
    data object Idle : StartState
    data object AwaitingFingerprint : StartState
    data class Sending(val retryUntilMs: Long) : StartState

    // retryUntilMs null: a resend needs a new fingerprint
    data class NotAccepted(val error: AppError, val retryUntilMs: Long?) : StartState
    data class Starting(
        val id: SessionId,
        val steps: ImmutableList<Step>,
        val sinceMs: Long,
        val streamReconnecting: Boolean,
        // past 10 s
        val slow: Boolean,
    ) : StartState

    // claude is null until the laptop reports the links
    data class Ready(val id: SessionId, val name: String, val claude: ClaudeLink?) : StartState
    data class Stuck(val id: SessionId, val reason: StuckReason?, val tail: ImmutableList<String>) : StartState
    data class Exited(val id: SessionId, val code: Int?, val tail: ImmutableList<String>) : StartState
}
