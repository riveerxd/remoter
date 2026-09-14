package me.river.remoter.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Hand-built KeyDescription in the shape KeyMint emits.
// TODO: use the real S25 chains as fixtures instead
class AttestationTest {
    private fun tlv(tag: Int, body: ByteArray) = byteArrayOf(tag.toByte(), body.size.toByte()) + body
    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private val int0 = tlv(0x02, byteArrayOf(0))
    private val enum2 = tlv(0x0a, byteArrayOf(2))
    private val oct = { b: ByteArray -> tlv(0x04, b) }

    /** [704] in DER high tag form: context class, constructed, tag 704 = 0x05 0x40. */
    private fun rootOfTrust(bootKey: ByteArray): ByteArray {
        val rot = seq(oct(bootKey), tlv(0x01, byteArrayOf(0xff.toByte())), tlv(0x0a, byteArrayOf(0)), oct(ByteArray(32) { 9 }))
        return byteArrayOf(0xbf.toByte(), 0x85.toByte(), 0x40, rot.size.toByte()) + rot
    }

    private fun description(hw: ByteArray) =
        seq(int0, enum2, int0, enum2, oct(byteArrayOf(1, 2, 3)), oct(ByteArray(0)), seq(), seq(hw))

    @Test
    fun reads_the_boot_key_from_hardware_enforced() {
        val key = ByteArray(32) { (it * 7).toByte() }
        assertArrayEquals(key, Attestation.verifiedBootKey(description(rootOfTrust(key))))
    }

    @Test
    fun no_root_of_trust_means_no_boot_key() {
        assertNull(Attestation.verifiedBootKey(description(ByteArray(0))))
    }
}
