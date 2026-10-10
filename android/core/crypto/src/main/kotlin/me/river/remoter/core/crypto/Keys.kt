package me.river.remoter.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import me.river.remoter.core.net.B64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

class Keys(private val sigSpec: (ByteArray, Boolean) -> KeyGenParameterSpec = KeySpecs::sig) {
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun generate(spec: KeyGenParameterSpec) {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    /** Both keys over the pairing challenge. Old ones are replaced: a new pairing is a new identity. */
    fun generatePair(challenge: ByteArray) {
        wipe()
        try {
            generate(sigSpec(challenge, true))
        } catch (e: StrongBoxUnavailableException) {
            // same flags, just not in StrongBox
            generate(sigSpec(challenge, false))
        }
        try {
            generate(KeySpecs.tls(challenge))
        } catch (e: StrongBoxUnavailableException) {
            // Only reachable if TLS_IN_STRONGBOX is on: the TLS key may fall back to the TEE, still hardware.
            generate(KeySpecs.tls(challenge, strongBox = false))
        }
    }

    fun has(alias: String) = ks.containsAlias(alias)

    fun privateKey(alias: String): PrivateKey = ks.getKey(alias, null) as PrivateKey

    fun chain(alias: String): List<X509Certificate> = ks.getCertificateChain(alias).orEmpty().map { it as X509Certificate }

    /** Leaf first, base64url DER, as the pairing and attest bodies carry it. */
    fun chainB64(alias: String): List<String> = chain(alias).map { B64.encode(it.encoded) }

    fun leafSpki(alias: String): ByteArray = chain(alias).first().publicKey.encoded

    fun level(alias: String): SecurityLevel {
        val key = privateKey(alias)
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> SecurityLevel.StrongBox
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SecurityLevel.Tee
            else -> SecurityLevel.Software
        }
    }

    fun keyInfo(alias: String): KeyInfo {
        val key = privateKey(alias)
        return KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
    }

    fun attestOnce(challenge: ByteArray): List<String> {
        val alias = "attest-" + System.nanoTime()
        generate(KeySpecs.attest(alias, challenge))
        return try {
            chainB64(alias)
        } finally {
            ks.deleteEntry(alias)
        }
    }

    fun wipe() {
        listOf(SIG_ALIAS, TLS_ALIAS).forEach { if (ks.containsAlias(it)) ks.deleteEntry(it) }
    }
}
