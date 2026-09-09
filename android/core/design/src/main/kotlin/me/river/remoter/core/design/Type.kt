package me.river.remoter.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Each weight pinned on the variable axis so 450 and 650 render as themselves
// instead of snapping to 400 and 600.
private val Weights = listOf(450, 550, 600, 650, 700)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int) = FontFamily(
    Weights.map { w ->
        Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

val Geist = variable(R.font.geist)
val GeistMono = variable(R.font.geist_mono)

private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Int, line: Int, weight: Int, tracking: Double, family: FontFamily = Geist) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
    lineHeightStyle = Trim,
)

@Immutable
data class RemoterType(
    val display: TextStyle = style(34, 40, 700, -0.02),
    val headline: TextStyle = style(28, 34, 700, -0.015),
    val title: TextStyle = style(21, 28, 650, -0.01),
    val body: TextStyle = style(16, 24, 450, 0.0),
    val bodyStrong: TextStyle = style(16, 24, 600, 0.0),
    val label: TextStyle = style(13, 18, 550, 0.01),
    val mono: TextStyle = style(12, 18, 450, 0.0, GeistMono),
)

fun TextStyle.tnum(): TextStyle = copy(fontFeatureSettings = "tnum")

val LocalRemoterType = staticCompositionLocalOf { RemoterType() }
