package me.river.remoter.core.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity

/**
 * The refusal shake: 3 x 4 dp over 300 ms. [trigger] changes to play it again.
 * Under reduced motion it doesn't move; the error text carries the meaning.
 */
fun Modifier.shake(trigger: Int): Modifier = composed {
    val x = remember { Animatable(0f) }
    val px = with(LocalDensity.current) { 4f * density }
    val reduced = Remoter.reducedMotion
    LaunchedEffect(trigger) {
        if (trigger == 0 || reduced) return@LaunchedEffect
        x.animateTo(
            0f,
            keyframes {
                durationMillis = 300
                px at 25; -px at 75; px at 125; -px at 175; px at 225; -px at 275; 0f at 300
            },
        )
    }
    graphicsLayer { translationX = x.value }
}

/**
 * Layout entrance: 30 ms apart, first six only, 16 dp rise, 250 ms EaseOut.
 * Data arriving later never uses this; it crossfades in place instead.
 */
fun Modifier.staggerIn(index: Int, play: Boolean): Modifier = composed {
    val reduced = Remoter.reducedMotion
    val p = remember { Animatable(if (play && !reduced) 0f else 1f) }
    val rise = with(LocalDensity.current) { StaggerOffsetDp * density }
    LaunchedEffect(play) {
        if (!play || reduced) {
            p.snapTo(1f)
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay((index.coerceAtMost(StaggerMaxItems - 1) * Dur.stagger).toLong())
        p.animateTo(1f, tween(Dur.base, easing = EaseOut))
    }
    graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * rise
    }
}

/** Holds a value visible for at least [minMs] once shown, so a skeleton never flickers. */
@Composable
fun rememberHeld(show: Boolean, minMs: Long = 300): Boolean {
    val state = remember { androidx.compose.runtime.mutableStateOf(show) }
    val shownAt = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(show) {
        if (show) {
            shownAt.longValue = System.currentTimeMillis()
            state.value = true
        } else if (state.value) {
            val left = minMs - (System.currentTimeMillis() - shownAt.longValue)
            if (left > 0) kotlinx.coroutines.delay(left)
            state.value = false
        }
    }
    return state.value
}

/** True only once [active] has stayed true for [afterMs]. Loaders use it so fast loads show nothing. */
@Composable
fun rememberAfter(active: Boolean, afterMs: Long): Boolean {
    val state = remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(active) {
        state.value = false
        if (active) {
            kotlinx.coroutines.delay(afterMs)
            state.value = true
        }
    }
    return state.value && active
}
