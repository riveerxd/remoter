package me.river.remoter.core.testing

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.river.remoter.core.net.ApiException
import me.river.remoter.core.net.AuditPage
import me.river.remoter.core.net.Conversation
import me.river.remoter.core.net.ErrorBody
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.Event
import me.river.remoter.core.net.Health
import me.river.remoter.core.net.HistoryResponse
import me.river.remoter.core.net.IdEvent
import me.river.remoter.core.net.ListResponse
import me.river.remoter.core.net.LiveEvent
import me.river.remoter.core.net.LockResponse
import me.river.remoter.core.net.MkdirRequest
import me.river.remoter.core.net.MkdirResponse
import me.river.remoter.core.net.Phase
import me.river.remoter.core.net.Proc
import me.river.remoter.core.net.ProcsResponse
import me.river.remoter.core.net.Resources
import me.river.remoter.core.net.SignalRequest
import me.river.remoter.core.net.RecentResponse
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.RemoterJson
import me.river.remoter.core.net.SearchResponse
import me.river.remoter.core.net.SessionDetail
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.SessionSummary
import me.river.remoter.core.net.SessionsResponse
import me.river.remoter.core.net.Signed
import me.river.remoter.core.net.SpawnMode
import me.river.remoter.core.net.SpawnRequest
import me.river.remoter.core.net.SpawnResponse
import me.river.remoter.core.net.StuckReason
import me.river.remoter.core.net.UnreachableException
import me.river.remoter.core.net.ViewTokenResponse

enum class SpawnScript { Ready, Stuck, Exited, Slow, DropOnce, HandoffFailed }

/**
 * Serves the responses remoter-proto writes, so every screen runs without a
 * laptop and shows what the daemon really sends. Tests and the
 * debug build steer it: [failWith] fails every call with that code,
 * [unreachable] makes calls fail as if the laptop never answered.
 */
