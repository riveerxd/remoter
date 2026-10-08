package me.river.remoter.feature.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import me.river.remoter.core.design.RemoterTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * At 200% font the old trip banners rode up over the map and hid "Connected". "+ New" rides
 * the same edge, so it gets the same guarantee.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeLargeFontTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun new_never_covers_the_status_line_at_200_percent() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                RemoterTheme(dark = false, reducedMotion = true) { HomeContent(homeStates.getValue("up_sessions"), HomeCallbacks(), entrance = false) }
            }
        }
        compose.waitForIdle()
        val status = compose.onNodeWithContentDescription("Connected").getUnclippedBoundsInRoot()
        val new = compose.onNodeWithContentDescription("New session").getUnclippedBoundsInRoot()
        assertTrue("+ New starts at ${new.top}, above the status line's bottom ${status.bottom}", new.top >= status.bottom)
    }
}
