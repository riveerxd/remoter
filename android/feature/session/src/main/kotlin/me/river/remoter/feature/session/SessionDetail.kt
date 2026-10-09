package me.river.remoter.feature.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.river.remoter.core.design.Appear
import me.river.remoter.core.design.Dur
import me.river.remoter.core.design.EaseIn
import me.river.remoter.core.design.EaseOut
import me.river.remoter.core.design.FadeSwap
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.Press
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.arrive
import me.river.remoter.core.design.components.BackButton
import me.river.remoter.core.design.components.ClaudeButton
import me.river.remoter.core.design.components.PrimaryButton
import me.river.remoter.core.design.components.QuietButton
import me.river.remoter.core.design.components.RemoterSheet
import me.river.remoter.core.design.components.RoundIconButton
import me.river.remoter.core.design.components.Skeleton
import me.river.remoter.core.design.components.StatusLabel
import me.river.remoter.core.design.components.StatusPill
import me.river.remoter.core.design.components.StatusTone
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.sharedContainer
import me.river.remoter.core.design.tnum
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ClaudeLink
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.Event
import me.river.remoter.core.net.Link
import me.river.remoter.core.net.LocalStore
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.SessionSummary
import me.river.remoter.core.net.displayPath
import me.river.remoter.core.net.toAppError
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

// which request an error came from, so Retry repeats it
enum class DetailAction { End }

data class DetailUi(
    val session: SessionSummary? = null,
    val tail: ImmutableList<String> = persistentListOf(),
    val tailAtMs: Long? = null,
    val nowMs: Long = 0,
    val hostname: String = "the laptop",
    val ending: Boolean = false,
    val terminalSp: Float = 12f,
    val error: AppError? = null,
    val failed: DetailAction? = null,
    val gone: Boolean = false,
    val unreachable: Boolean = false,
    val live: Boolean = false,
)