class FixtureBackend(
    failWith: ErrorCode? = null,
    unreachable: Boolean = false,
    var spawnScript: SpawnScript = SpawnScript.Ready,
    /** Delay between SSE phases. The debug build uses real time, tests virtual. */
    var phaseStepMs: Long = 700,
    var emptyHome: Boolean = false,
    /**
     * The laptop's one same-folder session per folder. On in the demo app so folder busy shows up
     * there; off by default, since most tests start where the sample sessions already run.
     */
    var oneSessionPerFolder: Boolean = false,
    /** View tokens last 15 minutes from now, like the laptop's, not from the fixture's fixed time. */
    var nowMs: () -> Long = { System.currentTimeMillis() },
) : RemoterApi {
    /** Bumped on every change the laptop would push, so open streams look again. */
    private val version = MutableStateFlow(0)
    private fun changed() {
        version.value++
    }

    var failWith: ErrorCode? = failWith
        set(v) {
            field = v
            changed()
        }

    /** Setting it drops open streams, as a tunnel going away would. */
    var unreachable: Boolean = unreachable
        set(v) {
            field = v
            changed()
        }

    /** How many times [live] was subscribed, so tests can see a reconnect. */
    var liveCalls = 0
        private set

    val spawnCalls = mutableListOf<Signed>()
    val killCalls = mutableListOf<Signed>()
    val mkdirCalls = mutableListOf<Signed>()
    val viewTokenCalls = mutableListOf<Signed>()
    val historyCalls = mutableListOf<String>()
    val signalCalls = mutableListOf<Signed>()

    /** Processes a signal took down, gone from the next list like on the laptop. */
    private val signalled = mutableSetOf<Int>()

    /** Null plays a laptop from before resources were sent. */
    var resources: Resources? = null
        get() = field ?: if (sendResources) get<Resources>("resources") else null
        set(v) {
            field = v
            changed()
        }
    var sendResources = true

    /** False makes the laptop refuse every token for history, as an expired one would be. */
    var historyTokenValid = true

    /** Past conversations per folder. The demo gets a week of plausible ones, timed from [nowMs]. */
    var history: (path: String) -> List<Conversation> = { if (emptyHome) emptyList() else demoHistory() }
    var lockCalls = 0
        private set

    private val responses: Map<String, JsonElement> by lazy { load("responses.json").jsonObject }
    private val errors: Map<ErrorCode, Pair<Int, ErrorBody>> by lazy {
        load("errors.json").jsonArray.associate {
            val body = RemoterJson.decodeFromJsonElement(ErrorBody.serializer(), it.jsonObject["body"]!!)
            val status = it.jsonObject["status"]!!.jsonPrimitive.content.toInt()
            body.code to (status to body)
        }
    }
    private val live = mutableMapOf<String, SessionSummary>()
    private val progress = mutableMapOf<String, Int>()
    /** Starts whose body asked for a handoff: they get the handoff phase, which the summarizer takes a while over. */
    private val handoffs = mutableSetOf<String>()
    /** How long the handoff phase takes on top of a step. A few seconds, so the demo shows the step. */
    var handoffMs: Long = 4_000

    private fun load(name: String): JsonElement {
        val stream = FixtureBackend::class.java.classLoader!!.getResourceAsStream(name)
            ?: error("fixture $name missing from the classpath")
        return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() })
    }

    fun error(code: ErrorCode): ApiException = errors.getValue(code).let { ApiException(it.first, it.second) }

    private fun gate() {
        if (unreachable) throw UnreachableException()
        failWith?.let { throw error(it) }
    }

    private inline fun <reified T> get(key: String): T {
        gate()
        return RemoterJson.decodeFromJsonElement(responses.getValue(key))
    }

    override suspend fun health(): Health = get<Health>(healthKey).copy(sessions = live.size)

    /** "health_direct" to play a laptop the phone dials straight. */
    var healthKey = "health"
    override suspend fun list(path: String, hidden: Boolean): ListResponse =
        get<ListResponse>("list").copy(path = path)
    override suspend fun search(query: String, path: String): SearchResponse = get<SearchResponse>("search").copy(query = query)
    override suspend fun recent(): RecentResponse =
        get<RecentResponse>("recent").let { if (emptyHome) it.copy(entries = emptyList(), typicalStartMs = null) else it }

    private val ended = mutableSetOf<String>()

    /** A session that ends on the laptop, not from this phone: its window closed, or claude exited. */
    fun endOnLaptop(id: String) {
        live.remove(id)
        ended += id
        changed()
    }

    /** A session's state moves on the laptop, as a stuck one recovering would. */
    fun setState(id: String, state: SessionState) {
        live[id]?.let { live[id] = it.copy(state = state) } ?: run {
            val s = current().firstOrNull { it.id == id } ?: return
            live[id] = s.copy(state = state)
        }
        changed()
    }

    private fun current(): List<SessionSummary> {
        val base = RemoterJson.decodeFromJsonElement<SessionsResponse>(responses.getValue("sessions"))
        val sample = if (emptyHome) emptyList() else base.sessions.filter { it.id !in ended && it.id !in live }
        return live.values.toList() + sample
    }

    override suspend fun sessions(): SessionsResponse = get<SessionsResponse>("sessions").copy(sessions = current())

    /** A snapshot on connect, then one whenever the list changes, the way remoterd pushes. */
    override fun live(): Flow<LiveEvent> = flow {
        liveCalls++
        var sent: List<SessionSummary>? = null
        var health: Health? = null
        var res: Resources? = null
        version.collect {
            gate()
            val list = current()
            val h = get<Health>(healthKey).copy(sessions = list.size)
            if (h != health) emit(LiveEvent.Health(h)).also { health = h }
            if (list != sent) emit(LiveEvent.Sessions(list)).also { sent = list }
            resources?.takeIf { it != res }?.let { emit(LiveEvent.Resources(it)); res = it }
        }
    }

    override suspend fun session(id: String, viewToken: String): SessionDetail {
        val d = get<SessionDetail>("session_detail")
        return d.copy(session = live[id] ?: d.session)
    }

    override suspend fun history(path: String, viewToken: String): HistoryResponse {
        historyCalls += path
        gate()
        if (!historyTokenValid) throw error(ErrorCode.ViewTokenRequired)
        return get<HistoryResponse>("history").copy(path = path, conversations = history(path))
    }

    private fun demoHistory(): List<Conversation> {
        val base = get<HistoryResponse>("history").conversations
        val now = nowMs()
        val h = 3_600_000L
        val d = 24 * h
        fun past(id: Char, title: String, prompt: String?, ago: Long, branch: String?) =
            Conversation("${id}0b4910a-dc2c-41b3-81c1-b8c9fd626592", title, prompt, now - ago - 2 * h, now - ago, branch, open = false)
        return listOf(
            base[1].copy(started = now - 3 * h, updated = now - 4 * 60_000),
            base[0].copy(started = now - d - 5 * h, updated = now - d - 3 * h),
            past('a', "tidy the settings screen", "the danger zone still sits too close to the toggles", 2 * d + 2 * h, "main"),
            past('b', "release signing", "sign the release with the key in remoter-signing", 4 * d, "main"),
            past('c', "pairing qr flow", "the scan frame should snap to the code", 9 * d, "pairing"),
            past('d', "first sketch", null, 20 * d, null),
        )
    }

    override suspend fun audit(before: Long?): AuditPage = get("audit")

    /** The two fixture processes plus enough of a desktop to scroll and sort, sessions tagged like the agent tags them. */
    override suspend fun procs(): ProcsResponse {
        val base = get<ProcsResponse>("procs")
        val (claude, sshd) = base.procs
        val gb = 1_000_000_000L
        val sessions = current().filter { it.state != SessionState.Exited && it.state != SessionState.Gone }
        fun mine(pid: Int, name: String, cmd: String, cpu: Double, rss: Long, ppid: Int = 1) =
            claude.copy(pid = pid, ppid = ppid, start = 1_000L + pid, name = name, cmd = cmd, cpuPct = cpu, rss = rss, session = null)
        val tagged = sessions.mapIndexed { i, s ->
            claude.copy(pid = claude.pid + i * 10, cmd = "claude --remote-control=${s.name}", cpuPct = listOf(104.5, 3.1, 0.4)[i % 3], session = claude.session?.copy(id = s.id, name = s.name))
        }
        val all = tagged + listOf(
            mine(2210, "firefox", "/usr/lib/firefox/firefox", 18.2, 2 * gb + 300_000_000),
            mine(2290, "Isolated Web Co", "/usr/lib/firefox/firefox -contentproc -isForBrowser", 9.6, 820_000_000, 2210),
            mine(1730, "Hyprland", "Hyprland", 4.8, 240_000_000),
            mine(3120, "cargo", "cargo test --workspace", 37.0, 610_000_000),
            mine(3125, "rustc", "rustc --crate-name remoter_agent --edition=2024", 96.3, 1_400_000_000, 3120),
            mine(1801, "kitty", "kitty", 0.7, 150_000_000),
            mine(1802, "zsh", "-zsh", 0.0, 9_000_000, 1801),
            mine(2050, "java", "java -Xmx2g org.gradle.launcher.daemon.bootstrap.GradleDaemon", 2.2, 3 * gb),
            mine(1650, "pipewire", "/usr/bin/pipewire", 0.3, 22_000_000),
            sshd,
            sshd.copy(pid = 640, name = "NetworkManager", cmd = "/usr/bin/NetworkManager --no-daemon", cpuPct = 0.1, rss = 31_000_000),
            sshd.copy(pid = 1, ppid = 0, name = "systemd", cmd = "/sbin/init", cpuPct = 0.0, rss = 14_000_000),
        )
        return base.copy(resources = resources ?: base.resources, procs = all.filter { it.pid !in signalled }.sortedByDescending { it.cpuPct })
    }

    /** A process that exits on its own, not from this phone. */
    fun endProcess(pid: Int) {
        signalled += pid
        changed()
    }

    override suspend fun signal(signed: Signed) {
        signalCalls += signed
        gate()
        val pid = signed.target.removePrefix("/v1/procs/").removeSuffix("/signal").toInt()
        RemoterJson.decodeFromString(SignalRequest.serializer(), signed.body.decodeToString())
        signalled += pid
        changed()
    }
    override suspend fun lock(): LockResponse {
        gate()
        lockCalls++
        return LockResponse(true)
    }

    override suspend fun mkdir(signed: Signed): MkdirResponse {
        mkdirCalls += signed
        gate()
        val req = RemoterJson.decodeFromString(MkdirRequest.serializer(), signed.body.decodeToString())
        return MkdirResponse(if (req.parent.isEmpty()) req.name else "${req.parent}/${req.name}")
    }

    override suspend fun spawn(signed: Signed): SpawnResponse {
        spawnCalls += signed
        gate()
        // idempotent retry: same signed bytes, same answer
        val first = spawnCalls.indexOfFirst { it.nonce == signed.nonce }
        val resp = get<SpawnResponse>("spawn").let { r -> r.copy(id = r.id.dropLast(1) + ('a' + first % 26), viewTokenExpires = nowMs() + 15 * 60_000) }
        val req = RemoterJson.decodeFromString(SpawnRequest.serializer(), signed.body.decodeToString())
        // The laptop's rule: one same-folder session per folder at a time. A retry of the same bytes
        // already got its answer above, so it never trips this.
        if (oneSessionPerFolder && first == spawnCalls.lastIndex && req.mode == SpawnMode.SameDir) {
            // like the agent: a start claude refused, or that it stopped at the trust dialog, has no claude left in the folder
            val busy = current().filter { it.path == req.path && it.state != SessionState.Exited && it.state != SessionState.Gone && it.reason != StuckReason.Untrusted }
            if (busy.isNotEmpty()) {
                val base = error(ErrorCode.FolderBusy)
                throw ApiException(base.status, base.body.copy(sessions = busy))
            }
        }
        if (req.handoff != null) handoffs += resp.id
        live.getOrPut(resp.id) {
            SessionSummary(resp.id, req.name, req.path, signed.device, signed.timestampMs, SessionState.Starting, null, null)
        }
        changed()
        return resp
    }

    override suspend fun kill(signed: Signed) {
        killCalls += signed
        gate()
        val id = signed.target.substringAfterLast('/')
        live.remove(id)
        // The sample sessions end too, as they would on a real laptop, so the banner collapses.
        ended += id
        changed()
    }

    override suspend fun viewToken(signed: Signed): ViewTokenResponse {
        viewTokenCalls += signed
        return get<ViewTokenResponse>("view_token").copy(expires = nowMs() + 15 * 60_000)
    }

    override suspend fun unpair(signed: Signed) = gate()

    private fun phasesOf(id: String) =
        if (id in handoffs) {
            listOf(Phase.Accepted, Phase.Terminal, Phase.Handoff, Phase.Claude, Phase.RemoteControl)
        } else {
            listOf(Phase.Accepted, Phase.Terminal, Phase.Claude, Phase.RemoteControl)
        }

    override fun events(id: String, viewToken: String?, lastEventId: String?): Flow<IdEvent> = flow {
        gate()
        if (live[id]?.state != SessionState.Starting) return@flow emitAll(settled(id, viewToken))
        var i = lastEventId?.toIntOrNull()?.plus(1) ?: 0
        val slowExtra = if (spawnScript == SpawnScript.Slow) 3_000L else 0L
        val phases = phasesOf(id)
        while (i < phases.size) {
            if (phases[i] == Phase.Handoff && spawnScript == SpawnScript.HandoffFailed) break
            delay(phaseStepMs + (if (i == 2) slowExtra * 3 else 0) + (if (phases[i] == Phase.Handoff) handoffMs else 0))
            emit(IdEvent(i.toString(), Event.PhaseEvent(phases[i], 0)))
            progress[id] = i
            if (spawnScript == SpawnScript.DropOnce && i == 1 && lastEventId == null) return@flow
            i++
        }
        delay(phaseStepMs)
        val tail = get<SessionDetail>("session_detail").tail
        val (state, reason, code) = when (spawnScript) {
            SpawnScript.Stuck -> Triple(SessionState.Stuck, StuckReason.Untrusted, null)
            SpawnScript.HandoffFailed -> Triple(SessionState.Stuck, StuckReason.HandoffFailed, null)
            SpawnScript.Exited -> Triple(SessionState.Exited, null, 1)
            else -> Triple(SessionState.Ready, null, null)
        }
        live[id]?.let { s ->
            val claude = if (state == SessionState.Ready) {
                get<SessionsResponse>("sessions").sessions.first().claude
            } else {
                null
            }
            live[id] = s.copy(state = state, reason = reason, exitCode = code, claude = claude)
        }
        changed()
        if (state != SessionState.Ready) emit(IdEvent("t", Event.TailEvent(tail, 0)))
        emit(IdEvent("s", Event.StateEvent(state, reason, code)))
    }

    /**
     * A session that is already up, as the detail screen opens it: its state and, with a token,
     * the screen at once, then whatever changes. Ends when the session does, like remoterd's.
     */
    private fun settled(id: String, viewToken: String?): Flow<IdEvent> {
        var state: SessionSummary? = null
        var tailSent = false
        return version.transformWhile {
            gate()
            val s = current().firstOrNull { it.id == id } ?: return@transformWhile false
            if (state?.state != s.state || state?.reason != s.reason || state?.exitCode != s.exitCode) {
                emit(IdEvent(null, Event.StateEvent(s.state, s.reason, s.exitCode)))
            }
            state = s
            if (viewToken != null && !tailSent) {
                val d = get<SessionDetail>("session_detail")
                emit(IdEvent(null, Event.TailEvent(d.tail, d.tailAt)))
                tailSent = true
            }
            true
        }
    }

    fun summary(id: String): SessionSummary? = live[id]
}
