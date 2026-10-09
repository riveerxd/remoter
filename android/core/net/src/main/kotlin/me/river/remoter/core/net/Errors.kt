package me.river.remoter.core.net

/**
 * Every failure the UI can be in, by what the user can do about it.
 * The server's message never reaches the screen.
 */
sealed interface AppError {
    data object VpnOff : AppError
    data object LaptopDown : AppError
    data object Unreachable : AppError
    data object AgentDown : AppError
    data class Server(val code: ErrorCode, val requestId: String) : AppError
    data object Locked : AppError
    data object DeviceUnknown : AppError
    data object KeyInvalidated : AppError
    data class Security(val code: ErrorCode, val requestId: String) : AppError
    data class RateLimited(val retryAfterS: Int) : AppError
    data class SessionCap(val sessions: List<SessionSummary>) : AppError
    /** A session already runs in the folder; claude allows one Remote Control per folder. */
    data class FolderBusy(val sessions: List<SessionSummary>) : AppError
    /** The conversation to resume is open in a claude on the laptop right now. */
    data object ConversationOpen : AppError
    data class ClockSkew(val offsetMs: Long) : AppError
    data object Untrusted : AppError
    data object NotFound : AppError
    data class Validation(val code: ErrorCode) : AppError
    data class Denied(val code: ErrorCode) : AppError
    data object ReattestFailed : AppError
    data object FingerprintLockedOut : AppError
}

fun ErrorBody.toAppError(nowMs: Long): AppError = when (code) {
    ErrorCode.NameInvalid, ErrorCode.Exists, ErrorCode.NotADirectory, ErrorCode.BadRequest -> AppError.Validation(code)
    ErrorCode.PathOutsideHome, ErrorCode.PathDenied, ErrorCode.PathUnsupported, ErrorCode.ProcessDenied -> AppError.Denied(code)
    ErrorCode.NotFound -> AppError.NotFound
    ErrorCode.UntrustedFolder -> AppError.Untrusted
    ErrorCode.Locked -> AppError.Locked
    ErrorCode.DeviceUnknown -> AppError.DeviceUnknown
    ErrorCode.SigInvalid, ErrorCode.NonceReused -> AppError.Security(code, requestId)
    ErrorCode.ClockSkew -> AppError.ClockSkew((serverTime ?: nowMs) - nowMs)
    ErrorCode.RateLimited -> AppError.RateLimited(retryAfterS ?: 60)
    ErrorCode.SessionCap -> AppError.SessionCap(sessions.orEmpty())
    ErrorCode.FolderBusy -> AppError.FolderBusy(sessions.orEmpty())
    ErrorCode.ConversationOpen -> AppError.ConversationOpen
    ErrorCode.AgentDown -> AppError.AgentDown
    // Handled silently by re-attesting; only a failed re-attestation reaches the user.
    ErrorCode.ReattestRequired -> AppError.ReattestFailed
    ErrorCode.ViewTokenRequired, ErrorCode.SpawnFailed, ErrorCode.DesktopDown, ErrorCode.Internal,
    ErrorCode.PairExpired, ErrorCode.PairRejected -> AppError.Server(code, requestId)
}

fun Throwable.toAppError(nowMs: Long): AppError = when (this) {
    is ApiException -> body.toAppError(nowMs)
    is VpnOffException -> AppError.VpnOff
    else -> AppError.Unreachable
}

/** Raised before any packet is sent, when our VPN network isn't there. */
class VpnOffException : Exception("vpn off")
