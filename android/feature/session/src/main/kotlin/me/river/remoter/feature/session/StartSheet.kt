package me.river.remoter.feature.session

import me.river.remoter.core.design.components.ClaudeButton
import me.river.remoter.core.design.components.ClaudeCrab
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import me.river.remoter.core.design.components.StatusTone
import me.river.remoter.core.design.components.StatusLabel
import me.river.remoter.core.design.components.StatusDot
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.river.remoter.core.design.Dur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateDpAsState
import me.river.remoter.core.design.arrive
import me.river.remoter.core.design.Press
import me.river.remoter.core.design.pressable
import me.river.remoter.core.design.animatedAlpha
import me.river.remoter.core.design.animatedTone
import me.river.remoter.core.design.FadeSwap
import me.river.remoter.core.design.FadeInPlace
import me.river.remoter.core.design.Appear
import me.river.remoter.core.design.EaseIn
import me.river.remoter.core.design.EaseOut
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.PressSpring
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.components.Hop
import me.river.remoter.core.design.components.PrimaryButton
import me.river.remoter.core.design.components.QuietButton
import me.river.remoter.core.design.components.RouteMap
import me.river.remoter.core.design.components.RouteNode
import me.river.remoter.core.design.components.SecondaryButton
import me.river.remoter.core.design.components.TerminalCard
import me.river.remoter.core.design.rememberHaptics
import me.river.remoter.core.design.shake
import me.river.remoter.core.design.tnum
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ClaudeLink
import me.river.remoter.core.net.Conversation
import me.river.remoter.core.net.SpawnMode
import me.river.remoter.core.net.StuckReason
import me.river.remoter.core.net.displayPath

data class StartCallbacks(
    val onMode: (SpawnMode) -> Unit = {},
    val onName: (String) -> Unit = {},
    val onStart: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onDone: () -> Unit = {},
    /** End it from Stuck or Exited, and Cancel start while starting: both kill the session. */
    val onEndIt: () -> Unit = {},
    /** A null link still opens the Claude app, through the launcher rung. */
    val onOpenClaude: (ClaudeLink?) -> Unit = {},
    val onUseWorktree: () -> Unit = {},
    val onPickPast: (Conversation) -> Unit = {},
    val onRetryPast: () -> Unit = {},
    val onMorePast: () -> Unit = {},
    /** Continue it (false) or Fresh with handoff (true). */
    val onPastChoice: (Boolean) -> Unit = {},
    val onHandoffInstead: () -> Unit = {},
    val errors: ErrorActions = ErrorActions(),
)

@Composable
fun StartSheet(ui: StartUi, cb: StartCallbacks) {
    RemoterSheet(visible = ui.open, onDismiss = cb.onClose) {
        StartContent(ui, cb)
    }
}

/** The sheet body, one layout per [StartState]. Shared with the screenshot tests. */
@Composable
fun StartContent(ui: StartUi, cb: StartCallbacks) {
    val s = ui.state
    val haptics = rememberHaptics()
    // A refusal is REJECT; an unanswered request isn't, the laptop never said no.
    LaunchedEffect(s) { if (s is StartState.NotAccepted && s.error.isRefusal()) haptics.reject() }
    // Draw each layout from the state the transition carries, not the current one. An error layout
    // fading out used to cast the new state to NotAccepted and every way off an error screen crashed.
    AnimatedContent(
        targetState = s,
        contentKey = ::layoutOf,
        transitionSpec = {
            fadeIn(tween(Dur.base, delayMillis = Dur.exit / 2, easing = EaseOut)) togetherWith fadeOut(tween(Dur.exit, easing = EaseIn))
        },
        label = "start",
    ) { shown ->
        when {
            layoutOf(shown) == "form" -> Form(ui, cb)
            shown is StartState.NotAccepted -> ErrorContent(
                shown.error, ui.hostname,
                cb.errors.copy(
                    onRetry = cb.onRetry, onDismiss = cb.onClose,
                    onUseWorktree = if (ui.target?.isGit == true && !ui.form.resumesAsIs) cb.onUseWorktree else null,
                    onHandoffInstead = cb.onHandoffInstead,
                ),
            )
            else -> Run(ui, cb)
        }
    }
}

