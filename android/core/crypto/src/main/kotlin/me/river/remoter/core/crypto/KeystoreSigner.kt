package me.river.remoter.core.crypto

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.river.remoter.core.net.B64
import me.river.remoter.core.net.Canonical
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.Signed
import okhttp3.HttpUrl
import java.security.SecureRandom
import java.security.Signature
import kotlin.coroutines.resume

/** The screen that can show the prompt right now. BiometricPrompt needs a FragmentActivity. */
fun interface PromptHost {
    fun current(): FragmentActivity?
}

/**
 * The Signature is initialised first, then handed to the prompt as
 * a CryptoObject, so the key only signs once this very finger is accepted.
 * The timestamp and nonce come after the finger, so a slow finger can't push
 * the request out of the 30 s window. Fingerprint only: no device credential,
 * because a PIN can be watched over a shoulder.
 */
class KeystoreSigner(
    private val keys: Keys,
    private val clock: Clock,
    private val deviceId: () -> String?,
    private val authorize: SigAuthorizer,
) : RequestSigner {
    private val rng = SecureRandom()

    override suspend fun sign(method: String, url: HttpUrl, body: ByteArray, prompt: PromptCopy): SignResult {
        val device = deviceId() ?: return SignResult.KeyInvalidated
        if (!keys.has(SIG_ALIAS)) return SignResult.KeyInvalidated
        val sig = Signature.getInstance("SHA256withECDSA")
        try {
            sig.initSign(keys.privateKey(SIG_ALIAS))
        } catch (e: KeyPermanentlyInvalidatedException) {
            return SignResult.KeyInvalidated
        }
        val s = when (val ready = authorize.authorize(sig, prompt)) {
            is SigAuth.Ok -> ready.sig
            SigAuth.Cancelled -> return SignResult.Cancelled
            SigAuth.LockedOut -> return SignResult.LockedOut
        }
        val ts = clock.nowMs()
        val nonce = B64.encode(ByteArray(16).also(rng::nextBytes))
        val canon = Canonical.build(method, url, device, ts, nonce, body)
        val der = withContext(Dispatchers.Default) {
            s.update(canon.toByteArray())
            s.sign()
        }
        return SignResult.Ok(Signed(method, Canonical.target(url), body, device, ts, nonce, der))
    }

}

/** Unlocks one `sig` operation. Release uses [BiometricAuthorizer]; the e2e build type has its own. */
fun interface SigAuthorizer {
    suspend fun authorize(sig: Signature, copy: PromptCopy): SigAuth
}

sealed interface SigAuth {
    data class Ok(val sig: Signature) : SigAuth
    data object Cancelled : SigAuth
    data object LockedOut : SigAuth
}

class BiometricAuthorizer(private val host: PromptHost) : SigAuthorizer {
    override suspend fun authorize(sig: Signature, copy: PromptCopy): SigAuth = withContext(Dispatchers.Main) {
        val activity = host.current() ?: return@withContext SigAuth.Cancelled
        suspendCancellableCoroutine<SigAuth> { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val s = result.cryptoObject?.signature
                        if (cont.isActive) cont.resume(if (s != null) SigAuth.Ok(s) else SigAuth.Cancelled)
                    }

                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        val r = when (code) {
                            BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> SigAuth.LockedOut
                            else -> SigAuth.Cancelled
                        }
                        if (cont.isActive) cont.resume(r)
                    }
                    // A rejected finger just lets the prompt ask again; it is not an outcome.
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(copy.title)
                .apply { copy.subtitle?.let(::setSubtitle) }
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setNegativeButtonText("Cancel")
                .setConfirmationRequired(false)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(sig))
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}
