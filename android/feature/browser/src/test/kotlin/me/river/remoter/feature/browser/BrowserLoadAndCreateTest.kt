package me.river.remoter.feature.browser

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.shots.HapticRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BrowserLoadAndCreateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun opening_a_folder_shows_no_skeleton_before_1s() {
        compose.mainClock.autoAdvance = false
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { BrowserContent(BrowserUi("Projects", null, loading = true), BrowserCallbacks(), false) } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("Loading folders").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithContentDescription("Loading folders").assertExists()
    }

    @Test
    fun the_new_folder_row_keeps_its_height_when_editing() {
        val ui = mutableStateOf(browserStates.getValue("listing"))
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { BrowserContent(ui.value, BrowserCallbacks(), false) } }
        val before = compose.onNodeWithTag("new-folder").getUnclippedBoundsInRoot().height
        ui.value = browserStates.getValue("new_folder_error")
        compose.waitForIdle()
        val after = compose.onNodeWithTag("new-folder").getUnclippedBoundsInRoot().height
        assertEquals(before, after)
    }

    @Test
    fun a_refused_new_folder_buzzes_reject() {
        val view = HapticRecorder(ApplicationProvider.getApplicationContext())
        val ui = mutableStateOf(browserStates.getValue("new_folder"))
        compose.setContent {
            CompositionLocalProvider(LocalView provides view) {
                RemoterTheme(dark = true, reducedMotion = true) { BrowserContent(ui.value, BrowserCallbacks(), false) }
            }
        }
        ui.value = ui.value.copy(newFolder = ui.value.newFolder.copy(shake = 1, editing = false))
        compose.waitForIdle()
        assertTrue("fired ${view.fired}", view.fired.contains(HapticFeedbackConstants.REJECT))
    }
}
