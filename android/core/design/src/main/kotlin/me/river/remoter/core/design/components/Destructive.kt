package me.river.remoter.core.design.components

import androidx.compose.animation.core.Animatable
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.Press
import me.river.remoter.core.design.animatedAlpha
import me.river.remoter.core.design.SwapText
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.river.remoter.core.design.Dur
import me.river.remoter.core.design.EaseIn
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.rememberHaptics

const val HOLD_MS = 500

/**
 * Press and hold to commit: a red fill runs across the button and the action
 * fires when it reaches the end. Letting go early drains it and nothing
 * happens. For actions that are a pain to undo but too common for a dialog.
 * TalkBack can't hold, so its double tap runs the action straight away.
 */
@Composable
fun HoldButton(
    text: String,
    holdingText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    /** Red only when it destroys something; a lock deletes nothing, so it stays neutral. */
    danger: Boolean = false,
) {
    val c = Remoter.colors
    val tone = if (danger) c.danger else c.text
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val fill = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    val confirm by rememberUpdatedState(onConfirm)
    val busy by rememberUpdatedState(loading)
    DangerShell(
        modifier
            // Drawn behind the whole pill, padding included, which a child box can't reach.
            .drawBehind { drawRect(tone.copy(alpha = if (danger) 0.22f else 0.14f), size = size.copy(width = size.width * fill.value)) }
            .semantics {
                role = Role.Button
                stateDescription = "Press and hold"
                onClick(text) {
                    if (!busy) confirm()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (busy) return@detectTapGestures
                    holding = true
                    haptics.tick()
                    val run = scope.launch {
                        fill.animateTo(1f, tween(((1f - fill.value) * HOLD_MS).toInt(), easing = LinearEasing))
                        haptics.confirm()
                        confirm()
                    }
                    val released = tryAwaitRelease()
                    holding = false
                    if (fill.value < 1f) {
                        run.cancel()
                        if (released) haptics.reject()
                    }
                    scope.launch { fill.animateTo(0f, tween(Dur.exit, easing = EaseIn)) }
                })
            },
        loading = loading,
        tone = tone,
    ) {
        SwapText(if (holding) holdingText else text, Remoter.type.bodyStrong, tone, Modifier.alpha(animatedAlpha(!loading, "label")))
    }
}

/**
 * The one destructive button style: outlined in danger red, full width, never
 * filled, so it can't be mistaken for the screen's main action. What it
 * destroys is said next to it, not in a dialog after it.
 */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, loading: Boolean = false) {
    val busy by rememberUpdatedState(loading)
    DangerShell(modifier.clickable(null, pressIndication(Press.Button), role = Role.Button) { if (!busy) onClick() }, loading, Remoter.colors.danger) {
        Text(text, style = Remoter.type.bodyStrong, color = Remoter.colors.danger, modifier = Modifier.alpha(animatedAlpha(!loading, "label")))
    }
}

@Composable
private fun DangerShell(modifier: Modifier, loading: Boolean, tone: Color, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val c = Remoter.colors
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.primaryButton)
            .clip(Shapes.pill)
            .border(1.dp, if (tone == c.danger) c.danger.copy(alpha = 0.6f) else c.line, Shapes.pill)
            .then(modifier)
            .padding(horizontal = Space.s24, vertical = Space.s16),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (loading) {
            if (Remoter.reducedMotion) {
                CircularProgressIndicator({ 0.3f }, Modifier.size(20.dp), color = tone, strokeWidth = 2.dp, trackColor = Color.Transparent)
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), color = tone, strokeWidth = 2.dp, trackColor = Color.Transparent)
            }
        }
    }
}
