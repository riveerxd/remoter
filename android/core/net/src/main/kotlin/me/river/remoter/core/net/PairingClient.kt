package me.river.remoter.core.net

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TlsVersion
import java.io.IOException
import java.net.InetSocketAddress
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext

sealed interface PairOutcome {
    data class Ok(val response: PairResponse) : PairOutcome
    data object KeyMismatch : PairOutcome
    data object Expired : PairOutcome
    data object Rejected : PairOutcome
    data object Unreachable : PairOutcome
}

// no client certificate yet. the request waits while the code is typed on the laptop,
// so the read timeout covers the whole window
class PairingClient(private val network: () -> Network?) {
    suspend fun post(link: Pairing.Link, body: PairRequest): PairOutcome = withContext(Dispatchers.IO) {
        val n = network() ?: return@withContext PairOutcome.Unreachable
        var mismatch = false
        val trust = object : javax.net.ssl.X509TrustManager {
            val pinned = PinnedTrustManager(link.serverFp)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                try {
                    pinned.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    mismatch = true
                    throw e
                }
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = pinned.checkClientTrusted(chain, authType)
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(trust), null) }
        val client = OkHttpClient.Builder()
            .socketFactory(n.socketFactory)
            .sslSocketFactory(ssl.socketFactory, trust)
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .hostnameVerifier(HostnameVerifier { host, s -> host == link.host && (s.peerCertificates.firstOrNull() as? X509Certificate)?.let(trust.pinned::matches) == true })
            .dns(RemoterHttp.LiteralDns)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.MINUTES)
            .retryOnConnectionFailure(false)
            .build()
        val req = Request.Builder()
            .url("https://${link.host}:${link.pairPort}/pair")
            .post(RemoterJson.encodeToString(PairRequest.serializer(), body).toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { r ->
                val text = r.body.string()
                when {
                    r.isSuccessful -> PairOutcome.Ok(RemoterJson.decodeFromString(PairResponse.serializer(), text))
                    else -> when (runCatching { RemoterJson.decodeFromString(ErrorBody.serializer(), text).code }.getOrNull()) {
                        ErrorCode.PairExpired -> PairOutcome.Expired
                        else -> PairOutcome.Rejected
                    }
                }
            }
        } catch (e: IOException) {
            if (mismatch) PairOutcome.KeyMismatch else PairOutcome.Unreachable
        }
    }
}

class TcpReachability(private val network: () -> Network?, private val port: () -> Int) : Reachability {
    override suspend fun laptopAnswers(): Boolean = withContext(Dispatchers.IO) {
        val n = network() ?: return@withContext false
        runCatching {
            n.socketFactory.createSocket().use { it.connect(InetSocketAddress(LAPTOP_ADDR, port()), 3_000) }
        }.isSuccess
    }
}
