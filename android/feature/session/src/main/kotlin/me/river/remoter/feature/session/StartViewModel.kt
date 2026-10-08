package me.river.remoter.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.river.remoter.core.crypto.PromptCopy
import me.river.remoter.core.crypto.RequestSigner
import me.river.remoter.core.crypto.SignResult
import me.river.remoter.core.net.ApiException
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.Conversation
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.Event
import me.river.remoter.core.net.LocalStore
import me.river.remoter.core.net.Paths
import me.river.remoter.core.net.Phase
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.RemoterJson
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.StuckReason
import me.river.remoter.core.net.Signed
import me.river.remoter.core.net.SpawnMode
import me.river.remoter.core.net.SpawnRequest
import me.river.remoter.core.net.displayPath
import me.river.remoter.core.net.toAppError
import me.river.remoter.core.net.Names
import java.time.ZoneId
import javax.inject.Inject

data class StartTarget(val path: String, val folder: String, val isGit: Boolean)

data class StartForm(
    val mode: SpawnMode = SpawnMode.SameDir,
    /** Resumed as is (forces [SpawnMode.SameDir]), or with [handoff] just the source of a fresh one. */
    val resume: Conversation? = null,
    /** Always true for a conversation that's open on the laptop. */
    val handoff: Boolean = false,
    val name: String = "",
    val nameInvalid: Boolean = false,
    /** Bumped per refused submit so the field shakes again even when already red. */
    val nameRefusals: Int = 0,
)

val StartForm.resumesAsIs get() = resume != null && !handoff

data class PastRow(val conversation: Conversation, val whenLabel: String)

/** Earlier here. Nothing drawn while [Loading] so a fast answer doesn't flicker. */
sealed interface PastState {
    data object Loading : PastState
    data class Loaded(val rows: ImmutableList<PastRow>) : PastState
    data object Failed : PastState
}

data class StartUi(
    val open: Boolean = false,
    val target: StartTarget? = null,
    val form: StartForm = StartForm(),
    val state: StartState = StartState.Idle,
    val hostname: String = "the laptop",
    /** Median of recent starts, null under three of them. */
    val typicalStartS: Int? = null,
    val retryLeftS: Int? = null,
    val elapsedS: Int = 0,
    val readySnack: StartState.Ready? = null,
    val ending: Boolean = false,
    val endError: AppError? = null,
    val past: PastState = PastState.Loading,
    val pastExpanded: Boolean = false,
    val direct: Boolean = false,
)

