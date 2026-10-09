package me.river.remoter.core.design.components

import androidx.compose.animation.AnimatedContent
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.Press
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.river.remoter.core.design.Dur
import me.river.remoter.core.design.EaseIn
import me.river.remoter.core.design.EaseOut
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space

/** The 2 dp line under a top bar, shown only once a load passes 150 ms or 1 s. */
@Composable
fun ProgressLine(modifier: Modifier = Modifier) {
    val c = Remoter.colors
    // Light volt is too faint as a thin line on white, so light mode uses text.
    LinearProgressIndicator(
        modifier.fillMaxWidth().height(2.dp),
        color = if (c.isDark) c.volt else c.text,
        trackColor = c.line,
    )
}

@Composable
fun Skeleton(width: Dp?, height: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp)) {
    val alpha = if (Remoter.reducedMotion) {
        1f
    } else {
        val t = rememberInfiniteTransition(label = "skeleton")
        t.animateFloat(1f, 0.55f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "skeleton").value
    }
    Box(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .alpha(alpha)
            .clip(shape)
            .background(Remoter.colors.surface),
    )
}

@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Space.gutter, vertical = Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Skeleton(40.dp, 40.dp, shape = Shapes.pill)
        Spacer(Modifier.width(Space.s16))
        androidx.compose.foundation.layout.Column {
            Skeleton(140.dp, 16.dp)
            Spacer(Modifier.height(Space.s8))
            Skeleton(90.dp, 12.dp)
        }
    }
}

// one slot for the whole app, so snackbars from the root and from screens don't stack.
// bottomInset is the current screen's bottom bar, so a snackbar never covers its button
@Composable
fun RemoterSnackbarHost(bottomInset: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val host = remember { Snackbars() }
    val reduced = Remoter.reducedMotion
    val inset by animateDpAsState(bottomInset, tween(Dur.base, easing = EaseOut), label = "snackbar inset")
    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSnackbars provides host, content = content)
        AnimatedContent(
            targetState = host.entries.lastOrNull(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(bottom = inset)
                .padding(Space.gutter),
            contentKey = { it?.id },
            contentAlignment = Alignment.BottomCenter,
            transitionSpec = {
                // A swap lets the old one leave before the new one arrives, so two never overlap.
                val wait = if (initialState != null && targetState != null) Dur.exit else 0
                val enter = fadeIn(tween(Dur.base, wait, EaseOut)) +
                    if (reduced) EnterTransition.None else slideInVertically(tween(Dur.base, wait, EaseOut)) { it / 2 }
                val exit = fadeOut(tween(Dur.exit, easing = EaseIn)) +
                    if (reduced) ExitTransition.None else slideOutVertically(tween(Dur.exit, easing = EaseIn)) { it / 2 }
                (enter togetherWith exit).using(SizeTransform(clip = false))
            },
            label = "snackbar",
        ) { e ->
            if (e != null) {
                SnackbarCard(
                    e.message, e.onDismiss, Modifier, e.actionLabel, e.onAction, e.autoDismissMs, e.claudeAction,
                    // The one leaving must not fire its timer during the exit.
                    active = host.entries.lastOrNull() === e,
                )
            }
        }
    }
}

// errors that need action stay until dismissed. the autoDismissMs countdown only runs on
// screen and holds under a finger. inside a host this just posts, and an older snackbar
// comes back once the newer one is gone, since its caller still wants it
@Composable
fun RemoterSnackbar(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    autoDismissMs: Long? = null,
    claudeAction: Boolean = false,
) {
    val host = LocalSnackbars.current
    if (host == null) {
        SnackbarCard(message, onDismiss, modifier, actionLabel, onAction, autoDismissMs, claudeAction, active = true)
        return
    }
    val entry = remember(host) { SnackEntry(host.nextId++, message) }
    SideEffect {
        entry.message = message
        entry.onDismiss = onDismiss
        entry.actionLabel = actionLabel
        entry.onAction = onAction
        entry.autoDismissMs = autoDismissMs
        entry.claudeAction = claudeAction
    }
    DisposableEffect(host, entry) {
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}

private class SnackEntry(val id: Long, message: String) {
    var message by mutableStateOf(message)
    var onDismiss by mutableStateOf({})
    var actionLabel by mutableStateOf<String?>(null)
    var onAction by mutableStateOf<(() -> Unit)?>(null)
    var autoDismissMs by mutableStateOf<Long?>(null)
    var claudeAction by mutableStateOf(false)
}

private class Snackbars {
    val entries = mutableStateListOf<SnackEntry>()
    var nextId = 0L
}

private val LocalSnackbars = staticCompositionLocalOf<Snackbars?> { null }

@Composable
private fun SnackbarCard(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    autoDismissMs: Long?,
    claudeAction: Boolean,
    active: Boolean,
) {
    val c = Remoter.colors
    var held by remember { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    val a11y = LocalAccessibilityManager.current
    val hasAction = actionLabel != null && onAction != null
    LaunchedEffect(message, autoDismissMs, active) {
        val ms = autoDismissMs ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        // TalkBack users get longer, the way the platform asks.
        var left = a11y?.calculateRecommendedTimeoutMillis(ms, containsIcons = true, containsText = true, containsControls = hasAction) ?: ms
        // Polls instead of suspending on the held state: a lifted finger costs at most one step.
        while (left > 0) {
            val step = minOf(left, HoldStepMs)
            delay(step)
            if (!held) left -= step
        }
        dismiss()
    }
    Row(
        modifier
            .fillMaxWidth()
            .shadow(if (c.isDark) 0.dp else 8.dp, Shapes.card)
            .clip(Shapes.card)
            .background(c.surfaceRaised)
            // The initial pass sees the finger even when it lands on Undo or the close button.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    held = true
                    try {
                        do {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                        } while (e.changes.any { it.pressed })
                    } finally {
                        held = false
                    }
                }
            }
            .padding(start = Space.s16)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = Remoter.type.body,
            color = c.text,
            modifier = Modifier.weight(1f).padding(vertical = Space.s16),
        )
        if (actionLabel != null && onAction != null) {
            if (claudeAction) ClaudeChip(actionLabel, onAction) else QuietButton(actionLabel, onAction)
        }
        Box(
            Modifier
                .size(48.dp)
                .clickable(null, pressIndication(Press.Icon), role = Role.Button, onClick = onDismiss)
                .clip(Shapes.pill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyphs.close, contentDescription = "Dismiss", tint = c.textMuted, modifier = Modifier.size(20.dp))
        }
    }
}

private const val HoldStepMs = 100L
