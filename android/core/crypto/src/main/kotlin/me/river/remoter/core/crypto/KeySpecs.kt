package me.river.remoter.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.spec.ECGenParameterSpec

const val SIG_ALIAS = "sig"
const val TLS_ALIAS = "tls"

// no switch here on purpose: the e2e relaxed sig key lives in its own source set
object KeySpecs {
    // TEE for now. still hardware
    const val TLS_IN_STRONGBOX = false

    fun sig(challenge: ByteArray): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(SIG_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(true)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)
            .setAttestationChallenge(challenge)
            .build()

    fun tls(challenge: ByteArray, strongBox: Boolean = TLS_IN_STRONGBOX): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(TLS_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // NONE because Conscrypt may hand the key a pre-hashed TLS digest
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .setUnlockedDeviceRequired(true)
            .setAttestationChallenge(challenge)
            .build()

    /** The daily throwaway TEE key: attested over the laptop's challenge, no finger, deleted right after. */
    fun attest(alias: String, challenge: ByteArray): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .build()
}
