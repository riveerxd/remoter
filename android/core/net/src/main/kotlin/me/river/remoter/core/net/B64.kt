package me.river.remoter.core.net

import java.util.Base64

object B64 {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = enc.encodeToString(bytes)

    /**
     * Strict, like the Rust side: one byte string has exactly one accepted
     * spelling, so padding and non canonical trailing bits are refused.
     */
    fun decode(text: String): ByteArray? {
        if (text.any { !(it.isLetterOrDigit() && it.code < 128) && it != '-' && it != '_' }) return null
        val bytes = try {
            dec.decode(text)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (encode(bytes) == text) bytes else null
    }

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append("0123456789abcdef"[v shr 4])
            append("0123456789abcdef"[v and 0x0f])
        }
    }
}
