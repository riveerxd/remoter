package me.river.remoter.core.design.components

import androidx.compose.animation.core.animateFloatAsState
import me.river.remoter.core.design.SwapText
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.Press
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.PressSpring
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch

// the Claude app's own colours, so it's obvious the button leaves remoter
object ClaudeColors {
    val coral = Color(0xFFD97757)
    val ink = Color(0xFF141413)
    // clawd_body in claude's terminal theme
    val crab = Color(0xFFD77757)
}

// the crab claude prints on its welcome screen (2.1.286, front pose). a terminal cell is
// twice as tall as wide, so one quadrant is 1 by 2 here
internal val CRAB_ROWS = listOf(
    " \u2590\u259B\u2588\u2588\u2588\u259B\u2588 ",
    "\u259D\u259C\u2588\u2588\u2588\u2588\u2588\u2588\u2580",
    " \u259D\u259D   \u259D\u259D ",
)

/** Which quadrants a block character fills: top left, top right, bottom left, bottom right. */
internal fun quadrants(c: Char): BooleanArray = when (c) {
    '\u2588' -> booleanArrayOf(true, true, true, true)
    '\u2590' -> booleanArrayOf(false, true, false, true)
    '\u258C' -> booleanArrayOf(true, false, true, false)
    '\u2580' -> booleanArrayOf(true, true, false, false)
    '\u2584' -> booleanArrayOf(false, false, true, true)
    '\u259B' -> booleanArrayOf(true, true, true, false)
    '\u259C' -> booleanArrayOf(true, true, false, true)
    '\u2599' -> booleanArrayOf(true, false, true, true)
    '\u259F' -> booleanArrayOf(false, true, true, true)
    '\u2598' -> booleanArrayOf(true, false, false, false)
    '\u259D' -> booleanArrayOf(false, true, false, false)
    '\u2596' -> booleanArrayOf(false, false, true, false)
    '\u2597' -> booleanArrayOf(false, false, false, true)
    else -> booleanArrayOf(false, false, false, false)
}

// the sprite's empty left column and bottom rows put the crab off centre in every disc, so the
// canvas is cropped to what it draws. the thin legs leave its weight above the box, so centring
// the box alone still read as high: hence CRAB_DROP
internal const val CRAB_LEFT = 1f
internal const val CRAB_W = 17f
internal const val CRAB_DROP = 0.6f
internal const val CRAB_H = 10f + CRAB_DROP

/** (x, y, w, h) in sprite units, before cropping. */
internal fun crabPixels(): List<FloatArray> = buildList {
    CRAB_ROWS.forEachIndexed { row, line ->
        line.forEachIndexed { col, ch ->
            val q = quadrants(ch)
            for (i in 0 until 4) if (q[i]) add(floatArrayOf(col * 2f + i % 2, row * 4f + (i / 2) * 2f, 1f, 2f))
        }
    }
}

// keep the 17 : 10.6 shape or it stretches
val CrabSize = androidx.compose.ui.unit.DpSize(27.dp, 16.8.dp)

val ClaudeCrab: ImageVector by lazy {
    ImageVector.Builder("ClaudeCrab", CrabSize.width, CrabSize.height, CRAB_W, CRAB_H).apply {
        path(fill = SolidColor(Color.Black)) {
            for (p in crabPixels()) rect(p[0] - CRAB_LEFT, p[1] + CRAB_DROP, p[2], p[3])
        }
    }.build()
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.rect(x: Float, y: Float, w: Float, h: Float) {
    moveTo(x, y)
    horizontalLineTo(x + w)
    verticalLineTo(y + h)
    horizontalLineTo(x)
    close()
}

// ink label because white on this coral fails contrast (ink is about 6:1)
@Composable
fun ClaudeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Touch.primaryButton)
            .clickable(null, pressIndication(Press.Button), role = Role.Button, onClick = onClick)
            .clip(Shapes.pill)
            .background(ClaudeColors.coral)
            .padding(horizontal = Space.s24, vertical = Space.s8),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(Shapes.pill).background(ClaudeColors.ink), contentAlignment = Alignment.Center) {
            Icon(ClaudeCrab, contentDescription = null, tint = ClaudeColors.crab, modifier = Modifier.size(CrabSize))
        }
        Box(Modifier.size(Space.s8 + Space.s4))
        SwapText(text, Remoter.type.bodyStrong, ClaudeColors.ink)
    }
}

// 40 dp pill, 48 dp hit area
@Composable
fun ClaudeChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = Touch.min)
            .clickable(null, pressIndication(Press.Button), role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .clip(Shapes.pill)
                .background(ClaudeColors.coral)
                .padding(start = Space.s4, end = Space.s16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clip(Shapes.pill).background(ClaudeColors.ink), contentAlignment = Alignment.Center) {
                Icon(ClaudeCrab, contentDescription = null, tint = ClaudeColors.crab, modifier = Modifier.size(CrabSize * (21f / 27f)))
            }
            Box(Modifier.size(Space.s8))
            Text(text, style = Remoter.type.bodyStrong, color = ClaudeColors.ink)
        }
    }
}