@HiltViewModel(assistedFactory = SessionDetailViewModel.Factory::class)
class SessionDetailViewModel @AssistedInject constructor(
    @Assisted private val id: String,
    private val api: RemoterApi,
    private val hub: SessionsHub,
    private val clock: Clock,
    private val monitor: ConnectionMonitor,
    private val store: LocalStore,
    private val live: LiveSync,
) : ViewModel() {
    @AssistedFactory interface Factory {
        fun create(id: String): SessionDetailViewModel
    }

    private val _ui = MutableStateFlow(DetailUi(terminalSp = store.state.value?.prefs?.terminalSp ?: 12f))
    val ui: StateFlow<DetailUi> = _ui.asStateFlow()
    private val _goHome = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val goHome: SharedFlow<Unit> = _goHome

    private val host get() = monitor.health.value?.hostname ?: store.state.value?.laptop?.hostname ?: "the laptop"

    // only older laptops need it, current ones read with mTLS alone
    private val token = MutableStateFlow<String?>(hub.viewToken() ?: "")
    private val streamUp = MutableStateFlow(false)

    // laptop clock. a slow poll must never put an older screen back
    private var shownTailAt = Long.MIN_VALUE

    init {
        viewModelScope.launch {
            hub.sessions.filterNotNull().collect { list -> listed(Result.success(list)) }
        }
        // the live stream keeps the hub's list current, this asks only while it's down
        viewModelScope.launch {
            live.connected.collectLatest { up ->
                if (up) return@collectLatest
                while (true) {
                    hub.refresh().onFailure { listed(Result.failure(it)) }
                    delay(LIST_POLL_MS)
                }
            }
        }
        viewModelScope.launch {
            combine(streamUp, monitor.link) { s, l -> s && l is Link.Up }.distinctUntilChanged().collect { v ->
                _ui.update { it.copy(live = v) }
            }
        }
        viewModelScope.launch {
            while (true) {
                token.value = hub.viewToken() ?: ""
                _ui.update { it.copy(nowMs = clock.nowMs()) }
                delay(1_000)
            }
        }
        viewModelScope.launch {
            combine(token, _ui.map { it.gone }) { t, g -> t to g }.distinctUntilChanged().collectLatest { (t, gone) ->
                if (gone) {
                    streamUp.value = false
                    return@collectLatest
                }
                follow(t)
            }
        }
    }

    private fun listed(r: Result<List<SessionSummary>>) {
        val listed = r.getOrNull()?.firstOrNull { s -> s.id == id }
        _ui.update {
            val gone = r.isSuccess && listed == null
            it.copy(
                session = listed ?: it.session?.let { s -> if (gone) s.copy(state = SessionState.Gone) else s },
                gone = gone,
                unreachable = r.isFailure && it.session == null,
                hostname = host,
            )
        }
    }

    private suspend fun follow(t: String?) = coroutineScope {
        // the stream only sends the screen when it changes, so an idle one would never show
        if (t != null) launch { fetch(t) }
        var attempt = 0
        var last: String? = null
        var fallback: Job? = null
        while (true) {
            var got = false
            try {
                api.events(id, t, last).collect { ev ->
                    if (!got) {
                        got = true
                        attempt = 0
                        fallback?.cancel()
                        fallback = null
                        streamUp.value = t != null
                    }
                    last = ev.id ?: last
                    when (val e = ev.event) {
                        is Event.TailEvent -> if (t != null) tail(e.lines, e.at)
                        is Event.StateEvent -> _ui.update {
                            val s = it.session ?: return@update it
                            if (it.gone) it else it.copy(session = s.copy(state = e.state, reason = e.reason, exitCode = e.exitCode))
                        }
                        is Event.PhaseEvent -> {}
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            // what shows was the screen until now, from here its age counts up
            if (streamUp.value) _ui.update { if (it.tail.isEmpty()) it else it.copy(tailAtMs = clock.nowMs()) }
            streamUp.value = false
            if (t != null && fallback == null) {
                fallback = launch {
                    while (true) {
                        fetch(t)
                        delay(1_000)
                    }
                }
            }
            delay(LiveSync.BACKOFF_MS[attempt.coerceAtMost(LiveSync.BACKOFF_MS.size - 1)])
            attempt++
        }
    }

    private suspend fun fetch(t: String) {
        runCatching { api.session(id, t) }.onSuccess { d ->
            _ui.update { if (it.gone) it else it.copy(session = d.session) }
            tail(d.tail, d.tailAt)
        }
    }

    private fun tail(lines: List<String>, atLaptopMs: Long) {
        if (atLaptopMs < shownTailAt) return
        shownTailAt = atLaptopMs
        // tail_at is the laptop's clock. one ran 11 s slow and the age flickered between 9 and 10 s
        // on every poll, so shift it by the skew health measures
        val skew = monitor.account.value?.clockSkewMs ?: 0
        _ui.update {
            if (it.gone || token.value == null) {
                it
            } else {
                val same = it.tail == lines
                it.copy(tail = if (same) it.tail else lines.toImmutableList(), tailAtMs = atLaptopMs - skew)
            }
        }
    }

    // ending shows from the tap on so End can't fire a second prompt and DELETE
    fun end() {
        val s = _ui.value.session ?: return
        if (_ui.value.ending) return
        _ui.update { it.copy(ending = true, error = null) }
        viewModelScope.launch {
            // home on any sent End. checking it still read as ending lost the race with a quick
            // laptop, whose gone arrived first, and left a dead detail screen
            when (val o = hub.end(s, host)) {
                EndOutcome.Sent -> _goHome.tryEmit(Unit)
                EndOutcome.Cancelled -> _ui.update { it.copy(ending = false) }
                is EndOutcome.Failed -> _ui.update { it.copy(ending = false, error = o.error, failed = DetailAction.End) }
            }
        }
    }

    fun zoom(sp: Float) {
        val v = sp.coerceIn(MIN_SP, MAX_SP)
        _ui.update { it.copy(terminalSp = v) }
        viewModelScope.launch { store.update { st -> st.copy(prefs = st.prefs.copy(terminalSp = v)) } }
    }

    fun clearError() = _ui.update { it.copy(error = null, failed = null) }
}

internal const val LIST_POLL_MS = 5_000L

internal const val MIN_SP = 9f
internal const val MAX_SP = 14f

// past a day "48:27:00" is a puzzle
fun uptime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    if (s >= 86_400) return "${s / 86_400}d ${(s / 3600) % 24}h"
    return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}

// for TalkBack: "1 hour 23 minutes", not "1:23:07"
fun spokenDuration(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    val h = m / 60
    val mm = m % 60
    fun unit(n: Long, one: String) = "$n $one" + if (n == 1L) "" else "s"
    return when {
        h > 0 && mm > 0 -> unit(h, "hour") + " " + unit(mm, "minute")
        h > 0 -> unit(h, "hour")
        mm > 0 -> unit(mm, "minute")
        else -> "under a minute"
    }
}

fun clockTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
    .let { "%02d:%02d".format(it.hour, it.minute) }

// actions stay in the bottom third, End session last and 48 dp under the rest
@Composable
fun SessionDetailContent(
    ui: DetailUi,
    onBack: () -> Unit,
    onOpenClaude: (ClaudeLink?) -> Unit,
    onEnd: () -> Unit,
    onZoom: (Float) -> Unit,
    errors: ErrorActions = ErrorActions(),
    onDismissError: () -> Unit = {},
) {
    val c = Remoter.colors
    val t = Remoter.type
    val s = ui.session
    val over = s != null && (s.state == SessionState.Exited || s.state == SessionState.Gone)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .sharedContainer("session-${s?.id}", onlyOnEnter = true),
        ) {
            BoxWithConstraints(Modifier.weight(1f)) {
            // past a tall header (large fonts) the terminal keeps its floor and the page scrolls
            val terminalMin = (maxHeight - DETAIL_HEADER).coerceAtLeast(200.dp)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter),
                verticalArrangement = Arrangement.spacedBy(Space.s16),
            ) {
                BackButton(onBack, Modifier.padding(top = Space.s8))
                FadeSwap(Triple(s, ui.gone, ui), key = { (sess, gone, _) -> if (sess != null) "content" else if (gone) "gone" else "skeleton" }) { (s, _, ui) ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                when {
                    s != null -> {
                        val (word, tone) = s.statusWord(ui.ending)
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.name, style = t.title, color = c.text, modifier = Modifier.weight(1f))
                                StatusPill(word, tone, pulsing = s.state == SessionState.Starting)
                            }
                            Text(displayPath(s.path), style = t.label, color = c.textMuted)
                            s.worktree?.let { WorktreeLine(it) }
                            Text("Started ${clockTime(s.started)} · ${uptime(ui.nowMs - s.started)}", style = t.label.tnum(), color = c.textMuted)
                            Appear(!over) { Text("Running on ${ui.hostname}, workspace 9", style = t.label, color = c.textMuted) }
                        }
                        // an exited session's last lines say why it stopped
                        if (s.state != SessionState.Gone) Terminal(ui, onZoom, terminalMin)
                    }
                    ui.gone -> Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                        Text("This session is gone", style = t.title, color = c.text)
                        Text("${ui.hostname} doesn't list it anymore. It ended, or its window was closed.", style = t.body, color = c.textMuted)
                    }
                    else -> DetailSkeleton(ui)
                }
                }
                }
            }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                val mode = when {
                    s == null && ui.gone -> "back"
                    s == null -> "none"
                    over -> "done"
                    else -> "live"
                }
                FadeSwap(mode) { m ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                when {
                    m == "back" -> PrimaryButton("Back", onBack)
                    m == "none" || s == null -> {}
                    m == "done" -> PrimaryButton("Done", onBack)
                    else -> {
                        ClaudeButton("Open in Claude", { onOpenClaude(s.claude) })
                        Spacer(Modifier.height(Space.s48))
                        // same 48 dp slot either way, so nothing jumps
                        Crossfade(ui.ending, Modifier.align(Alignment.CenterHorizontally), animationSpec = tween(Dur.base, easing = EaseOut), label = "end") { ending ->
                            if (ending) {
                                Row(
                                    Modifier.heightIn(min = Touch.min).semantics(mergeDescendants = true) { stateDescription = "Ending" },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(Modifier.size(16.dp), color = c.danger, strokeWidth = 2.dp, trackColor = Color.Transparent)
                                    Spacer(Modifier.width(Space.s8))
                                    Text("Ending\u2026", style = t.bodyStrong, color = c.danger)
                                }
                            } else {
                                QuietButton("End session", onEnd, danger = true)
                            }
                        }
                    }
                }
                }
                }
            }
        }
        // held so the sheet keeps its words while it slides away
        var shown by remember { mutableStateOf(ui.error) }
        if (ui.error != null) shown = ui.error
        RemoterSheet(visible = ui.error != null, onDismiss = onDismissError) {
            shown?.let { e ->
                val retry = when (ui.failed) {
                    DetailAction.End -> onEnd
                    null -> null
                }
                ErrorContent(e, ui.hostname, errors.copy(onRetry = retry?.let { r -> { onDismissError(); r() } }, onDismiss = onDismissError))
            }
        }
    }
}

