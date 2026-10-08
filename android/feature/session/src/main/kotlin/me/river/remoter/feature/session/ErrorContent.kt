package me.river.remoter.feature.session

import androidx.compose.animation.core.animateFloatAsState
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.Press
import me.river.remoter.core.design.FadeSwap
import me.river.remoter.core.design.AnimatedItems
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.components.HoldButton
import me.river.remoter.core.design.components.PrimaryButton
import me.river.remoter.core.design.components.ClaudeChip
import me.river.remoter.core.design.components.QuietButton
import me.river.remoter.core.design.components.StatusLabel
import me.river.remoter.core.design.components.StatusTone
import me.river.remoter.core.design.tnum
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.SessionSummary
import me.river.remoter.core.net.trimmedPath

/** What an error sheet can offer. Anything left null has no button. */
data class ErrorActions(
    val onRetry: (() -> Unit)? = null,
    val onLockLaptop: (() -> Unit)? = null,
    val onPairAgain: (() -> Unit)? = null,
    val onOpenDateSettings: (() -> Unit)? = null,
    val onOpenWireGuard: (() -> Unit)? = null,
    val onEnd: ((SessionSummary) -> Unit)? = null,
    /** Folder busy: open the session that holds the folder in the Claude app. */
    val onOpenInClaude: ((SessionSummary) -> Unit)? = null,
    /** Folder busy in a git repo: the same start, as a worktree, which is its own folder. */
    val onUseWorktree: (() -> Unit)? = null,
    /** Open conversation: start a fresh session from a handoff of it instead. */
    val onHandoffInstead: (() -> Unit)? = null,
    val onDismiss: () -> Unit = {},
    /** The lock request is in flight: the hold button spins and ignores another hold. */
    val locking: Boolean = false,
    /** Session cap rows whose End is waiting on the finger or the laptop. */
    val endingIds: Set<String> = emptySet(),
    /** Session cap rows the laptop already ended; they leave the list. */
    val endedIds: Set<String> = emptySet(),
)

/**
 * The body of an error sheet. Permission sheets carry a lock, Security ones
 * are red, and a session cap lists what is running, each with End.
 */