/** Form states share one layout, so the button springs back in place instead of the form jumping. */
internal fun layoutOf(s: StartState): String = when (s) {
    StartState.Idle, StartState.AwaitingFingerprint, is StartState.Sending -> "form"
    is StartState.NotAccepted -> if (s.error.isNetwork()) "form" else "error"
    is StartState.Starting, is StartState.Ready, is StartState.Stuck, is StartState.Exited -> "run"
}

private fun AppError.isRefusal() = this == AppError.Locked || this is AppError.Security || this is AppError.SessionCap || this is AppError.FolderBusy || this == AppError.ConversationOpen ||
    this == AppError.FingerprintLockedOut || this == AppError.ReattestFailed || this == AppError.DeviceUnknown || this == AppError.KeyInvalidated

private fun AppError.isNetwork() = this == AppError.Unreachable || this == AppError.AgentDown || this == AppError.VpnOff || this == AppError.LaptopDown

@Composable
private fun Form(ui: StartUi, cb: StartCallbacks) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val start = { focus.clearFocus(); cb.onStart() }
    val c = Remoter.colors
    val t = Remoter.type
    val target = ui.target ?: return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(target.folder, style = t.title, color = c.text, modifier = Modifier.weight(1f, fill = false))
                if (target.isGit) {
                    Spacer(Modifier.width(Space.s8))
                    Text("git", style = t.label, color = c.textMuted, modifier = Modifier.clip(Shapes.pill).background(c.surface).padding(horizontal = Space.s8, vertical = 2.dp))
                }
            }
            Text(displayPath(target.path), style = t.label, color = c.textMuted)
        }
        // The typical time is measured across both modes, so it sits under the button, not on one card.
        ModeCard(
            title = "Same folder",
            body = "Works right in this folder.",
            selected = ui.form.mode == SpawnMode.SameDir && !ui.form.resumesAsIs,
            onClick = { cb.onMode(SpawnMode.SameDir) },
        )
        // Hidden, not disabled, outside git repos: there is nothing to explain. It stays while a past
        // conversation is picked, since hiding it moved the list out from under the finger that picked.
        if (target.isGit) {
            ModeCard(
                title = "Worktree",
                body = "Its own git worktree, so parallel work stays apart.",
                selected = ui.form.mode == SpawnMode.Worktree && !ui.form.resumesAsIs,
                onClick = { cb.onMode(SpawnMode.Worktree) },
            )
        }
        Earlier(ui, cb)
        NameField(ui.form.name, ui.form.nameInvalid, ui.form.nameRefusals, cb.onName, start)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyphs.shield, null, tint = c.textMuted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Space.s8))
            Text("Bypass permissions · it can run anything as you", style = t.label, color = c.textMuted)
        }
        val s = ui.state
        val label = when {
            s is StartState.NotAccepted && ui.retryLeftS != null -> "Retry now"
            s is StartState.NotAccepted -> "Retry with fingerprint"
            ui.form.resumesAsIs -> "Resume session"
            ui.form.resume != null -> "Start with handoff"
            else -> "Start session"
        }
        PrimaryButton(
            label,
            onClick = if (s is StartState.NotAccepted) cb.onRetry else start,
            loading = s is StartState.Sending,
        )
        // Held while it closes, so the words stay put as it shrinks away.
        var trouble by remember { mutableStateOf<AppError?>(null) }
        if (s is StartState.NotAccepted) trouble = s.error
        Appear(s is StartState.NotAccepted) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) { trouble?.let { NetworkTrouble(it, ui, cb) } }
        }
        Appear(s is StartState.Idle && ui.typicalStartS != null, Modifier.align(Alignment.CenterHorizontally)) {
            Text("Usually ready in about ${ui.typicalStartS ?: 0} s", style = t.label.tnum(), color = c.textMuted)
        }
    }
}

