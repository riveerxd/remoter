package me.river.remoter.feature.session

import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ErrorCode
import kotlin.math.abs

enum class ErrorLook { Inline, Network, Server, Permission, Security, Limits }

data class ErrorCopy(
    val look: ErrorLook,
    val title: String,
    val body: String? = null,
    val command: String? = null,
    val requestId: String? = null,
)

fun AppError.copy(host: String): ErrorCopy = when (this) {
    is AppError.Validation -> when (code) {
        ErrorCode.Exists -> ErrorCopy(ErrorLook.Inline, "There's already a folder with that name here")
        ErrorCode.NotADirectory -> ErrorCopy(ErrorLook.Inline, "That's a file, not a folder")
        else -> ErrorCopy(ErrorLook.Inline, "Use letters, numbers, dots, dashes or underscores, and don't start with a dash")
    }
    AppError.VpnOff -> ErrorCopy(ErrorLook.Network, "WireGuard is off", "Turn on the rmt tunnel, then try again.")
    AppError.LaptopDown -> ErrorCopy(ErrorLook.Network, "$host is asleep or offline", "Wake it or plug it in, then retry.")
    AppError.Unreachable -> ErrorCopy(ErrorLook.Network, "Couldn't reach $host")
    AppError.AgentDown -> ErrorCopy(ErrorLook.Network, "$host's agent isn't running", command = "remoterctl doctor")
    is AppError.Server -> when (code) {
        ErrorCode.DesktopDown -> ErrorCopy(
            ErrorLook.Server, "Nobody is logged in on $host",
            "Sessions open as windows on workspace 9, so the desktop has to be running. Log in on the laptop, then try again.",
            requestId = requestId,
        )
        ErrorCode.SpawnFailed -> ErrorCopy(ErrorLook.Server, "$host couldn't start the session", "Run this on the laptop to see why.", "remoterctl doctor", requestId)
        else -> ErrorCopy(ErrorLook.Server, "Something went wrong on $host", "Run this on the laptop to see why.", "remoterctl doctor", requestId)
    }
    AppError.Locked -> ErrorCopy(ErrorLook.Permission, "$host is locked", "Nothing can start until you unlock it on the laptop.", "sudo remoterctl lock off")
    AppError.DeviceUnknown -> ErrorCopy(ErrorLook.Permission, "$host doesn't know this phone anymore", "It was unpaired or revoked. Pair again to keep going.")
    AppError.KeyInvalidated -> ErrorCopy(ErrorLook.Permission, "A new fingerprint was added, so remoter's key was wiped", "That's on purpose. Pair again to keep going.")
    is AppError.Security -> ErrorCopy(
        ErrorLook.Security, "$host rejected this phone's signature",
        "If you didn't just reinstall the app, lock the laptop.", requestId = requestId,
    )
    AppError.ReattestFailed -> ErrorCopy(
        ErrorLook.Security, "This phone couldn't prove it's unchanged",
        "$host checks every day that the bootloader is still locked. If you didn't change anything, lock the laptop.",
    )
    is AppError.RateLimited -> ErrorCopy(ErrorLook.Limits, "Too many requests", "The laptop limits how fast this phone can ask. The button counts down.")
    is AppError.FolderBusy -> ErrorCopy(
        ErrorLook.Limits, "A session is already running in this folder",
        "Claude takes one per folder. Open that one, or start this one in its own worktree.",
    )
    AppError.ConversationOpen -> ErrorCopy(ErrorLook.Limits, "That conversation is open on $host right now", "Hand it off to a fresh session instead, or close it there and try again.")
    is AppError.SessionCap -> ErrorCopy(ErrorLook.Limits, "$host is already running as many sessions as it allows", "End one below to start this one.")
    is AppError.ClockSkew -> ErrorCopy(
        ErrorLook.Limits, "Your phone's clock is ${abs(offsetMs) / 1000} s off",
        "Signed requests only count within 30 s of $host's time. Turn on automatic date and time.",
    )
    AppError.Untrusted -> ErrorCopy(
        ErrorLook.Limits, "Claude doesn't trust this folder yet",
        "remoter on $host is older than this app. Update it there and it trusts the folders it starts in by itself.",
    )
    AppError.NotFound -> ErrorCopy(ErrorLook.Inline, "That folder isn't there anymore")
    is AppError.Denied -> ErrorCopy(ErrorLook.Limits, "Sessions can't start here", "Folders like .ssh and your home itself are off limits for sessions.")
    AppError.FingerprintLockedOut -> ErrorCopy(
        ErrorLook.Permission, "Fingerprint is locked out for now",
        "Try again in a bit, or use your phone's lock screen to reset it.",
    )
}

/** Pair again is the only way out of these. */
val AppError.needsPairAgain get() = this == AppError.DeviceUnknown || this == AppError.KeyInvalidated
