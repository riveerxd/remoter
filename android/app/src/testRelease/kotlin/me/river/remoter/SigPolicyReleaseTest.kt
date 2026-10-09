package me.river.remoter

import android.security.keystore.KeyProperties
import me.river.remoter.core.crypto.BiometricAuthorizer
import me.river.remoter.core.crypto.PromptHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Whatever e2e relaxes, the release sig key keeps every flag. */
@RunWith(RobolectricTestRunner::class)
class SigPolicyReleaseTest {
    @Test
    fun release_sig_key_has_every_flag() {
        val s = SigPolicy.sigSpec(ByteArray(16))
        assertTrue(s.isStrongBoxBacked)
        assertTrue(s.isUserAuthenticationRequired)
        assertEquals(0, s.userAuthenticationValidityDurationSeconds)
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, s.userAuthenticationType)
        assertTrue(s.isInvalidatedByBiometricEnrollment)
        assertTrue(s.isUnlockedDeviceRequired)
    }

    @Test
    fun release_signs_through_biometric_prompt() {
        assertTrue(SigPolicy.authorizer(PromptHost { null }) is BiometricAuthorizer)
    }
}
