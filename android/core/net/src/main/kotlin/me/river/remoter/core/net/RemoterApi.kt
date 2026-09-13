package me.river.remoter.core.net

import kotlinx.coroutines.flow.Flow

/**
 * A mutation already signed with the fingerprint key. The API layer only ever
 * sends these bytes as they are, so a retry is byte for byte the same request.
 */
class Signed(
    val method: String,
    val target: String,
    val body: ByteArray,
    val device: String,
    val timestampMs: Long,
    val nonce: String,
    val signatureDer: ByteArray,
) {
    override fun toString() = "Signed($method $target)"
}

data class IdEvent(val id: String?, val event: Event)

/**
 * Everything the app asks the laptop. Reads need mTLS only; mutations carry a
 * signature built by the caller, so this layer never sees the key.
 */
interface RemoterApi {
    suspend fun health(): Health
    suspend fun list(path: String, hidden: Boolean): ListResponse
    suspend fun search(query: String, path: String): SearchResponse
    suspend fun recent(): RecentResponse
    suspend fun sessions(): SessionsResponse
    suspend fun session(id: String, viewToken: String): SessionDetail
    /** Past conversations in a folder. Needs the general view token: old prompts are as private as terminal output. */
    suspend fun history(path: String, viewToken: String): HistoryResponse
    suspend fun audit(before: Long?): AuditPage
    suspend fun lock(): LockResponse

    suspend fun mkdir(signed: Signed): MkdirResponse
    suspend fun spawn(signed: Signed): SpawnResponse
    suspend fun kill(signed: Signed)
    suspend fun viewToken(signed: Signed): ViewTokenResponse
    suspend fun unpair(signed: Signed)

    /** Completes when the stream drops; the caller decides whether to resubscribe. */
    fun events(id: String, viewToken: String?, lastEventId: String?): Flow<IdEvent>

    /** The laptop's session list and health, pushed as they change. Completes or fails when the stream drops. */
    fun live(): Flow<LiveEvent>
}

/** A failure the server described. The app picks its copy from [body].code. */
class ApiException(val status: Int, val body: ErrorBody) : Exception(body.code.name)

/** The request never got an answer. Safe to resend the same signed bytes. */
class UnreachableException(cause: Throwable? = null) : Exception("unreachable", cause)