@Composable
fun ErrorContent(error: AppError, host: String, actions: ErrorActions, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    val t = Remoter.type
    val copy = error.copy(host)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
        when (copy.look) {
            ErrorLook.Permission -> Icon(Glyphs.lock, null, tint = c.text, modifier = Modifier.size(32.dp))
            ErrorLook.Security -> Icon(Glyphs.shield, null, tint = c.danger, modifier = Modifier.size(32.dp))
            else -> {}
        }
        Text(copy.title, style = t.title, color = if (copy.look == ErrorLook.Security) c.danger else c.text)
        copy.body?.let { Text(it, style = t.body, color = c.textMuted) }
        copy.command?.let { CommandBlock(it) }
        if (error is AppError.SessionCap) {
            // An ended session closes out of the list where it was, and End turns into its spinner in
            // place, instead of rows vanishing and the ones below jumping up.
            AnimatedItems(error.sessions.filter { it.id !in actions.endedIds }, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().heightIn(min = Touch.row), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = t.bodyStrong, color = c.text)
                        Text(trimmedPath(s.path), style = t.label, color = c.textMuted)
                    }
                    actions.onEnd?.let { end ->
                        FadeSwap(s.id in actions.endingIds) { ending ->
                            if (ending) {
                                Box(Modifier.size(Touch.min).semantics { contentDescription = "Ending ${s.name}" }, contentAlignment = Alignment.Center) {
                                    Spinner(c.danger)
                                }
                            } else {
                                QuietButton("End", { end(s) }, danger = true)
                            }
                        }
                    }
                }
            }
        }
        if (error is AppError.FolderBusy) {
            error.sessions.forEach { s ->
                Row(Modifier.fillMaxWidth().heightIn(min = Touch.row), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = t.bodyStrong, color = c.text)
                        // Two sessions of one folder read the same without it: one may be in a worktree.
                        val where = s.worktree?.let { "${trimmedPath(s.path)} · worktree $it" } ?: trimmedPath(s.path)
                        Text(where, style = t.label, color = c.textMuted)
                    }
                    actions.onOpenInClaude?.let { open -> ClaudeChip("Open", { open(s) }) }
                }
            }
        }
        copy.requestId?.let { RequestId(it) }
        Spacer(Modifier.height(Space.s8))
        when {
            error.needsPairAgain && actions.onPairAgain != null -> PrimaryButton("Pair again", actions.onPairAgain)
            error is AppError.Security || error is AppError.ReattestFailed ->
                actions.onLockLaptop?.let { HoldButton("Hold to lock $host", "Keep holding", it, loading = actions.locking) }
            error is AppError.ClockSkew -> actions.onOpenDateSettings?.let { PrimaryButton("Open date settings", it) }
            error is AppError.VpnOff -> actions.onOpenWireGuard?.let { PrimaryButton("Turn on WireGuard", it) }
            error is AppError.RateLimited -> RateLimitButton(error.retryAfterS, actions.onRetry)
            error is AppError.FolderBusy -> actions.onUseWorktree?.let { PrimaryButton("Start in a new worktree", it) }
            // Closed on the laptop by now, so the same pick goes again, under a new fingerprint.
            error == AppError.ConversationOpen -> {
                actions.onHandoffInstead?.let { PrimaryButton("Hand off instead", it) }
                actions.onRetry?.let { QuietButton("Try again", it, Modifier.align(Alignment.CenterHorizontally)) }
            }
            // A slot is free now, so the next step is the start that hit the cap.
            error is AppError.SessionCap && actions.onRetry != null && error.sessions.any { it.id in actions.endedIds } ->
                PrimaryButton("Start now", actions.onRetry)
            actions.onRetry != null && copy.look in setOf(ErrorLook.Network, ErrorLook.Server) -> PrimaryButton("Retry", actions.onRetry)
        }
        QuietButton("Close", actions.onDismiss, Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun Spinner(color: androidx.compose.ui.graphics.Color) {
    if (Remoter.reducedMotion) {
        CircularProgressIndicator({ 0.3f }, Modifier.size(20.dp), color = color, strokeWidth = 2.dp, trackColor = androidx.compose.ui.graphics.Color.Transparent)
    } else {
        CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp, trackColor = androidx.compose.ui.graphics.Color.Transparent)
    }
}

/** Counts down from `retry_after_s` in place; the button stays tappable and says how long. */
@Composable
private fun RateLimitButton(seconds: Int, onRetry: (() -> Unit)?) {
    var left by remember(seconds) { mutableStateOf(seconds) }
    LaunchedEffect(seconds) {
        while (left > 0) {
            delay(1000)
            left--
        }
    }
    PrimaryButton(if (left > 0) "Try again in $left s" else "Try again", { if (left <= 0) onRetry?.invoke() }, numeric = true)
}

@Composable
fun CommandBlock(command: String, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Row(
        modifier.fillMaxWidth().clip(Shapes.technical).background(c.terminal).padding(start = Space.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(command, style = Remoter.type.mono, color = c.onTerminal, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(Touch.min)
                .clickable(null, pressIndication(Press.Icon), role = Role.Button) {
                    clip.setText(AnnotatedString(command))
                    copied = true
                }
                .semantics { contentDescription = if (copied) "Copied" else "Copy command" },
            contentAlignment = Alignment.Center,
        ) {
            FadeSwap(copied) { done -> Icon(if (done) Glyphs.check else Glyphs.copy, null, tint = c.onTerminal, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
fun RequestId(id: String) {
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Row(
        Modifier.heightIn(min = Touch.min).clickable(role = Role.Button) {
            clip.setText(AnnotatedString(id))
            copied = true
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Request $id", style = Remoter.type.label.tnum(), color = Remoter.colors.textMuted)
        Spacer(Modifier.width(Space.s8))
        FadeSwap(copied) { done -> Icon(if (done) Glyphs.check else Glyphs.copy, if (done) "Copied" else "Copy request id", tint = Remoter.colors.textMuted, modifier = Modifier.size(16.dp)) }
    }
}
