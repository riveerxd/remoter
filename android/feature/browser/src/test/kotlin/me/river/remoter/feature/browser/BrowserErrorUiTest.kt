package me.river.remoter.feature.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BrowserErrorUiTest {
    @get:Rule val compose = createComposeRule()

    private fun show(ui: BrowserUi, cb: BrowserCallbacks = BrowserCallbacks()) =
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { BrowserContent(ui, cb, false) } }

    @Test
    fun a_failed_first_load_offers_retry() {
        var retried = false
        show(BrowserUi("Projects", null, loading = false, hostname = "r1v3r", error = AppError.Unreachable), BrowserCallbacks(onRetry = { retried = true }))
        compose.onNodeWithText("Couldn't reach r1v3r").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun vpn_off_on_first_load_offers_wireguard() {
        var wg = false
        show(BrowserUi("Projects", null, loading = false, error = AppError.VpnOff), BrowserCallbacks(onOpenWireGuard = { wg = true }))
        compose.onNodeWithText("Turn on WireGuard").performClick()
        assertTrue(wg)
    }

    @Test
    fun a_failed_reload_keeps_the_list_and_says_so() {
        var retried = false
        show(browserStates.getValue("listing").copy(hostname = "r1v3r", error = AppError.Unreachable), BrowserCallbacks(onRetry = { retried = true }))
        compose.onNodeWithText("Showing the last list").assertIsDisplayed()
        compose.onNodeWithText("api").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun create_shows_a_spinner_after_the_fingerprint() {
        show(browserStates.getValue("new_folder").let { it.copy(newFolder = it.newFolder.copy(submitting = true)) })
        compose.onNodeWithTag("create-spinner", useUnmergedTree = true).assertExists()
    }

    @Test
    fun no_local_match_shows_nothing_while_the_laptop_searches() {
        show(browserStates.getValue("search_none").copy(searching = true))
        compose.onNodeWithText("Nothing called 'foo' here").assertDoesNotExist()
    }

    @Test
    fun a_failed_search_says_so_and_retries() {
        var retried = false
        show(browserStates.getValue("search_none").copy(hostname = "r1v3r", searchError = AppError.Unreachable), BrowserCallbacks(onRetrySearch = { retried = true }))
        compose.onNodeWithText("Couldn't search r1v3r").assertIsDisplayed()
        compose.onNodeWithText("Nothing called 'foo' here").assertDoesNotExist()
        compose.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun a_filled_search_can_be_cleared() {
        var q: String? = null
        show(browserStates.getValue("search_none"), BrowserCallbacks(onQuery = { q = it }))
        compose.onNodeWithContentDescription("Clear search").performClick()
        assertEquals("", q)
    }

    @Test
    fun an_empty_search_has_no_clear_button() {
        show(browserStates.getValue("listing"))
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
    }

    @Test
    fun offline_gives_a_way_to_turn_on_wireguard() {
        var wg = false
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { Column { BlockedCopy(Blocked.Offline, "r1v3r", "api", onOpenWireGuard = { wg = true }) } } }
        compose.onNodeWithText("Turn on WireGuard").performClick()
        assertTrue(wg)
    }
}
