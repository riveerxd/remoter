package me.river.remoter

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import me.river.remoter.core.crypto.PromptHost
import me.river.remoter.core.crypto.SIG_ALIAS
import me.river.remoter.core.crypto.SigAuth
import me.river.remoter.core.crypto.SigAuthorizer
import java.security.spec.ECGenParameterSpec

// e2e only: a sig key with no fingerprint so UI tests can drive every flow.
// checkReleaseHasNoE2e fails the build if MARKER ever lands in release classes,
// and the real laptop refuses this build anyway (package and signing digest differ).
object SigPolicy {
    const val MARKER = "remoter-e2e-relaxed-signing"

    val sigSpec: (ByteArray, Boolean) -> KeyGenParameterSpec = { challenge, strongBox ->
        KeyGenParameterSpec.Builder(SIG_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .setUnlockedDeviceRequired(true)
            .setAttestationChallenge(challenge)
            .build()
    }

    @Suppress("UNUSED_PARAMETER")
    fun authorizer(host: PromptHost): SigAuthorizer = SigAuthorizer { sig, _ ->
        android.util.Log.i("remoter", MARKER)
        SigAuth.Ok(sig)
    }
}