/**
 * A network failure keeps the form, so the fix has to live under the button:
 * what went wrong in warn, what to do about it, and how long the retry stays
 * free of a fingerprint.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.NetworkTrouble(error: AppError, ui: StartUi, cb: StartCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    val copy = error.copy(ui.hostname)
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Text(copy.title, style = t.bodyStrong, color = c.warn)
        copy.body?.let { Text(it, style = t.label, color = c.textMuted) }
        ui.retryLeftS?.let { Text("No fingerprint needed for $it s", style = t.label.tnum(), color = c.textMuted) }
    }
    copy.command?.let { CommandBlock(it) }
    if (error == AppError.VpnOff) {
        cb.errors.onOpenWireGuard?.let { SecondaryButton("Turn on WireGuard", it) }
    }
}

@Composable
private fun ModeCard(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val c = Remoter.colors
    val t = Remoter.type
    val interaction = remember { MutableInteractionSource() }
    val border = animatedTone(
        when {
            !selected -> c.line
            c.isDark -> c.volt
            else -> c.text
        },
    )
    val width by animateDpAsState(if (selected) 2.dp else 1.dp, arrive(), label = "card border")
    Box(
        Modifier
            .fillMaxWidth()
            .pressable(interaction, Press.Card)
            .clip(Shapes.card)
            .background(c.surface)
            .border(width, border, Shapes.card)
            .clickable(interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .padding(Space.cardPadding),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, style = t.bodyStrong, color = c.text)
                Text(body, style = t.label, color = c.textMuted)
            }
            FadeInPlace(selected) {
                Box(Modifier.size(20.dp).clip(Shapes.pill).background(c.volt), contentAlignment = Alignment.Center) {
                    Icon(Glyphs.check, "Selected", tint = c.onVolt, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

// 3 pushes Start below the fold on the S25 at 100%
private const val PAST_SHOWN = 2

/**
 * Conversations that ran in this folder before. Own heading and a history glyph per row so it
 * doesn't read as more mode options. Picking a row opens the pick up choice under it.
 */
@Composable
private fun Earlier(ui: StartUi, cb: StartCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    val past = ui.past
    if (past is PastState.Loading || past is PastState.Loaded && past.rows.isEmpty()) return
    Column(Modifier.padding(top = Space.s8), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Previous sessions", style = t.bodyStrong, color = c.text, modifier = Modifier.weight(1f, fill = false))
                if (past is PastState.Loaded) {
                    Spacer(Modifier.width(Space.s8))
                    Text("${past.rows.size}", style = t.label.tnum(), color = c.textMuted)
                }
            }
            // Stays after a pick: taking it away pulled the list, and the picked row, up under the finger.
            if (past is PastState.Loaded) {
                Text("Pick one to pick up where you left off", style = t.label, color = c.textMuted)
            }
        }
        when (past) {
            PastState.Failed -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Couldn't load previous sessions", style = t.label, color = c.textMuted, modifier = Modifier.weight(1f))
                QuietButton("Retry", cb.onRetryPast)
            }
            is PastState.Loaded -> {
                val rows = if (ui.pastExpanded) past.rows else past.rows.take(PAST_SHOWN)
                PastGroup {
                    past.rows.forEachIndexed { i, r ->
                        // The rest open below the first two when asked, instead of popping in at once.
                        Appear(i < PAST_SHOWN || ui.pastExpanded) {
                            Column {
                                if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 72.dp).height(1.dp).background(c.line))
                                val picked = ui.form.resume?.id == r.conversation.id
                                PastItem(r, picked, picked && ui.form.handoff, ui.hostname, cb.onPastChoice) { cb.onPickPast(r.conversation) }
                            }
                        }
                    }
                }
                val more = past.rows.size - rows.size
                Appear(more > 0, Modifier.align(Alignment.CenterHorizontally)) {
                    QuietButton("Show all ${past.rows.size} sessions", cb.onMorePast)
                }
            }
            PastState.Loading -> {}
        }
    }
}

