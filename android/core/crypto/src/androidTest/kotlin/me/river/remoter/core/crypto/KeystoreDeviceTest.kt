package me.river.remoter.core.crypto

import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

// tests that only mean something with StrongBox and real attestation skip elsewhere, never faked
@RunWith(AndroidJUnit4::class)
class KeystoreDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val strongBox get() = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    private val challenge = ByteArray(16) { 7 }

    @After
    fun clean() {
        ks.aliases().toList().forEach { ks.deleteEntry(it) }
    }

    // release sig spec minus StrongBox, so auth binding can be checked without it
    private fun teeSig(alias: String) {
        val s = KeySpecs.sig(challenge)
        val spec = KeyGenParameterSpec.Builder(alias, s.purposes)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(*s.digests)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)
            .setAttestationChallenge(challenge)
            .build()
        KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    @Test
    fun no_strongbox_falls_back_to_the_tee() {
        assumeFalse("this device has StrongBox", strongBox)
        val keys = Keys()
        keys.generatePair(challenge)
        val weak = weaknesses(keys.level(SIG_ALIAS), Attestation.rootOfTrust(keys.chain(SIG_ALIAS).first()))
        android.util.Log.i("remoter-weak", "${keys.level(SIG_ALIAS)}: ${weak.joinToString()}")
        assertTrue(me.river.remoter.core.net.Weakness.NoStrongBox in weak)
        assertTrue(keys.keyInfo(SIG_ALIAS).isUserAuthenticationRequired)
        // an emulator has no TEE, its keystore is software
        assertEquals(SecurityLevel.Tee, keys.level(SIG_ALIAS))
    }

    @Test
    fun auth_bound_key_carries_its_flags() {
        teeSig("t-sig")
        val info = Keys().keyInfo("t-sig")
        assertTrue(info.isUserAuthenticationRequired)
        assertEquals(0, info.userAuthenticationValidityDurationSeconds)
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, info.userAuthenticationType)
        assertTrue(info.isInvalidatedByBiometricEnrollment)
    }

    @Test
    fun signing_without_a_finger_throws() {
        teeSig("t-sig")
        val sig = Signature.getInstance("SHA256withECDSA")
        val err = runCatching {
            sig.initSign(ks.getKey("t-sig", null) as java.security.PrivateKey)
            sig.update(byteArrayOf(1, 2, 3))
            sig.sign()
        }.exceptionOrNull()
        assertNotNull("a signature came out with no finger", err)
    }

    @Test
    fun tls_key_signs_without_a_finger() {
        KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply { initialize(KeySpecs.tls(challenge, strongBox = false)) }.generateKeyPair()
        val km = TlsKeyManager(Keys())
        assertEquals(TLS_ALIAS, km.chooseClientAlias(arrayOf("EC"), null, null))
        val chain = km.getCertificateChain(TLS_ALIAS)!!
        assertTrue(chain.isNotEmpty())
        // Conscrypt may sign a pre-hashed digest, hence NONEwithECDSA.
        val s = Signature.getInstance("NONEwithECDSA").apply { initSign(km.getPrivateKey(TLS_ALIAS)); update(ByteArray(32) { 1 }) }.sign()
        assertTrue(s.isNotEmpty())
    }

    @Test
    fun attest_key_exports_chain_then_is_deleted() {
        val chain = Keys().attestOnce(challenge)
        assertTrue("leaf plus at least one issuer", chain.size >= 2)
        assertFalse(ks.aliases().toList().any { it.startsWith("attest-") })
    }
}

// the emulator run filters these out
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class RequiresS25

@RequiresS25
@RunWith(AndroidJUnit4::class)
class StrongBoxDeviceTest {
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val challenge = ByteArray(16) { 7 }

    @After
    fun clean() {
        ks.aliases().toList().forEach { ks.deleteEntry(it) }
    }

    @Test
    fun pair_keys_attest_strongbox_and_boot_key() {
        val keys = Keys()
        keys.generatePair(challenge)
        assertEquals(SecurityLevel.StrongBox, keys.level(SIG_ALIAS))
        assertTrue(keys.level(TLS_ALIAS) != SecurityLevel.Software)
        val info = keys.keyInfo(SIG_ALIAS)
        assertTrue(info.isUserAuthenticationRequired && info.isInvalidatedByBiometricEnrollment)
        assertEquals(0, info.userAuthenticationValidityDurationSeconds)
        assertNotNull(Attestation.verifiedBootKey(keys.chain(SIG_ALIAS).first()))
    }

    // public certs only, for checking the laptop's verifier. logcat because Gradle
    // uninstalls the test app, files and all
    @Test
    fun dump_pair_attestation_chains() {
        val keys = Keys()
        keys.generatePair(challenge)
        listOf(SIG_ALIAS, TLS_ALIAS).forEach { alias ->
            keys.chain(alias).forEachIndexed { i, cert ->
                android.util.Log.i("remoter-chain", "$alias $i ${android.util.Base64.encodeToString(cert.encoded, android.util.Base64.NO_WRAP)}")
            }
        }
    }
}
