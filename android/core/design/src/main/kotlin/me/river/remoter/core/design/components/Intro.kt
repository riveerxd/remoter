package me.river.remoter.core.design.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import me.river.remoter.core.design.Dur
import me.river.remoter.core.design.EaseIn
import me.river.remoter.core.design.EaseOut
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.MarkGeometry
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Space

@Stable
class IntroAnchor {
    var bounds: Rect? by mutableStateOf(null)
}

val LocalIntroAnchor = staticCompositionLocalOf<IntroAnchor?> { null }

/**
 * Reports this layout's window bounds to the intro. Goes last in the modifier
 * handed to [RouteMap], so the bounds are exactly its node row (nodes plus the
 * 8 dp above and below).
 */
fun Modifier.introAnchor(): Modifier = composed {
    val a = LocalIntroAnchor.current
    if (a == null) this else onGloballyPositioned { a.bounds = it.boundsInWindow() }
}

/** Milliseconds from the moment the system splash is gone. */
object IntroTiming {
    const val MARK_OUT = 150
    const val NODE_START = 40
    const val NODE_STAGGER = 60
    const val NODE_POP = 240
    const val LINE_START = 180
    const val LINE_DRAW = 260
    const val PULSE_START = 400
    const val PULSE_RUN = 240
    const val RING_START = 620
    const val RING_RUN = 200

    /** Home's entrance starts here, under the fading overlay. */
    const val LEAVE = 660
    const val GLIDE = 250

    /** The nodes hold a beat past the backdrop so home's own nodes are up before they go. */
    const val FG_DELAY = 100
    const val TOTAL = LEAVE + FG_DELAY + Dur.exit
}

private val Pop = CubicBezierEasing(0.3f, 1.35f, 0.5f, 1f)
private val Sweep = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)

// EaseIn holds too long here: with it the mark still sat full size when the first node popped.
private val MarkAway = CubicBezierEasing(0.4f, 0f, 1f, 1f)

private fun win(ms: Float, start: Int, dur: Int) = ((ms - start) / dur).coerceIn(0f, 1f)

/**
 * The cold start beat after the system splash: the mark gives way to the home
 * route (phone, relay, laptop), the lines draw, one pulse runs down them, then
 * the overlay fades off home. Before [play] it holds the finished mark exactly
 * where the splash icon sat, so the handoff doesn't move a pixel. With reduced
 * motion it draws nothing and finishes as soon as it may play.
 *
 * If home reported its route row through [LocalIntroAnchor], the nodes glide
 * onto it while the backdrop fades; otherwise they fade where they are.
 */
