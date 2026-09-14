package me.river.remoter.core.testing

import kotlinx.coroutines.delay
import me.river.remoter.core.crypto.PromptCopy
import me.river.remoter.core.crypto.RequestSigner
import me.river.remoter.core.crypto.SignResult
import me.river.remoter.core.net.B64
import me.river.remoter.core.net.Canonical
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.Signed
import okhttp3.HttpUrl
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Signs with a throwaway software key and no prompt. Tests queue what the
 * "finger" does next; the debug build waits [fingerMs] so the prompt moment
 * still shows up in recordings.
 */
class FakeSigner(
    private val clock: Clock,
    private val device: String = "01K6B7Y3M4N5P6Q7R8S9T0V1W2",
    var fingerMs: Long = 0,
) : RequestSigner {
    enum class Next { Sign, Cancel, LockOut, Invalidate }

    val outcomes = ArrayDeque<Next>()

    /** When set, the "finger" waits here, so a test can tap again while a prompt is up. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val prompts = mutableListOf<PromptCopy>()
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val rng = SecureRandom()

    override suspend fun sign(method: String, url: HttpUrl, body: ByteArray, prompt: PromptCopy): SignResult {
        prompts += prompt
        if (fingerMs > 0) delay(fingerMs)
        gate?.await()
        return when (outcomes.removeFirstOrNull() ?: Next.Sign) {
            Next.Cancel -> SignResult.Cancelled
            Next.LockOut -> SignResult.LockedOut
            Next.Invalidate -> SignResult.KeyInvalidated
            Next.Sign -> {
                val ts = clock.nowMs()
                val nonce = B64.encode(ByteArray(16).also(rng::nextBytes))
                val canon = Canonical.build(method, url, device, ts, nonce, body)
                val sig = Signature.getInstance("SHA256withECDSA").run {
                    initSign(key.private)
                    update(canon.toByteArray())
                    sign()
                }
                SignResult.Ok(Signed(method, Canonical.target(url), body, device, ts, nonce, sig))
            }
        }
    }
}
