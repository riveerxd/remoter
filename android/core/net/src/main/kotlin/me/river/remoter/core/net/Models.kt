package me.river.remoter.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Field names mirror `remoter-proto` exactly; the contract tests parse the
 * Rust fixtures with these classes and fail on any drift. Unknown fields are
 * an error, the same as on the Rust side.
 */
val RemoterJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class Health(
    val hostname: String,
    val version: String,
    @SerialName("server_time") val serverTime: Long,
    val locked: Boolean,
    val sessions: Int,
    @SerialName("on_ac") val onAc: Boolean?,
    @SerialName("battery_pct") val batteryPct: Int?,
    @SerialName("fresh_until") val freshUntil: Long?,
    /** "hub" or "direct". A string, so a newer laptop's next kind can't break the decode. */
    val tunnel: String? = null,
) {
    val direct get() = tunnel == "direct"
}

@Serializable
enum class SymlinkKind {
    @SerialName("none") None,
    @SerialName("relative") Relative,
    @SerialName("absolute") Absolute,
}

@Serializable
enum class DenyReason {
    @SerialName("denied") Denied,
    @SerialName("home") Home,
    @SerialName("untrusted") Untrusted,
    @SerialName("unsupported") Unsupported,
    @SerialName("symlink_absolute") SymlinkAbsolute,
}

@Serializable
data class FsEntry(
    val name: String,
    val mtime: Long,
    @SerialName("is_git") val isGit: Boolean,
    @SerialName("has_claude_md") val hasClaudeMd: Boolean,
    val symlink: SymlinkKind,
    @SerialName("symlink_target") val symlinkTarget: String?,
    @SerialName("file_count") val fileCount: Int?,
    @SerialName("session_count") val sessionCount: Int,
    val trusted: Boolean,
    @SerialName("spawn_allowed") val spawnAllowed: Boolean,
    @SerialName("deny_reason") val denyReason: DenyReason?,
    val unsupported: Boolean,
)

@Serializable
data class ListResponse(
    val path: String,
    @SerialName("is_git") val isGit: Boolean,
    val trusted: Boolean,
    @SerialName("spawn_allowed") val spawnAllowed: Boolean,
    @SerialName("deny_reason") val denyReason: DenyReason?,
    val entries: List<FsEntry>,
    val truncated: Boolean,
    val partial: Boolean,
)

@Serializable
data class SearchHit(val path: String, val name: String, @SerialName("is_git") val isGit: Boolean, val depth: Int)

@Serializable
data class SearchResponse(val query: String, val hits: List<SearchHit>, val capped: Boolean)

@Serializable
data class RecentEntry(
    val path: String,
    val name: String,
    @SerialName("is_git") val isGit: Boolean,
    @SerialName("last_spawn") val lastSpawn: Long,
)

@Serializable
data class RecentResponse(
    val entries: List<RecentEntry>,
    @SerialName("typical_start_ms") val typicalStartMs: Int?,
)

@Serializable
data class MkdirRequest(val parent: String, val name: String, @SerialName("git_init") val gitInit: Boolean)

@Serializable
data class MkdirResponse(val path: String)

@Serializable
enum class SpawnMode {
    @SerialName("same-dir") SameDir,
    @SerialName("worktree") Worktree,
}

