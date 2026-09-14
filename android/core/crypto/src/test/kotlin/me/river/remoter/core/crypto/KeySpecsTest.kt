package me.river.remoter.core.crypto

import android.security.keystore.KeyProperties
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.spec.ECGenParameterSpec

/** Every flag on the sig key a real build ships, so a relaxation can only live in e2e. */
@RunWith(RobolectricTestRunner::class)
class KeySpecsTest {
    private val challenge = ByteArray(16) { it.toByte() }

    @Test
    fun release_sig_spec_has_every_flag() {
        val s = KeySpecs.sig(challenge)
        assertEquals(SIG_ALIAS, s.keystoreAlias)
        assertEquals(KeyProperties.PURPOSE_SIGN, s.purposes)
        assertEquals("secp256r1", (s.algorithmParameterSpec as ECGenParameterSpec).name)
        assertArrayEquals(arrayOf(KeyProperties.DIGEST_SHA256), s.digests)
        assertTrue("StrongBox, no fallback", s.isStrongBoxBacked)
        assertTrue(s.isUserAuthenticationRequired)
        assertEquals("one finger, one signature", 0, s.userAuthenticationValidityDurationSeconds)
        assertEquals("fingerprint only, no PIN", KeyProperties.AUTH_BIOMETRIC_STRONG, s.userAuthenticationType)
        assertTrue(s.isInvalidatedByBiometricEnrollment)
        assertTrue(s.isUnlockedDeviceRequired)
        assertArrayEquals(challenge, s.attestationChallenge)
    }

    @Test
    fun no_way_to_build_a_relaxed_sig_key() {
        // The only sig builder takes the challenge and nothing else, so no flag can turn the finger off.
        val builders = KeySpecs::class.java.declaredMethods.filter { it.name == "sig" }
        assertEquals(1, builders.size)
        assertArrayEquals(arrayOf<Class<*>>(ByteArray::class.java), builders[0].parameterTypes)
    }

    @Test
    fun tls_spec() {
        val s = KeySpecs.tls(challenge)
        assertEquals(TLS_ALIAS, s.keystoreAlias)
        assertArrayEquals(arrayOf(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256), s.digests)
        assertEquals(KeySpecs.TLS_IN_STRONGBOX, s.isStrongBoxBacked)
        assertTrue(s.isUnlockedDeviceRequired)
        assertFalse("TLS runs without a finger", s.isUserAuthenticationRequired)
        assertArrayEquals(challenge, s.attestationChallenge)
    }
}
