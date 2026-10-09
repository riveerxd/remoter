package me.river.remoter.core.net

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509ExtendedKeyManager

private val JSON = "application/json".toMediaType()

/**
 * The real client. Every socket comes from the VPN network's own factory, so
 * with the tunnel down this throws [VpnOffException] before a single packet
 * leaves the phone.
 */
class OkHttpRemoterApi(
    private val network: () -> Network?,
    private val keyManager: X509ExtendedKeyManager,
    private val laptop: () -> PairedLaptop?,
    private val clock: Clock,
) : RemoterApi {
    private var cached: Triple<Long, String, OkHttpClient>? = null

    @Synchronized
    private fun client(): Pair<OkHttpClient, PairedLaptop> {
        val n = network() ?: throw VpnOffException()
        val l = laptop() ?: throw ApiException(401, ErrorBody(ErrorCode.DeviceUnknown, "not paired", ""))
        cached?.let { (h, fp, c) -> if (h == n.networkHandle && fp == l.serverFp) return c to l }
        val pin = B64.decode(l.serverFp) ?: throw IllegalStateException("bad stored fingerprint")
        val c = RemoterHttp.client(n.socketFactory, PinnedTrustManager(pin), keyManager)
        cached = Triple(n.networkHandle, l.serverFp, c)
        return c to l
    }

    private fun base(l: PairedLaptop, url: HttpUrl) = url.newBuilder().port(l.port).build()

    private suspend fun <T> call(req: (PairedLaptop) -> Request, parse: KSerializer<T>?, client: OkHttpClient? = null): T? =
        withContext(Dispatchers.IO) {
            val (c, l) = client()
            val r = try {
                (client ?: c).newCall(req(l)).execute()
            } catch (e: IOException) {
                throw UnreachableException(e)
            }
            r.use { handle(it, parse) }
        }

    private fun <T> handle(r: Response, parse: KSerializer<T>?): T? {
        val text = r.body.string()
        if (!r.isSuccessful) {
            val body = runCatching { RemoterJson.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
                ?: ErrorBody(ErrorCode.Internal, "status ${r.code}", r.header("remoter-request-id").orEmpty())
            throw ApiException(r.code, body)
        }
        return parse?.let { RemoterJson.decodeFromString(it, text) }
    }

    private suspend fun <T> get(url: HttpUrl, s: KSerializer<T>, extra: Map<String, String> = emptyMap()): T =
        call({ l -> Request.Builder().url(base(l, url)).apply { extra.forEach { (k, v) -> header(k, v) } }.build() }, s)!!

    /** Sends exactly the signed bytes: the URL is the signed target, byte for byte, or nothing goes out. */
    private fun signedRequest(l: PairedLaptop, s: Signed): Request {
        val url = HttpUrl.Builder().scheme("https").host(LAPTOP_ADDR).port(l.port).build().resolve(s.target)
            ?: throw IllegalStateException("unparseable target")
        check(Canonical.target(url) == s.target) { "the URL would not match what was signed" }
        val body = if (s.body.isEmpty()) null else s.body.toRequestBody(JSON)
        return Request.Builder()
            .url(url)
            .method(s.method, body ?: if (s.method == "POST") ByteArray(0).toRequestBody(JSON) else null)
            .header(Canonical.HDR_DEVICE, s.device)
            .header(Canonical.HDR_TIMESTAMP, s.timestampMs.toString())
            .header(Canonical.HDR_NONCE, s.nonce)
            .header(Canonical.HDR_SIGNATURE, B64.encode(s.signatureDer))
            .build()
    }

    private suspend fun <T> signed(s: Signed, parse: KSerializer<T>?): T? = call({ l -> signedRequest(l, s) }, parse)

    override suspend fun health() = get(Paths.url("health"), Health.serializer())
    override suspend fun list(path: String, hidden: Boolean) =
        get(Paths.url("fs", "list", query = listOf("path" to path, "hidden" to hidden.toString())), ListResponse.serializer())
    override suspend fun search(query: String, path: String) =
        get(Paths.url("fs", "search", query = listOf("q" to query, "path" to path)), SearchResponse.serializer())
    override suspend fun recent() = get(Paths.url("fs", "recent"), RecentResponse.serializer())
    override suspend fun sessions() = get(Paths.sessions, SessionsResponse.serializer())
    override suspend fun session(id: String, viewToken: String) =
        get(Paths.session(id), SessionDetail.serializer(), mapOf(HDR_VIEW_TOKEN to viewToken))
    override suspend fun history(path: String, viewToken: String) =
        get(Paths.history(path), HistoryResponse.serializer(), mapOf(HDR_VIEW_TOKEN to viewToken))
    override suspend fun procs() = get(Paths.procs, ProcsResponse.serializer())
    override suspend fun audit(before: Long?) =
        get(Paths.url("audit", query = listOfNotNull(before?.let { "before" to it.toString() })), AuditPage.serializer())

    /** mTLS only: making things safer never needs a fingerprint. */
    override suspend fun lock(): LockResponse =
        call({ l -> Request.Builder().url(base(l, Paths.url("lock"))).post(ByteArray(0).toRequestBody(JSON)).build() }, LockResponse.serializer())!!

    override suspend fun mkdir(signed: Signed) = signed(signed, MkdirResponse.serializer())!!
    override suspend fun spawn(signed: Signed) = signed(signed, SpawnResponse.serializer())!!
    override suspend fun kill(signed: Signed) {
        signed<Unit>(signed, null)
    }
    override suspend fun viewToken(signed: Signed) = signed(signed, ViewTokenResponse.serializer())!!
    override suspend fun unpair(signed: Signed) {
        signed<Unit>(signed, null)
    }
    override suspend fun signal(signed: Signed) {
        signed<Unit>(signed, null)
    }

    suspend fun attestChallenge() = get(Paths.url("attest", "challenge"), AttestChallenge.serializer())
    suspend fun attest(req: AttestRequest): AttestResponse = call({ l ->
        Request.Builder().url(base(l, Paths.url("attest")))
            .post(RemoterJson.encodeToString(AttestRequest.serializer(), req).toByteArray().toRequestBody(JSON)).build()
    }, AttestResponse.serializer())!!

    /**
     * A silent stream counts as dead after [readTimeoutMs]: the laptop pings while idle, so missing
     * two pings means the link went away without a FIN, which a tunnel dropping never sends.
     */
    private fun <T> sse(url: HttpUrl, readTimeoutMs: Long, headers: Map<String, String>, parse: (id: String?, type: String, data: String) -> T?): Flow<T> =
        callbackFlow {
            val (c, l) = client()
            val sse = c.newBuilder().readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS).build()
            val req = Request.Builder().url(base(l, url)).header("Accept", "text/event-stream")
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            val source = EventSources.createFactory(sse).newEventSource(req, object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    val ev = runCatching { parse(id, type ?: return, data) }.getOrNull() ?: return
                    trySend(ev)
                }
                override fun onClosed(eventSource: EventSource) {
                    close()
                }
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    close(t ?: UnreachableException())
                }
            })
            awaitClose { source.cancel() }
        }

    override fun events(id: String, viewToken: String?, lastEventId: String?): Flow<IdEvent> = sse(
        Paths.events(id),
        EVENTS_READ_TIMEOUT_MS,
        buildMap {
            viewToken?.let { put(HDR_VIEW_TOKEN, it) }
            lastEventId?.let { put("Last-Event-ID", it) }
        },
    ) { evId, type, data -> Event.parse(type, data)?.let { IdEvent(evId, it) } }

    override fun live(): Flow<LiveEvent> = sse(Paths.live, LIVE_READ_TIMEOUT_MS, emptyMap()) { _, type, data -> LiveEvent.parse(type, data) }

    companion object {
        const val HDR_VIEW_TOKEN = "Remoter-View-Token"

        // live pings every 5 s
        const val LIVE_READ_TIMEOUT_MS = 10_000L

        // so does the session stream
        const val EVENTS_READ_TIMEOUT_MS = 10_000L
    }
}