@Serializable
data class SpawnRequest(
    val path: String,
    val name: String,
    val mode: SpawnMode,
    /** A past conversation in this folder to bring back. Left out when null, so an older laptop still takes every start. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val resume: String? = null,
    /** A past conversation to start fresh from: the laptop summarizes it and the new session opens with that. Never with [resume]. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val handoff: String? = null,
)

/** A Claude Code conversation that once ran in a folder. The text is cleaned on the laptop and only ever shown. */
@Serializable
data class Conversation(
    val id: String,
    val title: String,
    @SerialName("last_prompt") val lastPrompt: String?,
    val started: Long,
    val updated: Long,
    val branch: String?,
    /** A claude has it open right now, so it can't be resumed until that one ends. */
    val open: Boolean,
)

@Serializable
data class HistoryResponse(val path: String, val conversations: List<Conversation>, val truncated: Boolean)

@Serializable
data class SpawnResponse(
    val id: String,
    @SerialName("view_token") val viewToken: String,
    @SerialName("view_token_expires") val viewTokenExpires: Long,
)

@Serializable
enum class SessionState {
    @SerialName("starting") Starting,
    @SerialName("ready") Ready,
    @SerialName("stuck") Stuck,
    @SerialName("exited") Exited,
    @SerialName("ending") Ending,
    @SerialName("gone") Gone,
}

@Serializable
enum class StuckReason {
    @SerialName("untrusted") Untrusted,
    @SerialName("not_logged_in") NotLoggedIn,
    @SerialName("folder_changed") FolderChanged,
    @SerialName("network") Network,
    @SerialName("timeout") Timeout,
    @SerialName("folder_busy") FolderBusy,
    @SerialName("handoff_failed") HandoffFailed,
}

@Serializable
data class SessionSummary(
    val id: String,
    val name: String,
    val path: String,
    val device: String?,
    val started: Long,
    val state: SessionState,
    val reason: StuckReason?,
    @SerialName("exit_code") val exitCode: Int?,
    /** Set once the session is ready. `null` hides Open Claude rather than disabling it. */
    val claude: ClaudeLink? = null,
    /** The worktree claude made for a worktree session, once it runs in it. */
    val worktree: String? = null,
)

@Serializable
data class ClaudeLink(
    @SerialName("session_id") val sessionId: String,
    @SerialName("session_url") val sessionUrl: String,
    @SerialName("environment_url") val environmentUrl: String?,
)

@Serializable
data class SessionsResponse(val sessions: List<SessionSummary>, val cap: Int)

@Serializable
data class SessionDetail(val session: SessionSummary, val tail: List<String>, @SerialName("tail_at") val tailAt: Long)

@Serializable
data class ViewTokenResponse(val token: String, val expires: Long)

@Serializable
data class AttestChallenge(val challenge: String, val expires: Long)

@Serializable
data class AttestRequest(val chain: List<String>)

@Serializable
data class AttestResponse(@SerialName("fresh_until") val freshUntil: Long)

@Serializable
data class LockResponse(val locked: Boolean)

@Serializable
data class AuditEntry(
    val ts: Long,
    val device: String?,
    val action: String,
    val path: String?,
    val result: String,
    @SerialName("request_id") val requestId: String,
)

@Serializable
data class AuditPage(val entries: List<AuditEntry>, @SerialName("next_before") val nextBefore: Long?)

/** What the laptop has left, pushed as `resources` on the live stream. Sizes are bytes. */
@Serializable
data class Resources(
    @SerialName("cpu_pct") val cpuPct: Double,
    val cores: Int,
    @SerialName("mem_total") val memTotal: Long,
    @SerialName("mem_available") val memAvailable: Long,
    @SerialName("swap_total") val swapTotal: Long,
    @SerialName("swap_free") val swapFree: Long,
    @SerialName("disk_total") val diskTotal: Long,
    @SerialName("disk_free") val diskFree: Long,
) {
    val memUsed get() = (memTotal - memAvailable).coerceAtLeast(0)
    val diskUsed get() = (diskTotal - diskFree).coerceAtLeast(0)
}

@Serializable
data class ProcSession(val id: String, val name: String)

/** One process, htop style. [cpuPct] is of one core, so it goes past 100. */
@Serializable
data class Proc(
    val pid: Int,
    val ppid: Int,
    /** Sent back with a signal, so a pid the laptop reused is never hit. */
    val start: Long,
    val user: String,
    val name: String,
    val cmd: String,
    @SerialName("cpu_pct") val cpuPct: Double,
    val rss: Long,
    val killable: Boolean,
    val session: ProcSession? = null,
)

@Serializable
data class ProcsResponse(val resources: Resources, val procs: List<Proc>, val truncated: Boolean)

@Serializable
enum class Signal {
    @SerialName("term") Term,
    @SerialName("kill") Kill,
}

@Serializable
data class SignalRequest(val start: Long, val signal: Signal)

@Serializable
data class PairRequest(
    @SerialName("device_name") val deviceName: String,
    @SerialName("tls_chain") val tlsChain: List<String>,
    @SerialName("sig_chain") val sigChain: List<String>,
    val mac: String,
)

@Serializable
data class PairResponse(@SerialName("device_id") val deviceId: String, val hostname: String)

@Serializable
enum class ErrorCode {
    @SerialName("path_outside_home") PathOutsideHome,
    @SerialName("path_denied") PathDenied,
    @SerialName("path_unsupported") PathUnsupported,
    @SerialName("not_found") NotFound,
    @SerialName("not_a_directory") NotADirectory,
    @SerialName("name_invalid") NameInvalid,
    @SerialName("exists") Exists,
    @SerialName("untrusted_folder") UntrustedFolder,
    @SerialName("locked") Locked,
    @SerialName("device_unknown") DeviceUnknown,
    @SerialName("reattest_required") ReattestRequired,
    @SerialName("sig_invalid") SigInvalid,
    @SerialName("nonce_reused") NonceReused,
    @SerialName("clock_skew") ClockSkew,
    @SerialName("rate_limited") RateLimited,
    @SerialName("session_cap") SessionCap,
    @SerialName("folder_busy") FolderBusy,
    @SerialName("conversation_open") ConversationOpen,
    @SerialName("process_denied") ProcessDenied,
    @SerialName("view_token_required") ViewTokenRequired,
    @SerialName("spawn_failed") SpawnFailed,
    @SerialName("desktop_down") DesktopDown,
    @SerialName("agent_down") AgentDown,
    @SerialName("pair_expired") PairExpired,
    @SerialName("pair_rejected") PairRejected,
    @SerialName("bad_request") BadRequest,
    @SerialName("internal") Internal,
}

@Serializable
data class ErrorBody(
    val code: ErrorCode,
    /** For logs only. The app maps [code] to its own copy and never shows this. */
    val message: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("retry_after_s") val retryAfterS: Int? = null,
    val sessions: List<SessionSummary>? = null,
    @SerialName("server_time") val serverTime: Long? = null,
)

@Serializable
enum class Phase {
    @SerialName("accepted") Accepted,
    @SerialName("terminal") Terminal,
    @SerialName("handoff") Handoff,
    @SerialName("claude") Claude,
    @SerialName("remote_control") RemoteControl,
}

sealed interface Event {
    @Serializable data class PhaseEvent(val step: Phase, val at: Long) : Event
    @Serializable data class StateEvent(
        val state: SessionState,
        val reason: StuckReason?,
        @SerialName("exit_code") val exitCode: Int?,
    ) : Event
    @Serializable data class TailEvent(val lines: List<String>, val at: Long) : Event

    companion object {
        /** `null` for an event name this build doesn't know, which is skipped, not fatal. */
        fun parse(name: String, data: String): Event? = when (name) {
            "phase" -> RemoterJson.decodeFromString<PhaseEvent>(data)
            "state" -> RemoterJson.decodeFromString<StateEvent>(data)
            "tail" -> RemoterJson.decodeFromString<TailEvent>(data)
            else -> null
        }
    }
}
