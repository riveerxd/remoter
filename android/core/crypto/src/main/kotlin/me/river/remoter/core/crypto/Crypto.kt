package me.river.remoter.core.crypto

import me.river.remoter.core.net.Signed
import okhttp3.HttpUrl

/** What the fingerprint prompt says. Built from the same request that gets signed. */
data class PromptCopy(val title: String, val subtitle: String? = null)

sealed interface SignResult {
    data class Ok(val signed: Signed) : SignResult

    /** cancelled on purpose. not an error, the button just springs back */
    data object Cancelled : SignResult

    /** too many tries, and there's no PIN fallback */
    data object LockedOut : SignResult

    /** A new fingerprint was enrolled and the key is gone, by design. Pair again. */
    data object KeyInvalidated : SignResult
}

/**
 * The StrongBox `sig` key, one fingerprint per signature. The timestamp is
 * taken after the finger, so a slow finger can't push it out of the window.
 */
interface RequestSigner {
    suspend fun sign(method: String, url: HttpUrl, body: ByteArray, prompt: PromptCopy): SignResult
}

enum class SecurityLevel { StrongBox, Tee, Software }

data class KeyLevels(val sig: SecurityLevel, val tls: SecurityLevel)

sealed interface PairEvent {
    /** Type this on the laptop. [bootKey] is the first 8 hex of the attested boot key. */
    data class Code(val code: String, val bootKey: String) : PairEvent
    data class Paired(val hostname: String, val deviceId: String, val serverFp: String, val sig: SecurityLevel, val tls: SecurityLevel, val port: Int = 8443) : PairEvent

    /** Hard stops: a full screen, no continue anyway. */
    data object Expired : PairEvent
    data object ServerKeyMismatch : PairEvent
    data object NoStrongBox : PairEvent

    /** The laptop said no: wrong code typed, or the one attempt was used. */
    data object Rejected : PairEvent
    data object Unreachable : PairEvent
}

/** Generates both keys over the challenge, talks to port 8444 once, and never retries on its own. */
interface Pairer {
    fun pair(link: me.river.remoter.core.net.Pairing.Link, deviceName: String): kotlinx.coroutines.flow.Flow<PairEvent>
}
