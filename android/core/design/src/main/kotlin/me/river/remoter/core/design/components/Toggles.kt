package me.river.remoter.core.design.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.Dur
import me.river.remoter.core.design.EaseOut
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.animatedTone

// not Material's: it had its own motion and a ripple halo nothing else here has. visual only,
// the row around it is the toggleable so label and switch are one target
@Composable
fun RemoterSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    val reduced = Remoter.reducedMotion
    val x by animateDpAsState(
        if (checked) TrackW - Thumb - Inset * 2 else 0.dp,
        if (reduced) tween(0) else spring(dampingRatio = 0.86f, stiffness = 520f),
        label = "switch",
    )
    Box(
        modifier
            .size(TrackW, TrackH)
            .clip(Shapes.pill)
            .background(animatedTone(if (checked) c.cta else c.surfaceRaised, "track"))
            .border(1.dp, animatedTone(if (checked) c.cta else c.textMuted, "track edge"), Shapes.pill)
            .padding(Inset),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = x).size(Thumb).clip(Shapes.pill).background(animatedTone(if (checked) c.onCta else c.textMuted, "thumb")))
    }
}

private val TrackW = 52.dp
private val TrackH = 32.dp
private val Thumb = 24.dp
private val Inset = 4.dp

@Composable
fun SelfDrawingCheck(done: Boolean, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val c = Remoter.colors
    val p by animateFloatAsState(if (done) 1f else 0f, tween(if (Remoter.reducedMotion) 0 else Dur.base, easing = EaseOut), label = "check")
    Box(modifier.size(size).clip(Shapes.pill).background(animatedTone(if (done) c.volt else c.surface, "check disc")), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.6f)) {
            if (p > 0f) {
                val a = Offset(this.size.width * 0.1f, this.size.height * 0.55f)
                val b = Offset(this.size.width * 0.4f, this.size.height * 0.85f)
                val e = Offset(this.size.width * 0.92f, this.size.height * 0.2f)
                val first = (p / 0.4f).coerceAtMost(1f)
                drawLine(c.onVolt, a, a + (b - a) * first, 2.dp.toPx(), StrokeCap.Round)
                if (p > 0.4f) drawLine(c.onVolt, b, b + (e - b) * ((p - 0.4f) / 0.6f), 2.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}
