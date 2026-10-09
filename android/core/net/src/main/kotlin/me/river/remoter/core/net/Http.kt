package me.river.remoter.core.net

import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

const val LAPTOP_ADDR = "10.66.66.3"

/**
 * Accepts exactly one server key: the SPKI hash from the pairing link. No CA,
 * no dates, no chain, because the laptop's certificate is self-signed and the
 * key is what was verified at pairing.
 */
class PinnedTrustManager(private val spkiSha256: ByteArray) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
        if (!matches(leaf)) throw CertificateException("server key does not match the paired laptop")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client role not supported")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    fun matches(cert: X509Certificate): Boolean =
        MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded), spkiSha256)
}

object RemoterHttp {
    /** Resolves only our literal address, so a name can never send a request elsewhere. */
    val LiteralDns = Dns { host ->
        if (host == LAPTOP_ADDR) listOf(InetAddress.getByName(LAPTOP_ADDR)) else throw UnknownHostException(host)
    }

    // OkHttp refuses a protocol list without HTTP/1.1, so both are offered; the laptop's ALPN
    // only has h2, so h2 is what gets negotiated
    fun client(
        socketFactory: SocketFactory,
        trust: PinnedTrustManager,
        keyManager: X509ExtendedKeyManager?,
    ): OkHttpClient {
        val ssl = SSLContext.getInstance("TLSv1.3").apply { init(keyManager?.let { arrayOf(it) }, arrayOf(trust), null) }
        val spec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build()
        val verifier = HostnameVerifier { host, session ->
            val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
            host == LAPTOP_ADDR && leaf != null && trust.matches(leaf)
        }
        return OkHttpClient.Builder()
            .socketFactory(socketFactory)
            .sslSocketFactory(ssl.socketFactory, trust)
            .connectionSpecs(listOf(spec))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .hostnameVerifier(verifier)
            .dns(LiteralDns)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
