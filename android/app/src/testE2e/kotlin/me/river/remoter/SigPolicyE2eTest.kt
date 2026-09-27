package me.river.remoter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The relaxation is exactly the finger and nothing more. */
@RunWith(RobolectricTestRunner::class)
class SigPolicyE2eTest {
    @Test
    fun e2e_drops_only_the_finger() {
        val s = SigPolicy.sigSpec(ByteArray(16))
        assertFalse(s.isUserAuthenticationRequired)
        assertTrue("still StrongBox", s.isStrongBoxBacked)
        assertTrue("still unlock-bound", s.isUnlockedDeviceRequired)
        assertEquals("me.river.remoter.e2e", BuildConfig.APPLICATION_ID)
    }
}
