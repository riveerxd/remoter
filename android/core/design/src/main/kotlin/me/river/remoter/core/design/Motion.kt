package me.river.remoter.core.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.runtime.staticCompositionLocalOf

val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)    // arriving
val EaseIn = CubicBezierEasing(0.7f, 0f, 0.84f, 0f)     // leaving

object Dur {
    const val exit = 180
    const val base = 250
    const val screen = 300
    const val stagger = 30
}

val SheetSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 520f)  // settles in about 280 ms
val PressSpring = spring<Float>(dampingRatio = 0.6f, stiffness = 1400f)
const val StaggerOffsetDp = 16

const val StaggerMaxItems = 6

/**
 * True when the system animator scale is 0. Compose already snaps timed
 * transitions then; this flag additionally stops every looping animation:
 * pulses, shimmer, route dashes.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }
