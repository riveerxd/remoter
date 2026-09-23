package me.river.remoter.feature.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.shots.clippedText
import me.river.remoter.core.design.shots.unlabelledClickables
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BrowserA11yTest(private val name: String) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = browserStates.keys.map { arrayOf<Any>(it) }
    }

    @get:Rule val compose = createComposeRule()

    @Test
    fun labelled_and_unclipped_at_200_percent() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) {
                RemoterTheme(dark = true, reducedMotion = true) { BrowserContent(browserStates.getValue(name), BrowserCallbacks(), false) }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(emptyList<String>(), compose.unlabelledClickables())
        assertEquals(emptyList<String>(), compose.clippedText())
    }
}
