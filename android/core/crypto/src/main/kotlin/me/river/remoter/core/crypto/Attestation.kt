package me.river.remoter.core.crypto

import java.security.cert.X509Certificate

/** Just enough DER to read `rootOfTrust` from the key description; the laptop does the real verification. */
internal class Der(private val b: ByteArray, private var i: Int = 0, private val end: Int = b.size) {
    data class Tlv(val cls: Int, val constructed: Boolean, val tag: Int, val start: Int, val len: Int)

    fun more() = i < end

    fun next(): Tlv {
        val first = b[i++].toInt() and 0xff
        val cls = first shr 6
        val constructed = first and 0x20 != 0
        var tag = first and 0x1f
        if (tag == 0x1f) {
            tag = 0
            do {
                val x = b[i++].toInt() and 0xff
                tag = (tag shl 7) or (x and 0x7f)
            } while (x and 0x80 != 0)
        }
        var len = b[i++].toInt() and 0xff
        if (len and 0x80 != 0) {
            val n = len and 0x7f
            require(n in 1..3) { "length too long" }
            len = 0
            repeat(n) { len = (len shl 8) or (b[i++].toInt() and 0xff) }
        }
        require(i + len <= end) { "truncated" }
        val t = Tlv(cls, constructed, tag, i, len)
        i += len
        return t
    }

    fun inside(t: Tlv) = Der(b, t.start, t.start + t.len)
    fun bytes(t: Tlv) = b.copyOfRange(t.start, t.start + t.len)
}

object Attestation {
    const val KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17"
    private const val ROOT_OF_TRUST_TAG = 704

    fun verifiedBootKey(leaf: X509Certificate): ByteArray? {
        val ext = leaf.getExtensionValue(KEY_DESCRIPTION_OID) ?: return null
        // getExtensionValue wraps the extension in an OCTET STRING; its contents are the KeyDescription.
        val d = Der(ext)
        return verifiedBootKey(d.bytes(d.next()))
    }

    fun verifiedBootKey(keyDescription: ByteArray): ByteArray? {
        val top = Der(keyDescription)
        val seq = top.inside(top.next())
        // attestationVersion, attestationSecurityLevel, keyMintVersion, keyMintSecurityLevel,
        // attestationChallenge, uniqueId, softwareEnforced, then hardwareEnforced.
        repeat(7) { seq.next() }
        val hw = seq.inside(seq.next())
        while (hw.more()) {
            val t = hw.next()
            if (t.cls == 2 && t.tag == ROOT_OF_TRUST_TAG) {
                val wrap = hw.inside(t)
                val rot = wrap.inside(wrap.next())
                return rot.bytes(rot.next())
            }
        }
        return null
    }
}
