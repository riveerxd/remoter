package me.river.remoter.core.net

import okhttp3.HttpUrl
import java.security.MessageDigest

/**
 * The string that gets signed. It is always built from OkHttp's final
 * encoded form of the URL, never from the string the app started with,
 * because OkHttp may re-encode and the laptop verifies what went over the wire.
 */
object Canonical {
    const val TAG = "remoter-sig-v1"

    const val HDR_DEVICE = "Remoter-Device"
    const val HDR_TIMESTAMP = "Remoter-Timestamp"
    const val HDR_NONCE = "Remoter-Nonce"
    const val HDR_SIGNATURE = "Remoter-Signature"

    fun target(url: HttpUrl): String = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")

    fun bodyHashHex(body: ByteArray): String = B64.hex(MessageDigest.getInstance("SHA-256").digest(body))

    fun build(method: String, url: HttpUrl, device: String, timestampMs: Long, nonce: String, body: ByteArray): String =
        buildString {
            append(TAG).append('\n')
            append(method).append('\n')
            append(target(url)).append('\n')
            append(device).append('\n')
            append(timestampMs).append('\n')
            append(nonce).append('\n')
            append(bodyHashHex(body))
        }
}
