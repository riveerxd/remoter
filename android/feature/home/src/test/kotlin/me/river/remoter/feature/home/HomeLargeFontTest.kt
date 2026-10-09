package me.river.remoter.feature.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

// at 200% the old banners rode up over the map and hid Connected. + New rides the same edge
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeLargeFontTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun new_clears_status_at_200_percent() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                RemoterTheme(dark = false, reducedMotion = true) { HomeContent(homeStates.getValue("up_sessions"), HomeCallbacks(), entrance = false) }
            }
        }
        compose.waitForIdle()
        val status = compose.onNodeWithContentDescription("Connected").getUnclippedBoundsInRoot()
        val new = compose.onNodeWithContentDescription("New session").getUnclippedBoundsInRoot()
        assertTrue("new ${new.top}, status ${status.bottom}", new.top >= status.bottom)
    }

    @Test
    fun new_clears_load_card() {
        var scale by androidx.compose.runtime.mutableFloatStateOf(1f)
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = scale)) {
                RemoterTheme(dark = true, reducedMotion = true) { HomeContent(homeStates.getValue("up_sessions_load"), HomeCallbacks(), entrance = false) }
            }
        }
        compose.waitForIdle()
        val card = compose.onNodeWithContentDescription("CPU", substring = true).getUnclippedBoundsInRoot()
        val new = compose.onNodeWithContentDescription("New session").getUnclippedBoundsInRoot()
        assertTrue("new ${new.top}, card ${card.bottom}", new.top >= card.bottom)
        // no room at 200%, the details sheet carries it then
        scale = 2f
        compose.waitForIdle()
        compose.onNodeWithContentDescription("CPU", substring = true).assertDoesNotExist()
    }
}
