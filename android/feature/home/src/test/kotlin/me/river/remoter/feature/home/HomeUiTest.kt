package me.river.remoter.feature.home

import androidx.compose.ui.semantics.getOrNull
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

private fun clickLabelled(label: String) = androidx.compose.ui.test.SemanticsMatcher("click label $label") {
    it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label == label
}

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
        compose.onNodeWithText("Nothing running yet").assertIsDisplayed()
        compose.onNodeWithText("Start Claude Code in a folder", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Browse ~").performClick()
        assertTrue(browsed)
        compose.onNodeWithText("Try one of these").assertExists()
    }

    @Test
    fun nothing_running_offers_the_folders_to_start_again() {
        var started: FolderItem? = null
        show(homeStates.getValue("up_no_sessions"), HomeCallbacks(onFolder = { started = it }))
        compose.onNodeWithText("Nothing running").assertIsDisplayed()
        compose.onNodeWithText("Start again").assertExists()
        compose.onNodeWithText("api").performClick()
        assertEquals("Projects/api", started?.path)
    }

    @Test
    fun the_load_card_opens_processes() {
        var opened = 0
        show(homeStates.getValue("up_load"), HomeCallbacks(onLoad = { opened++ }))
        compose.onNodeWithContentDescription("CPU 23 percent busy", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Memory 40 percent used", substring = true).assertExists()
        compose.onNodeWithContentDescription("Disk 58 percent used, 384 GB free", substring = true).assertExists()
        compose.onNode(clickLabelled("Show processes")).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun connection_details_carry_the_load_too() {
        var opened = 0
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) {
                androidx.compose.foundation.layout.Column { MapDetails(homeStates.getValue("up_load"), onProcesses = { opened++ }) }
            }
        }
        compose.onNodeWithText("CPU 23% · Memory 40% · Disk 58%").assertExists()
        compose.onNodeWithText("Processes").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun no_load_card_while_the_laptop_is_away() {
        show(homeStates.getValue("laptop_down_load"))
        compose.onNodeWithContentDescription("CPU", substring = true).assertDoesNotExist()
        compose.onNode(clickLabelled("Show processes")).assertDoesNotExist()
    }

    @Test
    fun new_opens_the_new_session_sheet() {
        var opened = 0
        show(homeStates.getValue("up_sessions"), HomeCallbacks(onNew = { opened++ }))
        compose.onNodeWithContentDescription("New session").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun a_ready_card_opens_claude() {
        var opened: String? = null
        show(homeStates.getValue("up_sessions"), HomeCallbacks(onOpenClaude = { opened = it.name }))
        compose.onNodeWithContentDescription("Open remoter in Claude").performClick()
        assertEquals("remoter", opened)
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