fun sessionNameProblem(name: String): String? {
    val n = name.trim(' ')
    val bad = n.firstOrNull { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._- ") }
    return when {
        n.isEmpty() -> "Give it a name"
        n.length > 48 -> "Too long, 48 max"
        bad != null -> "Can't use \"$bad\" in a name"
        n[0] == '-' -> "Can't start with a dash"
        else -> null
    }
}

/** Activity scoped, so closing the sheet never cancels anything: the session carries on and home shows it. */
@HiltViewModel
class StartViewModel @Inject constructor(
    private val api: RemoterApi,
    private val signer: RequestSigner,
    private val clock: Clock,
    private val monitor: ConnectionMonitor,
    private val store: LocalStore,
    private val hub: SessionsHub,
) : ViewModel() {
    /** var so tests can pin it */
    var zone: ZoneId = ZoneId.systemDefault()
    private val _ui = MutableStateFlow(StartUi())
    val ui: StateFlow<StartUi> = _ui.asStateFlow()

    /** The exact bytes that were signed, kept for the idempotent retry. Never in UI state. */
    private var signed: Signed? = null
    private var tail = mutableListOf<String>()
    private var jobs = mutableListOf<Job>()
    /** Kept apart from [jobs]: a retry must not strand the list half loaded. */
    private var pastJob: Job? = null

    private val host get() = monitor.health.value?.hostname ?: store.state.value?.laptop?.hostname ?: "the laptop"

    fun open(target: StartTarget) {
        val s = _ui.value.state
        // A start that is under way keeps the sheet on it; a finished one makes room for a new form.
        if (s !is StartState.Idle && s !is StartState.Ready && s !is StartState.Exited && s !is StartState.NotAccepted) {
            _ui.update { it.copy(open = true) }
            return
        }
        cancelJobs()
        signed = null
        val times = store.state.value?.startTimesMs.orEmpty()
        // current laptops read past sessions with mTLS alone, the token is for older ones
        val token = hub.viewToken() ?: ""
        _ui.value = StartUi(
            open = true,
            target = target,
            form = StartForm(name = Names.sessionNameFromFolder(target.folder)),
            hostname = host,
            direct = monitor.health.value?.direct ?: (store.state.value?.snapshot?.direct == true),
            typicalStartS = if (times.size >= 3) (times.sorted()[times.size / 2] + 500) / 1000 else null,
            past = PastState.Loading,
        )
        pastJob?.cancel()
        pastJob = null
        loadPast(target.path, token)
    }

    private fun loadPast(path: String, token: String) {
        pastJob?.cancel()
        _ui.update { it.copy(past = PastState.Loading) }
        pastJob = viewModelScope.launch {
            val next = try {
                val now = clock.nowMs()
                val rows = api.history(path, token).conversations.map { PastRow(it, pastWhen(it.updated, now, zone)) }
                PastState.Loaded(rows.toImmutableList())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PastState.Failed
            }
            _ui.update { if (it.target?.path == path) it.copy(past = next) else it }
        }
    }

    fun retryPast() {
        val target = _ui.value.target ?: return
        if (_ui.value.past != PastState.Failed) return
        loadPast(target.path, hub.viewToken() ?: "")
    }

    fun expandPast() = _ui.update { it.copy(pastExpanded = true) }

    /**
     * Picks a past conversation, or clears it when it is already picked. One that is open on the
     * laptop can only be the source of a handoff: resuming it would let two claudes run on one
     * transcript, while a handoff only reads it.
     */
    fun selectResume(c: Conversation) {
        val ui = _ui.value
        val target = ui.target ?: return
        val s = ui.state
        if (s !is StartState.Idle && s !is StartState.NotAccepted) return
        if (s is StartState.NotAccepted) {
            // Signed bytes for the old choice must never go out as a retry of the new one.
            cancelJobs()
            signed = null
        }
        val clear = ui.form.resume?.id == c.id
        _ui.update {
            it.copy(
                state = StartState.Idle,
                retryLeftS = null,
                form = it.form.copy(
                    resume = if (clear) null else c,
                    handoff = !clear && c.open,
                    mode = SpawnMode.SameDir,
                    name = if (clear) Names.sessionNameFromFolder(target.folder) else Names.sessionNameFromTitle(c.title),
                    nameInvalid = false,
                ),
            )
        }
    }

    /** Drag, back or Keep in background. Cancels nothing. */
    fun close() = _ui.update { it.copy(open = false) }

    /**
     * Whether the form may change now. Not while a request is out: the fingerprint prompt and the
     * signed bytes already say what goes. After a refusal or a failure it may, and the old signed
     * bytes are dropped, so Retry signs what is on screen instead of resending what was.
     */
    private fun editable(): Boolean {
        val s = _ui.value.state
        if (s !is StartState.Idle && s !is StartState.NotAccepted) return false
        if (s is StartState.NotAccepted) {
            cancelJobs()
            signed = null
            _ui.update { it.copy(state = StartState.Idle, retryLeftS = null) }
        }
        return true
    }

    /**
     * Continue it or Fresh with handoff for the picked conversation. Continue it is refused for one
     * that is open on the laptop. Back to Continue it, the mode goes back to the same folder.
     */
    fun setHandoff(on: Boolean) {
        val ui = _ui.value
        val picked = ui.form.resume ?: return
        if (!on && picked.open) return
        val s = ui.state
        if (s !is StartState.Idle && s !is StartState.NotAccepted) return
        if (s is StartState.NotAccepted) {
            cancelJobs()
            signed = null
        }
        _ui.update {
            it.copy(
                state = StartState.Idle,
                retryLeftS = null,
                form = it.form.copy(handoff = on, mode = if (on) it.form.mode else SpawnMode.SameDir),
            )
        }
    }

    /**
     * A resume runs in its own folder, so with Continue it picked a mode card drops the pick and
     * its name. A handoff starts a fresh session, so there the mode is simply the mode.
     */
    fun setMode(mode: SpawnMode) {
        if (!editable()) return
        val form = _ui.value.form
        if (form.resume != null && !form.handoff) form.resume.let(::selectResume)
        _ui.update { it.copy(form = it.form.copy(mode = mode)) }
    }

    fun setName(name: String) {
        if (!editable()) return
        _ui.update {
            val invalid = it.form.nameInvalid && !Names.isValidSessionName(name)
            it.copy(form = it.form.copy(name = name, nameInvalid = invalid))
        }
    }

    /** Ignored unless Idle, so a second tap can never start a second session. */
    fun start() {
        val ui = _ui.value
        val target = ui.target ?: return
        if (ui.state !is StartState.Idle) return
        if (!Names.isValidSessionName(ui.form.name)) {
            _ui.update { it.copy(form = it.form.copy(nameInvalid = true, nameRefusals = it.form.nameRefusals + 1)) }
            return
        }
        _ui.update { it.copy(state = StartState.AwaitingFingerprint) }
        launchJob {
            val handoff = ui.form.resume?.takeIf { ui.form.handoff }
            val resume = ui.form.resume?.takeIf { !ui.form.handoff }
            val mode = if (resume != null) SpawnMode.SameDir else ui.form.mode
            val name = ui.form.name.trim(' ')
            val body = RemoterJson.encodeToString(
                SpawnRequest.serializer(),
                SpawnRequest(target.path, name, mode, resume = resume?.id, handoff = handoff?.id),
            ).toByteArray()
            val where = displayPath(target.path)
            startedHandoff = handoff != null
            val prompt = PromptCopy(
                when {
                    handoff != null -> "Start $name in $where with a handoff on $host"
                    resume != null -> "Resume $name in $where on $host"
                    else -> "Start session in $where on $host"
                },
            )
            when (val r = signer.sign("POST", Paths.sessions, body, prompt)) {
                SignResult.Cancelled -> _ui.update { it.copy(state = StartState.Idle) }
                SignResult.LockedOut -> fail(AppError.FingerprintLockedOut, null)
                SignResult.KeyInvalidated -> fail(AppError.KeyInvalidated, null)
                is SignResult.Ok -> {
                    signed = r.signed
                    send(r.signed)
                }
            }
        }
    }

    /**
     * The picked conversation is open on the laptop: the same pick, as a handoff instead, which only
     * reads it. A new request, so a new fingerprint.
     */
    fun handoffInstead() {
        val s = _ui.value.state
        if (s !is StartState.NotAccepted || s.error != AppError.ConversationOpen || _ui.value.form.resume == null) return
        setHandoff(true)
        start()
    }

    /** Folder busy: the same start again as a worktree. A new request, so a new fingerprint. */
    fun startInWorktree() {
        val form = _ui.value.form
        val s = _ui.value.state
        val busy = s is StartState.NotAccepted || (s is StartState.Stuck && s.reason == StuckReason.FolderBusy)
        if (!busy || _ui.value.target?.isGit != true || (form.resume != null && !form.handoff)) return
        cancelJobs()
        signed = null
        _ui.update { it.copy(state = StartState.Idle, retryLeftS = null, form = it.form.copy(mode = SpawnMode.Worktree)) }
        start()
    }

    /** Resends the same signed bytes inside 30 s; after that it needs a new fingerprint. */
    fun retry() {
        // Retry clears the jobs, and the kill in flight is one of them.
        if (_ui.value.ending) return
        val s = _ui.value.state
        val bytes = signed
        when {
            s is StartState.NotAccepted && s.retryUntilMs != null && bytes != null && clock.nowMs() < s.retryUntilMs ->
                launchJob { send(bytes) }
            s is StartState.NotAccepted || s is StartState.Stuck || s is StartState.Exited -> {
                cancelJobs()
                signed = null
                _ui.update { it.copy(state = StartState.Idle, retryLeftS = null) }
                start()
            }
        }
    }

    private suspend fun send(bytes: Signed) {
        val until = bytes.timestampMs + RETRY_WINDOW_MS
        _ui.update { it.copy(state = StartState.Sending(until)) }
        try {
            val resp = api.spawn(bytes)
            startFollowing(SessionId(resp.id), resp.viewToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = e.toAppError(clock.nowMs())
            // Only an unanswered request is safe to resend: the server never said no.
            fail(err, if (err is AppError.Unreachable || err is AppError.AgentDown) until else null)
        }
    }

    private fun fail(error: AppError, retryUntilMs: Long?) {
        _ui.update { it.copy(state = StartState.NotAccepted(error, retryUntilMs)) }
        if (retryUntilMs != null) {
            launchJob {
                while (true) {
                    val left = ((retryUntilMs - clock.nowMs() + 999) / 1000).toInt()
                    val s = _ui.value.state
                    if (s !is StartState.NotAccepted || s.retryUntilMs != retryUntilMs) return@launchJob
                    if (left <= 0) {
                        _ui.update { it.copy(retryLeftS = null, state = StartState.NotAccepted(error, null)) }
                        return@launchJob
                    }
                    _ui.update { it.copy(retryLeftS = left) }
                    delay(1000)
                }
            }
        } else {
            _ui.update { it.copy(retryLeftS = null) }
        }
    }

    private var startedHandoff = false
    /** Where "Taking longer than usual" counts from. A handoff alone takes 15 to 60 s, so after one it counts from there. */
    private var slowFromMs: Long? = null

    /** The stepper's phases in order, `null` standing for the fingerprint, already done. */
    private val phaseOrder get() =
        listOfNotNull(null to "Fingerprint", Phase.Accepted to "Accepted by $host", Phase.Terminal to "Opening the terminal") +
            listOfNotNull((Phase.Handoff to "Writing the handoff").takeIf { startedHandoff }) +
            listOf(Phase.Claude to "Launching Claude", Phase.RemoteControl to "Connecting Remote Control")

    private fun startFollowing(id: SessionId, token: String) {
        tail.clear()
        val since = clock.nowMs()
        slowFromMs = if (startedHandoff) null else since
        val steps = phaseOrder.mapIndexed { i, (_, l) -> Step(l, i == 0) }.toImmutableList()
        _ui.update { it.copy(state = StartState.Starting(id, steps, since, streamReconnecting = false, slow = false), retryLeftS = null, elapsedS = 0) }
        launchJob {
            while (_ui.value.state is StartState.Starting) {
                delay(1000)
                val s = _ui.value.state as? StartState.Starting ?: break
                val now = clock.nowMs()
                val el = ((now - s.sinceMs) / 1000).toInt()
                val slow = slowFromMs?.let { now - it >= SLOW_MS } == true
                _ui.update { it.copy(elapsedS = el, state = if (slow && !s.slow) s.copy(slow = true) else s) }
            }
        }
        launchJob { follow(id, token) }
    }

    /**
     * A timeout only means no sign of life yet: claude once took 31 s to connect while the sheet
     * had given up at 20 s and missed the Ready. Real failures carry a reason and end it.
     */
    private fun watching(): Boolean = when (val s = _ui.value.state) {
        is StartState.Starting -> true
        is StartState.Stuck -> s.reason == null || s.reason == StuckReason.Timeout
        else -> false
    }

    private suspend fun follow(id: SessionId, token: String) {
        var last: String? = null
        while (true) {
            try {
                api.events(id.value, token, last).collect { ev ->
                    last = ev.id ?: last
                    reconnecting(false)
                    apply(id, ev.event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // lost stream isn't a failure. hold the stepper, poll
            }
            if (!watching()) return
            reconnecting(true)
            delay(POLL_MS)
            try {
                val d = api.session(id.value, token)
                tail = d.tail.toMutableList()
                when (d.session.state) {
                    SessionState.Ready, SessionState.Stuck, SessionState.Exited ->
                        apply(id, Event.StateEvent(d.session.state, d.session.reason, d.session.exitCode))
                    else -> {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            if (!watching()) return
        }
    }

    private fun reconnecting(on: Boolean) = _ui.update {
        val s = it.state as? StartState.Starting ?: return@update it
        if (s.streamReconnecting == on) it else it.copy(state = s.copy(streamReconnecting = on))
    }

    private suspend fun apply(id: SessionId, e: Event) {
        when (e) {
            is Event.PhaseEvent -> _ui.update {
                val s = it.state as? StartState.Starting ?: return@update it
                val idx = phaseOrder.indexOfFirst { (p, _) -> p == e.step }
                if (idx < 0) return@update it
                if (startedHandoff && slowFromMs == null && e.step in HANDOFF_AND_AFTER) slowFromMs = clock.nowMs()
                it.copy(state = s.copy(steps = s.steps.mapIndexed { i, st -> if (i <= idx) st.copy(done = true) else st }.toImmutableList()))
            }
            is Event.TailEvent -> tail = e.lines.toMutableList()
            is Event.StateEvent -> {
                val since = (_ui.value.state as? StartState.Starting)?.sinceMs
                val name = _ui.value.form.name.trim(' ')
                val next = when (e.state) {
                    SessionState.Ready -> {
                        val link = runCatching { api.sessions().sessions.firstOrNull { it.id == id.value }?.claude }.getOrNull()
                        if (since != null) {
                            val took = (clock.nowMs() - since).toInt()
                            store.update { st -> st.copy(startTimesMs = (st.startTimesMs + took).takeLast(9)) }
                        }
                        StartState.Ready(id, name, link)
                    }
                    SessionState.Stuck -> StartState.Stuck(id, e.reason, tail.takeLast(40).toImmutableList())
                    SessionState.Exited -> StartState.Exited(id, e.exitCode, tail.takeLast(40).toImmutableList())
                    else -> return
                }
                _ui.update { it.copy(state = next, readySnack = if (next is StartState.Ready && !it.open) next else it.readySnack) }
            }
        }
    }

    fun snackShown() = _ui.update { it.copy(readySnack = null) }

    fun done() {
        cancelJobs()
        pastJob?.cancel()
        signed = null
        _ui.update { StartUi(hostname = it.hostname) }
    }

    /**
     * End it, or Cancel start while starting. The fingerprint prompt is the confirmation. A failed
     * kill keeps the sheet open with the reason, the session is probably still running.
     */
    fun endIt() {
        val ui = _ui.value
        if (ui.ending) return
        val id = when (val s = ui.state) {
            is StartState.Starting -> s.id
            is StartState.Stuck -> s.id
            is StartState.Exited -> s.id
            else -> return
        }
        _ui.update { it.copy(ending = true, endError = null) }
        launchJob {
            val prompt = PromptCopy("End ${_ui.value.form.name.trim(' ')} on $host")
            when (val r = signer.sign("DELETE", Paths.session(id.value), ByteArray(0), prompt)) {
                SignResult.Cancelled -> _ui.update { it.copy(ending = false) }
                SignResult.LockedOut -> _ui.update { it.copy(ending = false, endError = AppError.FingerprintLockedOut) }
                SignResult.KeyInvalidated -> _ui.update { it.copy(ending = false, endError = AppError.KeyInvalidated) }
                is SignResult.Ok -> try {
                    api.kill(r.signed)
                    done()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _ui.update { it.copy(ending = false, endError = e.toAppError(clock.nowMs())) }
                }
            }
        }
    }

    private fun launchJob(block: suspend () -> Unit) {
        jobs += viewModelScope.launch { block() }
    }

    private fun cancelJobs() {
        jobs.forEach { it.cancel() }
        jobs = mutableListOf()
    }

    companion object {
        const val RETRY_WINDOW_MS = 30_000L
        const val POLL_MS = 2_000L
        const val SLOW_MS = 10_000L
        private val HANDOFF_AND_AFTER = setOf(Phase.Handoff, Phase.Claude, Phase.RemoteControl)
    }
}