// back button, title block, gaps and the age row, at 100%
private val DETAIL_HEADER = 290.dp

@Composable
private fun DetailSkeleton(ui: DetailUi) {
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "Loading session" }, verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        Skeleton(180.dp, 28.dp)
        Skeleton(140.dp, 16.dp)
        Skeleton(200.dp, 16.dp)
    }
    Skeleton(null, 160.dp, shape = Shapes.technical)
    if (ui.unreachable) Text("Can't reach ${ui.hostname} right now. Still trying.", style = Remoter.type.label, color = Remoter.colors.textMuted)
}

@Composable
private fun Terminal(ui: DetailUi, onZoom: (Float) -> Unit, minHeight: Dp) {
    val c = Remoter.colors
    val t = Remoter.type
    // the first capture lands up to a second after opening
    FadeSwap(ui.tailAtMs != null || ui.tail.isNotEmpty()) { loaded ->
        Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            if (loaded) TerminalBody(ui, onZoom, minHeight)
            else Skeleton(null, minHeight, Modifier.semantics { contentDescription = "Loading terminal output" }, shape = Shapes.technical)
        }
    }
}

@Composable
private fun TerminalBody(ui: DetailUi, onZoom: (Float) -> Unit, minHeight: Dp) {
    val c = Remoter.colors
    val t = Remoter.type
    val sp by rememberUpdatedState(ui.terminalSp)
    val zoom by rememberUpdatedState(onZoom)
    val v = rememberScrollState()
    val scope = rememberCoroutineScope()
    val atBottom = v.value >= v.maxValue - 4
    // stick to the bottom unless scrolled up
    LaunchedEffect(ui.tail) { if (atBottom) v.scrollTo(v.maxValue) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .height(minHeight)
                .clip(Shapes.technical)
                .background(c.terminal)
                .pointerInput(Unit) { detectTransformGestures { _, _, factor, _ -> zoom((sp * factor).coerceIn(MIN_SP, MAX_SP)) } }
                .verticalScroll(v)
                .horizontalScroll(rememberScrollState())
                .padding(Space.cardPadding)
                .semantics { contentDescription = "Terminal output" }
                .testTag("terminal"),
        ) {
            val shownSp by animateFloatAsState(ui.terminalSp, if (Remoter.reducedMotion) tween(0) else arrive(), label = "text size")
            val style = t.mono.copy(fontSize = shownSp.sp, lineHeight = (shownSp * 1.5f).sp)
            ui.tail.forEach { Text(it, style = style, color = c.onTerminal, softWrap = false, maxLines = 1) }
        }
        AnimatedVisibility(
            !atBottom,
            Modifier.align(Alignment.BottomCenter).padding(Space.s8),
            enter = fadeIn(tween(Dur.base, easing = EaseOut)),
            exit = fadeOut(tween(Dur.exit, easing = EaseIn)),
        ) {
            Box(
                Modifier
                    .heightIn(min = Touch.min)
                    .clip(Shapes.pill)
                    .background(c.cta)
                    .clickable(remember { MutableInteractionSource() }, pressIndication(Press.Button), role = Role.Button) { scope.launch { v.animateScrollTo(v.maxValue) } }
                    .padding(horizontal = Space.s16),
                contentAlignment = Alignment.Center,
            ) {
                Text("Jump to latest", style = t.label, color = c.onCta)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val age = ui.ageLabel()
        Box(Modifier.weight(1f)) {
            FadeSwap(age == "Live") { live ->
                if (live) StatusLabel("Live", StatusTone.Live, pulsing = true) else Text(age, style = t.label.tnum(), color = c.textMuted)
            }
        }
        // pinch works too, but nothing on screen says so
        Row(Modifier.clip(Shapes.pill).border(1.dp, c.line, Shapes.pill), verticalAlignment = Alignment.CenterVertically) {
            ZoomButton(13.sp, "Smaller text") { onZoom((ui.terminalSp.roundToInt() - 1f).coerceIn(MIN_SP, MAX_SP)) }
            Box(Modifier.width(1.dp).height(24.dp).background(c.line))
            ZoomButton(19.sp, "Larger text") { onZoom((ui.terminalSp.roundToInt() + 1f).coerceIn(MIN_SP, MAX_SP)) }
        }
    }
}

/** small A, big A. what every reader app uses */
@Composable
private fun ZoomButton(size: TextUnit, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Touch.min)
            .clickable(remember { MutableInteractionSource() }, pressIndication(Press.Icon), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text("A", style = Remoter.type.bodyStrong.copy(fontSize = size), color = Remoter.colors.text)
    }
}