@Composable
private fun PastGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val c = Remoter.colors
    Column(Modifier.fillMaxWidth().clip(Shapes.card).background(c.surface).border(1.dp, c.line, Shapes.card), content = content)
}

@Composable
private fun PastIcon(glyph: androidx.compose.ui.graphics.vector.ImageVector, live: Boolean = false) {
    val c = Remoter.colors
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).clip(Shapes.pill).background(c.bg), contentAlignment = Alignment.Center) {
            Icon(glyph, null, tint = c.text, modifier = Modifier.size(20.dp))
        }
        if (live) StatusDot(StatusTone.Live, Modifier.align(Alignment.TopEnd))
    }
}

/** A radio that is always there, so the rows read as pickable before anything is picked. */
@Composable
private fun PastRadio(selected: Boolean) {
    val c = Remoter.colors
    // The ring stays and the filled disc grows over it, on the press spring, like a radio settling.
    val fill by animateFloatAsState(if (selected) 1f else 0f, if (Remoter.reducedMotion) tween(0) else PressSpring, label = "radio")
    Box(Modifier.size(Touch.min), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).clip(Shapes.pill).border(2.dp, c.textMuted, Shapes.pill))
        Box(
            Modifier.size(22.dp).graphicsLayer { scaleX = fill; scaleY = fill; alpha = fill.coerceIn(0f, 1f) }.clip(Shapes.pill).background(c.volt),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyphs.check, null, tint = c.onVolt, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun PastItem(row: PastRow, selected: Boolean, handoff: Boolean, host: String, onChoice: (Boolean) -> Unit, onClick: () -> Unit) {
    val c = Remoter.colors
    val t = Remoter.type
    val conv = row.conversation
    val meta = listOfNotNull(row.whenLabel, conv.branch).joinToString(" · ")
    // A fill would flip in light mode, where the raised surface is the brighter one, so the pick is an
    // accent bar on the leading edge plus the filled radio.
    val accent = if (c.isDark) c.volt else c.text
    val bar = animatedAlpha(selected, "pick bar")
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { if (bar > 0f) drawRect(accent.copy(alpha = bar), size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(interaction, Press.Card)
                .clickable(interaction, indication = null, role = Role.RadioButton, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append("Previous session ${conv.title}, ${row.whenLabel}")
                        if (conv.open) append(", open on $host now")
                    }
                    this.selected = selected
                    stateDescription = if (selected) "Selected" else "Not selected"
                }
                .heightIn(min = Touch.row)
                .padding(start = Space.s16, end = Space.s4, top = Space.s16, bottom = Space.s16),
            verticalAlignment = Alignment.Top,
        ) {
            PastIcon(Glyphs.history, live = conv.open)
            Spacer(Modifier.width(Space.s16))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(conv.title, style = t.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta, style = t.label.tnum(), color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (conv.open) StatusLabel("Open on $host now", StatusTone.Live)
                conv.lastPrompt?.let {
                    Text("You: “$it”", style = t.label, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            PastRadio(selected)
        }
        Appear(selected) { PickUp(handoff, conv.open, host, onChoice) }
    }
}

/** How to pick a session up, under the row it belongs to. The row keeps everything it said. */
@Composable
private fun PickUp(handoff: Boolean, open: Boolean, host: String, onChoice: (Boolean) -> Unit) {
    val c = Remoter.colors
    val t = Remoter.type
    Column(Modifier.fillMaxWidth().padding(start = Space.s16, end = Space.s16, bottom = Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        Row(
            Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).clip(Shapes.card).border(1.dp, c.line, Shapes.card),
        ) {
            Segment("Continue", selected = !handoff, blocked = open, modifier = Modifier.weight(1f).fillMaxHeight()) { onChoice(false) }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
            Segment("Hand off", selected = handoff, blocked = false, modifier = Modifier.weight(1f).fillMaxHeight()) { onChoice(true) }
        }
        if (open) Text("Open on $host, so hand off only", style = t.label, color = c.textMuted)
    }
}

/**
 * One half of the pick up choice. Volt fills only under onVolt text, so it reads in light mode too.
 * A blocked one stays visible and says why underneath, it just doesn't take the tap.
 */
@Composable
private fun Segment(title: String, selected: Boolean, blocked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Remoter.colors
    val t = Remoter.type
    val fg = animatedTone(
        when {
            selected -> c.onVolt
            blocked -> c.textMuted
            else -> c.text
        },
    )
    val fill = animatedTone(if (selected) c.volt else c.volt.copy(alpha = 0f))
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .heightIn(min = Touch.min)
            .background(fill)
            .pressable(interaction, Press.Button)
            .clickable(interaction, indication = null, enabled = !blocked, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                stateDescription = when {
                    blocked -> "Not available, the session is open on the laptop"
                    selected -> "Selected"
                    else -> "Not selected"
                }
            }
            .padding(horizontal = Space.s8, vertical = Space.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = t.bodyStrong, color = fg, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun NameField(name: String, invalid: Boolean, refusals: Int, onName: (String) -> Unit, onSubmit: () -> Unit) {
    val c = Remoter.colors
    val t = Remoter.type
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    // Submit clears focus before the ViewModel answers, so a refusal has to pull it back.
    LaunchedEffect(refusals) { if (refusals > 0) focusRequester.requestFocus() }
    Column(Modifier.shake(refusals)) {
        Text("Name", style = t.label, color = c.textMuted)
        BasicTextField(
            name,
            onName,
            singleLine = true,
            textStyle = t.body.copy(color = c.text),
            cursorBrush = SolidColor(c.text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Touch.min)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .padding(vertical = Space.s8),
        )
        // A fixed 2 dp slot whose line thickens and changes color in it, so focus moves nothing.
        val line = animatedTone(if (invalid) c.danger else if (focused) c.text else c.line)
        val thick by animateDpAsState(if (focused || invalid) 2.dp else 1.dp, arrive(), label = "underline")
        Box(Modifier.fillMaxWidth().height(2.dp)) { Box(Modifier.fillMaxWidth().height(thick).align(Alignment.BottomStart).background(line)) }
        // Rules only while editing, so the form stays quiet otherwise. They open below the field the
        // finger is on, so nothing moves under it.
        Appear(invalid || focused) {
            FadeSwap(invalid) { bad ->
                if (bad) Text(sessionNameProblem(name) ?: "", style = t.label, color = c.danger)
                else Text("Letters, numbers, spaces, dots, dashes, underscores. Up to 48, no leading dash.", style = t.label, color = c.textMuted)
            }
        }
    }
}

private val nodes = persistentListOf(
    RouteNode(Glyphs.phone, "This phone"),
    RouteNode(Glyphs.relay, "Relay"),
    RouteNode(Glyphs.laptop, "Laptop"),
    RouteNode(ClaudeCrab, "Claude"),
)

private val directNodes = persistentListOf(
    RouteNode(Glyphs.phone, "This phone"),
    RouteNode(Glyphs.laptop, "Laptop"),
    RouteNode(ClaudeCrab, "Claude"),
)

private val directReadyNodes = persistentListOf(
    RouteNode(Glyphs.phone, "This phone"),
    RouteNode(Glyphs.laptop, "Laptop"),
    RouteNode(Glyphs.check, "Claude, ready"),
)

private val readyNodes = persistentListOf(
    RouteNode(Glyphs.phone, "This phone"),
    RouteNode(Glyphs.relay, "Relay"),
    RouteNode(Glyphs.laptop, "Laptop"),
    RouteNode(Glyphs.check, "Claude, ready"),
)

@Composable
private fun Run(ui: StartUi, cb: StartCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    val s = ui.state
    val haptics = rememberHaptics()
    val done = (s as? StartState.Starting)?.steps?.count { it.done } ?: 5
    LaunchedEffect(done) { if (s is StartState.Starting && done > 1) haptics.tick() }
    LaunchedEffect(s::class) {
        when (s) {
            is StartState.Ready -> haptics.confirm()
            is StartState.Stuck, is StartState.Exited -> haptics.reject()
            else -> {}
        }
    }
    // Each hop lights as its phase lands: accepted, terminal, then Remote Control, which is the
    // last step whether or not a handoff step came before it. A dead start breaks the last hop,
    // so the map says the same thing as the red ring.
    val hops = when (s) {
        is StartState.Starting -> listOf(done >= 2, done >= 3, done >= s.steps.size).map { if (it) Hop.Live else Hop.Pulse }
        is StartState.Stuck, is StartState.Exited -> listOf(Hop.Live, Hop.Live, Hop.Broken)
        else -> listOf(Hop.Live, Hop.Live, Hop.Live)
    }.let { if (ui.direct) listOf(it[0], it[2]) else it }.toImmutableList()
    val ring = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    val density = LocalDensity.current
    val reduced = Remoter.reducedMotion
    LaunchedEffect(s::class) {
        when (s) {
            is StartState.Ready -> if (reduced) ring.snapTo(1f) else ring.animateTo(1f, tween(Dur.screen, easing = EaseOut))
            is StartState.Stuck, is StartState.Exited -> {
                ring.snapTo(1f)
                if (!reduced) {
                    val px = with(density) { 4.dp.toPx() }
                    shake.animateTo(0f, keyframes { durationMillis = 300; px at 25; -px at 75; px at 125; -px at 175; px at 225; -px at 275 })
                }
            }
            else -> ring.snapTo(0f)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
        val shownNodes = when {
            ui.direct -> if (s is StartState.Ready) directReadyNodes else directNodes
            else -> if (s is StartState.Ready) readyNodes else nodes
        }
        RouteMap(
            shownNodes, hops, nodeSize = 48.dp, lineWidth = 4.dp, ring = ring.value,
            // Light mode draws the ring like the focus ring: `text`, volt inside.
            ringColor = if (s is StartState.Ready) c.focusRing else c.danger,
            ringInner = if (s is StartState.Ready) c.focusInner else null,
            lastNodeShake = shake,
        )
        // crossfade while the sheet height springs, or the stepper snaps to Ready in one frame
        FadeSwap(s, key = { it::class }) { shown ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                when (shown) {
                    is StartState.Starting -> Starting(ui.state as? StartState.Starting ?: shown, ui, cb)
                    is StartState.Ready -> {
                        Text("Ready", style = t.display, color = c.text)
                        Text("${shown.name} is live in the Claude app", style = t.body, color = c.textMuted)
                        ClaudeButton("Open in Claude", { cb.onOpenClaude(shown.claude) })
                        QuietButton("Done", cb.onDone, Modifier.align(Alignment.CenterHorizontally))
                    }
                    is StartState.Stuck -> Failed(shown, reasonText(shown.reason), shown.tail, ui, cb)
                    is StartState.Exited -> Failed(
                        shown,
                        "Claude exited" + (shown.code?.let { " with code $it" } ?: ""),
                        shown.tail, ui, cb,
                    )
                    else -> {}
                }
            }
        }
    }
}

fun reasonText(r: StuckReason?): String = when (r) {
    StuckReason.Untrusted -> "Claude asked to trust this folder anyway, so the laptop stopped it. Try again"
    StuckReason.NotLoggedIn -> "Claude isn't logged in on the laptop"
    StuckReason.FolderChanged -> "Folder changed while starting"
    StuckReason.Network -> "The laptop couldn't reach Claude"
    StuckReason.FolderBusy -> "Another Claude already serves this folder on the laptop"
    StuckReason.HandoffFailed -> "Couldn't write the handoff from that session."
    StuckReason.Timeout, null -> "No sign of it after 90 s. It can still come up, and this updates on its own"
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Starting(s: StartState.Starting, ui: StartUi, cb: StartCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    Column(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        // The clock rides on the step it is waiting for, instead of floating alone at the edge.
        val waiting = s.steps.indexOfFirst { !it.done }
        val elapsed = "%d:%02d".format(ui.elapsedS / 60, ui.elapsedS % 60)
        s.steps.forEachIndexed { i, step -> StepRow(step.label, step.done, if (i == waiting) elapsed else null) }
    }
    Appear(s.streamReconnecting) { Text("Reconnecting", style = t.label, color = c.warn) }
    Appear(s.slow) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            Text("Taking longer than usual", style = t.body, color = c.textMuted)
            SecondaryButton("Keep in background", cb.onClose)
        }
    }
    EndError(ui)
    EndButton("Cancel start", ui.ending, cb.onEndIt)
}

@Composable
private fun EndError(ui: StartUi) {
    val e = ui.endError ?: return
    Text(
        "Couldn't end it. " + e.copy(ui.hostname).title,
        style = Remoter.type.label,
        color = Remoter.colors.danger,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.EndButton(text: String, ending: Boolean, onClick: () -> Unit) {
    val c = Remoter.colors
    Crossfade(ending, Modifier.align(Alignment.CenterHorizontally), animationSpec = tween(Dur.base, easing = EaseOut), label = "end") { busy ->
        if (busy) {
            Box(Modifier.size(Touch.min).semantics { stateDescription = "Ending" }, contentAlignment = Alignment.Center) {
                if (Remoter.reducedMotion) {
                    CircularProgressIndicator({ 0.3f }, Modifier.size(20.dp), color = c.danger, strokeWidth = 2.dp, trackColor = Color.Transparent)
                } else {
                    CircularProgressIndicator(Modifier.size(20.dp), color = c.danger, strokeWidth = 2.dp, trackColor = Color.Transparent)
                }
            }
        } else {
            QuietButton(text, onClick, danger = true)
        }
    }
}

@Composable
private fun StepRow(label: String, done: Boolean, trailing: String? = null) {
    val c = Remoter.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 32.dp)) {
        me.river.remoter.core.design.components.SelfDrawingCheck(done)
        Spacer(Modifier.width(Space.s16))
        Text(label, style = Remoter.type.body, color = animatedTone(if (done) c.text else c.textMuted), modifier = Modifier.weight(1f))
        FadeInPlace(trailing != null) { Text(trailing ?: "", style = Remoter.type.label.tnum(), color = c.textMuted) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Failed(s: StartState, reason: String, tail: kotlinx.collections.immutable.ImmutableList<String>, ui: StartUi, cb: StartCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    // [s] is the state this layout was made for: while it fades out, ui.state is already the next one.
    Text(if (s is StartState.Exited) "Exited" else "Stuck", style = t.display, color = c.danger)
    Text(reason, style = t.body, color = c.text)
    if (tail.isNotEmpty()) TerminalCard(tail.takeLast(12).toImmutableList())
    EndError(ui)
    // The way out matches the reason: Retry alone would meet the same refusal from claude.
    val primary: Pair<String, () -> Unit> = when {
        s is StartState.Stuck && s.reason == StuckReason.FolderBusy && ui.target?.isGit == true && !ui.form.resumesAsIs ->
            "Start in a new worktree" to cb.onUseWorktree
        else -> "Retry" to cb.onRetry
    }
    // End session gets its own row exactly 24 dp under the primary, so a thumb aimed at it can't land there.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s24)) {
        PrimaryButton(primary.first, primary.second)
        EndButton("End session", ui.ending, cb.onEndIt)
    }
}

@Composable
private fun RemoterSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    me.river.remoter.core.design.components.RemoterSheet(visible, onDismiss, content = content)
