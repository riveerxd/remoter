package me.river.remoter.core.net

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Pairing transcript, MAC, confirmation code and link, byte for byte as in remoter-proto. */
object Pairing {
    private val TAG = "remoter-pair-v1".toByteArray()

    /** Each field carries a 4 byte big endian length, so no name can shift bytes between fields. */
    fun transcript(serverFp: ByteArray, tlsSpki: ByteArray, sigSpki: ByteArray, deviceName: String): ByteArray {
        val parts = listOf(TAG, serverFp, tlsSpki, sigSpki, deviceName.toByteArray())
        val buf = ByteBuffer.allocate(parts.sumOf { it.size + 4 })
        parts.forEach { buf.putInt(it.size).put(it) }
        return buf.array()
    }

    fun mac(secret: ByteArray, transcript: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(transcript)
        }

    fun confirmationCode(secret: ByteArray, transcript: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").run {
            update(secret)
            digest(transcript)
        }
        val n = ByteBuffer.wrap(d, 0, 4).int.toUInt() % 1_000_000u
        return n.toString().padStart(6, '0')
    }

    data class Link(
        val host: String,
        val port: Int,
        val pairPort: Int,
        val serverFp: ByteArray,
        val secret: ByteArray,
        val challenge: ByteArray,
        val expiresUnix: Long,
    ) {
        fun toUri(): String =
            "remoter://pair?v=1&h=$host&p=$port&pp=$pairPort&fp=${B64.encode(serverFp)}" +
                "&s=${B64.encode(secret)}&c=${B64.encode(challenge)}&exp=$expiresUnix"

        override fun equals(other: Any?) = other is Link && toUri() == other.toUri()
        override fun hashCode() = toUri().hashCode()

        // no secret or fp, so a stray log line can't leak them
        override fun toString() = "Link(host=$host, port=$port, pairPort=$pairPort, exp=$expiresUnix)"

        companion object {
            /** Only the exact spelling the laptop prints. A pasted link that is slightly off is refused. */
            fun parse(uri: String): Link? {
                val rest = uri.removePrefix("remoter://pair?").takeIf { it != uri } ?: return null
                val parts = rest.split('&')
                val keys = listOf("v", "h", "p", "pp", "fp", "s", "c", "exp")
                if (parts.size != keys.size) return null
                val v = parts.zip(keys).map { (part, key) -> part.removePrefix("$key=").takeIf { it != part } ?: return null }
                if (v[0] != "1") return null
                val link = Link(
                    host = v[1].takeIf { isIpv4(it) } ?: return null,
                    port = v[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null,
                    pairPort = v[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null,
                    serverFp = B64.decode(v[4])?.takeIf { it.size == 32 } ?: return null,
                    secret = B64.decode(v[5])?.takeIf { it.size == 32 } ?: return null,
                    challenge = B64.decode(v[6])?.takeIf { it.size == 16 } ?: return null,
                    expiresUnix = v[7].takeIf { it.isNotEmpty() && it[0] != '0' && it.all(Char::isDigit) }
                        ?.toLongOrNull() ?: return null,
                )
                return if (link.toUri() == uri) link else null
            }

            private fun isIpv4(s: String): Boolean {
                val o = s.split('.')
                return o.size == 4 && o.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() <= 255 && (it == "0" || it[0] != '0') }
            }
        }
    }
}
