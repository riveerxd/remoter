package me.river.remoter.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeUiTest {
    @get:Rule val compose = createComposeRule()

    private fun show(ui: HomeUi, cb: HomeCallbacks = HomeCallbacks()) =
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { HomeContent(ui, cb, entrance = false) } }

    @Test
    fun first_run_has_all_five_parts() {
        var browsed = false
        show(homeStates.getValue("first_run"), HomeCallbacks(onBrowseHome = { browsed = true }))
        compose.onNodeWithText("Your folders land here").assertIsDisplayed()
        compose.onNodeWithText("Start a session once", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Browse ~").performClick()
        assertTrue(browsed)
        compose.onNodeWithText("Try one of these").assertExists()
    }

    @Test
    fun no_sessions_is_one_quiet_line() {
        show(homeStates.getValue("up_no_sessions"))
        compose.onNodeWithText("Sessions you start show up here").assertIsDisplayed()
    }

    @Test
    fun a_row_tap_opens_start_directly() {
        var started: FolderItem? = null
        show(homeStates.getValue("up_no_sessions"), HomeCallbacks(onFolder = { started = it }))
        compose.onNodeWithText("remoter").performClick()
        assertEquals("Projects/remoter", started?.path)
    }

    @Test
    fun vpn_off_offers_wireguard_and_laptop_down_offers_retry() {
        var wg = false
        show(homeStates.getValue("vpn_off"), HomeCallbacks(onOpenWireGuard = { wg = true }))
        // Dot and word are one accessible node, named by its description.
        compose.onNodeWithContentDescription("WireGuard is off").assertIsDisplayed()
        compose.onNodeWithText("Turn on WireGuard").performClick()
        assertTrue(wg)
    }

    @Test
    fun laptop_down_says_last_seen() {
        var retried = false
        show(homeStates.getValue("laptop_down"), HomeCallbacks(onRetry = { retried = true }))
        compose.onNodeWithText("asleep or offline", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun reconnecting_waits_300ms_before_showing() {
        compose.mainClock.autoAdvance = false
        show(homeStates.getValue("up_no_sessions").copy(link = Link.Reconnecting))
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("Reconnecting…").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithContentDescription("Reconnecting…").assertExists()
    }
}