@Composable
fun WorktreeLine(name: String, modifier: Modifier = Modifier) {
    // two lines: at large fonts one line ellipsized away the name
    Text("worktree · $name", style = Remoter.type.label, color = Remoter.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

fun SessionSummary.statusWord(ending: Boolean): Pair<String, StatusTone> = if (ending) {
    "Ending…" to StatusTone.Muted
} else {
    when (state) {
        SessionState.Starting -> "Starting" to StatusTone.Warn
        SessionState.Ready -> "Ready" to StatusTone.Ready
        SessionState.Stuck -> "Stuck" to StatusTone.Danger
        SessionState.Exited -> ("Exited" + (exitCode?.let { " · code $it" } ?: "")) to StatusTone.Muted
        SessionState.Ending -> "Ending…" to StatusTone.Muted
        SessionState.Gone -> "Gone" to StatusTone.Muted
    }
}

// an idle screen sends nothing for minutes, and while the stream is up that's still the screen
internal fun DetailUi.ageLabel(): String = tailAtMs?.let { if (live) "Live" else updatedAgo(((nowMs - it) / 1000).coerceAtLeast(0)) } ?: ""

// a stalled stream once showed "166472 s ago"
internal fun updatedAgo(seconds: Long): String = when {
    // captures land every second, a 0, 1, 0, 1 counter is just noise
    seconds < 3 -> "Live"
    seconds < 60 -> "Updated $seconds s ago"
    seconds < 3600 -> "Updated ${seconds / 60} min ago"
    else -> "Updated ${seconds / 3600} h ago"
}
