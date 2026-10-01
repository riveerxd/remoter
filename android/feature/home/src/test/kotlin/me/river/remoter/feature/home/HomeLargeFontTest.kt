package me.river.remoter.feature.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import me.river.remoter.core.design.RemoterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * At 200% font the banners rode up over the map and hid "Connected" (golden
 * home_up_sessions_light_200). They now take only the room below the status line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeLargeFontTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun banners_never_cover_the_status_line_at_200_percent() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                RemoterTheme(dark = false, reducedMotion = true) { HomeContent(homeStates.getValue("up_sessions"), HomeCallbacks(), entrance = false) }
            }
        }
        compose.waitForIdle()
        val status = compose.onNodeWithContentDescription("Connected").getUnclippedBoundsInRoot()
        val banners = compose.onAllNodes(hasContentDescription("Session ", substring = true)).fetchSemanticsNodes()
        banners.forEach { b ->
            val top = with(compose.density) { b.boundsInRoot.top.toDp() }
            assertTrue("a banner starts at $top, above the status line's bottom ${status.bottom}", top >= status.bottom)
        }
    }

    @Test
    fun banners_that_dont_fit_fold_into_the_pill() {
        assertEquals("room for two and the pill", 2, bannersThatFit(3, roomPx = 400f, bannerPx = 150f, pillPx = 60f, gapPx = 10f))
        assertEquals("one and the pill", 1, bannersThatFit(3, roomPx = 260f, bannerPx = 150f, pillPx = 60f, gapPx = 10f))
        assertEquals("only the pill", 0, bannersThatFit(3, roomPx = 100f, bannerPx = 150f, pillPx = 60f, gapPx = 10f))
        assertEquals("a lone banner needs no pill", 1, bannersThatFit(1, roomPx = 150f, bannerPx = 150f, pillPx = 60f, gapPx = 10f))
        assertEquals("unmeasured shows two", 2, bannersThatFit(3, roomPx = 0f, bannerPx = 0f, pillPx = 0f, gapPx = 0f))
    }
}
