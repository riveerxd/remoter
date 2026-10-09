package me.river.remoter.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// same geometry and 288 unit canvas as app's res/drawable/splash_mark.xml, so the intro
// draws it exactly where the splash icon sat
object MarkGeometry {
    const val CANVAS = 288f
    val chevron = listOf(Offset(112f, 100f), Offset(162f, 144f), Offset(112f, 188f))
    const val STROKE = 26f
    val dot = Offset(190f, 144f)
    const val DOT_R = 13f
}

@Composable
fun Mark(
    modifier: Modifier = Modifier,
    size: Dp = 288.dp,
    chevronColor: Color = Remoter.colors.text,
    dotColor: Color = Remoter.colors.volt,
    trim: Float = 1f,
    dotScale: Float = 1f,
) {
    Canvas(modifier.size(size).semantics { contentDescription = "remoter" }) {
        val k = this.size.minDimension / MarkGeometry.CANVAS
        val full = Path().apply {
            val (a, b, c) = MarkGeometry.chevron
            moveTo(a.x * k, a.y * k)
            lineTo(b.x * k, b.y * k)
            lineTo(c.x * k, c.y * k)
        }
        val path = if (trim >= 1f) {
            full
        } else {
            Path().also { out -> PathMeasure().apply { setPath(full, false); getSegment(0f, length * trim, out) } }
        }
        drawPath(path, chevronColor, style = Stroke(MarkGeometry.STROKE * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (dotScale > 0f) {
            drawCircle(dotColor, MarkGeometry.DOT_R * k * dotScale, Offset(MarkGeometry.dot.x * k, MarkGeometry.dot.y * k))
        }
    }
}
