package me.river.remoter.feature.settings

import me.river.remoter.core.design.components.StatusTone

// anything new the laptop starts logging still reads as words, not snake_case
internal fun auditAction(action: String, result: String): String = when (action) {
    "spawn" -> "Started a session"
    "end" -> "Ended a session"
    "signal" -> "Stopped a process"
    "mkdir" -> "Created a folder"
    "view_token" -> "Showed terminal output"
    "unpair" -> "Unpaired"
    "lock" -> if (result == "auto") "Locked itself" else "Locked"
    "unlock" -> "Unlocked"
    "pair" -> "Paired"
    "pair_open" -> "Opened pairing"
    "reattest" -> "Checked this phone's keys"
    "attestation_data" -> "Read attestation data"
    else -> words(action)
}

// a lock's result is the reason, not an outcome, so it never reads as a failure
internal fun auditResult(action: String, result: String): Pair<String, StatusTone> {
    if (action == "lock") return when (result) {
        "auto" -> "After bad tries" to StatusTone.Warn
        "phone" -> "From phone" to StatusTone.Muted
        else -> words(result) to StatusTone.Muted
    }
    return when (result) {
        "ok" -> "OK" to StatusTone.Muted
        "refused" -> "Refused" to StatusTone.Danger
        "sig_invalid" -> "Bad signature" to StatusTone.Danger
        "nonce_reused" -> "Replayed" to StatusTone.Danger
        "device_unknown" -> "Unknown phone" to StatusTone.Danger
        "code_mismatch" -> "Wrong code" to StatusTone.Danger
        "locked" -> "Locked" to StatusTone.Warn
        "clock_skew" -> "Clock off" to StatusTone.Warn
        "rate_limited" -> "Too fast" to StatusTone.Warn
        "agent_down" -> "Agent down" to StatusTone.Warn
        "desktop_down" -> "No desktop" to StatusTone.Warn
        "not_found" -> "Not found" to StatusTone.Warn
        "exists" -> "Already there" to StatusTone.Warn
        else -> if (result.startsWith("http_")) "Error ${result.removePrefix("http_")}" to StatusTone.Danger else words(result) to StatusTone.Danger
    }
}

// a signal's path is "firefox 2210 term", not a folder
internal fun auditPath(action: String, path: String): String {
    if (action != "signal") return "~/$path"
    val parts = path.split(' ')
    val sig = when (parts.lastOrNull()) {
        "term" -> "SIGTERM"
        "kill" -> "SIGKILL"
        else -> return path
    }
    return (parts.dropLast(1) + sig).joinToString(" · ")
}

private fun words(s: String): String = s.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }.ifEmpty { "Unknown" }
