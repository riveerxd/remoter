package me.river.remoter.core.design.components

import androidx.compose.animation.core.Animatable
import me.river.remoter.core.design.EaseOut
import me.river.remoter.core.design.Dur
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes

/** How one hop looks. Every state has words next to it on screen; the line is never the only signal. */
enum class Hop { Live, Pulse, Broken, Idle }

@Immutable
data class RouteNode(val glyph: ImageVector, val description: String)

@Composable
fun RouteMap(
    nodes: ImmutableList<RouteNode>,
    hops: ImmutableList<Hop>,
    modifier: Modifier = Modifier,
    nodeSize: Dp = 56.dp,
    lineWidth: Dp = 6.dp,
    ring: Float = 0f,
    ringColor: Color = Remoter.colors.volt,
    /** A thin inner stroke, for light mode where volt alone on white would be 1.33:1. */
    ringInner: Color? = null,
    lastNodeShake: Animatable<Float, *>? = null,
) {
    val c = Remoter.colors
    val reduced = Remoter.reducedMotion
    val t = rememberInfiniteTransition(label = "route")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "flow")
    val pulse by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
    val density = LocalDensity.current
    val fades = rememberHopFades(hops)
    BoxWithConstraints(modifier.fillMaxWidth().height(nodeSize + 16.dp)) {
        val w = constraints.maxWidth.toFloat()
        val node = with(density) { nodeSize.toPx() }
        val gap = if (nodes.size > 1) (w - node) / (nodes.size - 1) else 0f
        val cy = with(density) { (nodeSize / 2 + 8.dp).toPx() }
        Canvas(Modifier.fillMaxWidth().height(nodeSize + 16.dp)) {
            val lw = lineWidth.toPx()
            val ringR = node / 2 + 5.dp.toPx()
            hops.indices.forEach { i ->
                val (x0, x1) = hopSpan(i, nodes.size, node, gap, lw, if (ring > 0f) ringR else null)
                val a = Offset(x0, cy)
                val b = Offset(x1, cy)
                val f = fades.getOrNull(i)
                val p = f?.progress?.value ?: 1f
                // blend a changed hop instead of switching colour in one frame
                if (f != null && p < 1f) drawHop(f.from, a, b, lw, 1f - p, c, reduced, flow, pulse)
                drawHop(f?.to ?: hops[i], a, b, lw, p, c, reduced, flow, pulse)
            }
            if (ring > 0f) {
                val cx = node / 2 + gap * (nodes.size - 1)
                val r = ringR
                drawArc(
                    ringColor, -90f, 360f * ring, false,
                    topLeft = Offset(cx - r, cy - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                )
                if (ringInner != null) {
                    val ri = r - 2.5.dp.toPx()
                    drawArc(
                        ringInner, -90f, 360f * ring, false,
                        topLeft = Offset(cx - ri, cy - ri), size = androidx.compose.ui.geometry.Size(ri * 2, ri * 2),
                        style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
        nodes.forEachIndexed { i, n ->
            val x = with(density) { (gap * i).toDp() }
            val shake = if (i == nodes.lastIndex && lastNodeShake != null) {
                with(density) { lastNodeShake.value.toDp() }
            } else {
                0.dp
            }
            Box(
                Modifier
                    .offset(x = x + shake, y = 8.dp)
                    .size(nodeSize)
                    .clip(Shapes.pill)
                    .background(c.surfaceRaised)
                    .border(1.dp, c.line, Shapes.pill),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.animation.Crossfade(n.glyph, animationSpec = tween(Dur.base, easing = EaseOut), label = "glyph") { g ->
                    Icon(g, n.description, tint = c.text, modifier = Modifier.size(nodeSize * 0.45f))
                }
            }
        }
    }
}

/**
 * Where hop [i] starts and ends. The ring sits apart from the last node, so the
 * last hop stops at the ring with its round cap, not at the node inside it.
 */
internal fun hopSpan(i: Int, count: Int, node: Float, gap: Float, lw: Float, ringR: Float?): Pair<Float, Float> {
    val x0 = node / 2 + gap * i + node / 2
    val centre = node / 2 + gap * (i + 1)
    val x1 = if (ringR != null && i + 1 == count - 1) centre - ringR - lw / 2 else centre - node / 2
    return x0 to x1
}

private class HopFade(var from: Hop, var to: Hop, val progress: Animatable<Float, AnimationVector1D>)

@Composable
private fun rememberHopFades(hops: List<Hop>): List<HopFade> {
    val fades = remember(hops.size) { hops.map { HopFade(it, it, Animatable(1f)) } }
    val reduced = Remoter.reducedMotion
    hops.forEachIndexed { i, h ->
        LaunchedEffect(i, h) {
            val f = fades.getOrNull(i) ?: return@LaunchedEffect
            if (f.to == h) return@LaunchedEffect
            f.from = f.to
            f.to = h
            if (reduced) {
                f.progress.snapTo(1f)
            } else {
                f.progress.snapTo(0f)
                f.progress.animateTo(1f, tween(Dur.base, easing = EaseOut))
            }
        }
    }
    return fades
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHop(
    hop: Hop, a: Offset, b: Offset, lw: Float, alpha: Float,
    c: me.river.remoter.core.design.RemoterColors, reduced: Boolean, flow: Float, pulse: Float,
) {
    if (alpha <= 0f) return
    when (hop) {
        Hop.Live -> {
            // light volt on white is too faint for a line, and full text read as a heavy black
            // bar. textMuted clears 3:1 and the volt dashes carry the motion
            val base = if (c.isDark) c.volt else c.textMuted
            val dash = if (c.isDark) c.bg.copy(alpha = 0.55f) else c.volt
            drawLine(base, a, b, lw, StrokeCap.Round, alpha = alpha)
            val phase = if (reduced) 0f else -flow * 48.dp.toPx()
            drawLine(dash, a, b, lw * 0.5f, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 38.dp.toPx()), phase), alpha = alpha)
        }
        Hop.Pulse -> drawLine(c.warn, a, b, lw, StrokeCap.Round, alpha = alpha * (if (reduced) 1f else pulse))
        Hop.Broken -> drawLine(c.danger, a, b, lw, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 12.dp.toPx())), alpha = alpha)
        Hop.Idle -> drawLine(c.line, a, b, lw, StrokeCap.Round, alpha = alpha)
    }
}
