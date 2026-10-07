package me.river.remoter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.components.RemoterSnackbarHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "X is live" never went away on its own, and the root's snackbar sat right on top
 * of the browser's "Start in X" button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RootSnackbarsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun the_live_snackbar_goes_away_after_six_seconds() {
        var ready by mutableStateOf<String?>("remoter")
        var shown = 0
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) {
                RemoterSnackbarHost(0.dp) {
                    RootSnackbars(ready, { shown++; ready = null }, {}, null, {})
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("remoter is live").assertExists()
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals("success stays at least 4 s", 0, shown)
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(1, shown)
    }

    @Test
    fun browser_lifts_snackbars_over_its_start_button() {
        assertTrue(snackbarInset(Browser("Projects")) >= 88.dp)
        assertEquals(0.dp, snackbarInset(Home))
    }
}