@Composable
fun Intro(
    play: Boolean,
    onLeave: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    anchor: IntroAnchor? = LocalIntroAnchor.current,
) {
    val leave by rememberUpdatedState(onLeave)
    val done by rememberUpdatedState(onDone)
    if (Remoter.reducedMotion) {
        LaunchedEffect(play) {
            if (play) {
                leave()
                done()
            }
        }
        return
    }
    val t = remember { Animatable(0f) }
    LaunchedEffect(play) {
        if (!play) return@LaunchedEffect
        var left = false
        t.animateTo(IntroTiming.TOTAL.toFloat(), tween(IntroTiming.TOTAL, easing = LinearEasing)) {
            if (!left && value >= IntroTiming.LEAVE) {
                left = true
                leave()
            }
        }
        if (!left) leave()
        done()
    }
    val c = Remoter.colors
    val glyphs = listOf(rememberVectorPainter(Glyphs.phone), rememberVectorPainter(Glyphs.relay), rememberVectorPainter(Glyphs.laptop))
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier
            .fillMaxSize()
            .testTag("intro")
            .onGloballyPositioned { origin = it.positionInWindow() }
            // Home is live under the overlay: until it starts to leave, a tap must not land on
            // a button nobody can see yet.
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (t.value < IntroTiming.LEAVE) e.changes.forEach { it.consume() }
                        if (e.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        val ms = t.value
        val leaving = win(ms, IntroTiming.LEAVE, Dur.exit)
        drawRect(c.bg.copy(alpha = 1f - EaseIn.transform(leaving)))

        val m = win(ms, 0, IntroTiming.MARK_OUT)
        if (m < 1f) {
            val e = MarkAway.transform(m)
            scale(1f - 0.5f * e, center) { drawMark(center, 288.dp.toPx(), 1f - e, c.text, c.volt) }
        }

        // mirrors home's RouteMap: 64 dp nodes, 24 dp from each edge
        val node0 = 64.dp.toPx()
        val pad = Space.s24.toPx()
        val from = listOf(pad + node0 / 2, size.width / 2, size.width - pad - node0 / 2)
        val target = anchor?.bounds?.translate(-origin)
        val g = if (target == null) 0f else EaseOut.transform(win(ms, IntroTiming.LEAVE, IntroTiming.GLIDE))
        val node = if (target == null) node0 else lerp(node0, target.height - 16.dp.toPx(), g)
        val xs = if (target == null) {
            from
        } else {
            val to = listOf(target.left + node / 2, target.center.x, target.right - node / 2)
            from.zip(to) { a, b -> lerp(a, b, g) }
        }
        val y = if (target == null) center.y else lerp(center.y, target.center.y, g)
        val fg = if (target == null) {
            1f - EaseIn.transform(leaving)
        } else {
            1f - EaseIn.transform(win(ms, IntroTiming.LEAVE + IntroTiming.FG_DELAY, Dur.exit))
        }
        if (fg <= 0f) return@Canvas

        val lw = 8.dp.toPx()
        val segs = (0 until 2).map { i -> xs[i] + node / 2 to xs[i + 1] - node / 2 }
        val sweep = Sweep.transform(win(ms, IntroTiming.LINE_START, IntroTiming.LINE_DRAW)) * segs.size
        segs.forEachIndexed { i, (a, b) ->
            val p = (sweep - i).coerceIn(0f, 1f)
            if (p > 0f) drawLine(c.volt.copy(alpha = fg), Offset(a, y), Offset(lerp(a, b, p), y), lw, StrokeCap.Round)
        }

        val pulse = win(ms, IntroTiming.PULSE_START, IntroTiming.PULSE_RUN)
        if (pulse > 0f && pulse < 1f) {
            val lens = segs.map { (a, b) -> b - a }
            var at = Sweep.transform(pulse) * lens.sum()
            val i = if (at <= lens[0]) 0 else 1
            if (i == 1) at -= lens[0]
            val (a, b) = segs[i]
            val half = 14.dp.toPx()
            val x = a + at
            val s = Offset((x - half).coerceAtLeast(a), y)
            val e = Offset((x + half).coerceAtMost(b), y)
            drawLine(c.text.copy(alpha = 0.18f * fg), s, e, lw * 2.2f, StrokeCap.Round)
            drawLine(c.text.copy(alpha = 0.9f * fg), s, e, lw * 0.5f, StrokeCap.Round)
        }

        xs.forEachIndexed { i, x ->
            val p = win(ms, IntroTiming.NODE_START + IntroTiming.NODE_STAGGER * i, IntroTiming.NODE_POP)
            if (p <= 0f) return@forEachIndexed
            val k = Pop.transform(p)
            val a = (p * 2f).coerceAtMost(1f) * fg
            val r = node / 2 * k
            drawCircle(c.surfaceRaised.copy(alpha = a), r, Offset(x, y))
            drawCircle(c.line.copy(alpha = a), r - 0.5.dp.toPx(), Offset(x, y), style = Stroke(1.dp.toPx()))
            val gs = node * 0.45f * k
            translate(x - gs / 2, y - gs / 2) {
                with(glyphs[i]) { draw(Size(gs, gs), alpha = a, colorFilter = ColorFilter.tint(c.text)) }
            }
        }

        // The pulse lands: the laptop gets a brief volt ring, the same ring home uses for Ready.
        val ring = win(ms, IntroTiming.RING_START, IntroTiming.RING_RUN)
        if (ring > 0f && ring < 1f) {
            val r = node / 2 + 5.dp.toPx() + 6.dp.toPx() * EaseOut.transform(ring)
            drawCircle(c.volt.copy(alpha = (1f - ring) * fg), r, Offset(xs[2], y), style = Stroke(3.dp.toPx()))
        }
    }
}

private fun DrawScope.drawMark(center: Offset, sizePx: Float, alpha: Float, chevron: Color, dot: Color) {
    val k = sizePx / MarkGeometry.CANVAS
    val o = Offset(center.x - sizePx / 2, center.y - sizePx / 2)
    val (a, b, cc) = MarkGeometry.chevron
    val path = Path().apply {
        moveTo(o.x + a.x * k, o.y + a.y * k)
        lineTo(o.x + b.x * k, o.y + b.y * k)
        lineTo(o.x + cc.x * k, o.y + cc.y * k)
    }
    drawPath(path, chevron.copy(alpha = alpha), style = Stroke(MarkGeometry.STROKE * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(dot.copy(alpha = alpha), MarkGeometry.DOT_R * k, Offset(o.x + MarkGeometry.dot.x * k, o.y + MarkGeometry.dot.y * k))
}
