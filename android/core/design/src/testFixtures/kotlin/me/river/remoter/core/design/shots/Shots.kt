package me.river.remoter.core.design.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.RemoterTheme

data class Variant(val dark: Boolean, val fontScale: Float) {
    val suffix get() = (if (dark) "dark" else "light") + "_" + (if (fontScale == 1f) "100" else "200")

    companion object {
        val all = listOf(Variant(false, 1f), Variant(true, 1f), Variant(false, 2f), Variant(true, 2f))
        @JvmStatic fun params(): List<Array<Any>> = all.map { arrayOf<Any>(it) }
    }
}

/** Loops stop so a capture never lands on a random frame; the clock is the test's. */
fun ComposeContentTestRule.shot(name: String, v: Variant, content: @Composable () -> Unit) {
    setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, v.fontScale)) {
            RemoterTheme(dark = v.dark, reducedMotion = true) {
                Box(Modifier.fillMaxSize().background(Remoter.colors.bg)) { content() }
            }
        }
    }
    waitForIdle()
    onRoot().captureRoboImage("src/test/snapshots/${name}_${v.suffix}.png")
}
