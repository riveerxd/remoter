package me.river.remoter.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.Link
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.SessionSummary
import me.river.remoter.feature.session.clockTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_790_620_000_000
    private val base = homeStates.getValue("up_no_sessions")
    private fun session(id: String, name: String, st: SessionState) =
        SessionSummary(id, name, "Projects/$name", null, now - 5_000_000, st, null, null)

    private fun show(ui: HomeUi, cb: HomeCallbacks = HomeCallbacks()) {
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { HomeContent(ui, cb, entrance = false) } }
        compose.waitForIdle()
    }

    @Test
    fun cold_laptop_down_shows_why_and_retry() {
        var retried = false
        show(HomeUi(hostname = "r1v3r", link = Link.LaptopDown(null)), HomeCallbacks(onRetry = { retried = true }))
        compose.onNodeWithText("asleep or offline", substring = true).assertIsDisplayed()
        compose.onNodeWithText("show up once r1v3r answers", substring = true).assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun first_launch_with_wireguard_off_offers_it() {
        show(HomeUi(hostname = "r1v3r", link = Link.VpnOff))
        compose.onNodeWithText("Turn on WireGuard").assertIsDisplayed()
        compose.onNodeWithText("once WireGuard is on", substring = true).assertExists()
    }

    @Test
    fun a_failed_first_load_has_try_again() {
        var refreshed = false
        show(HomeUi(hostname = "r1v3r", link = Link.Up(30), loadFailed = true), HomeCallbacks(onRefresh = { refreshed = true }))
        compose.onNodeWithText("Couldn't load your sessions", substring = true).assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertTrue(refreshed)
    }

    // an empty list before the first answer used to read as nothing running
    @Test
    fun nothing_running_waits_for_an_answer() {
        show(HomeUi(hostname = "r1v3r", link = Link.LaptopDown(null)))
        compose.onNodeWithText("Nothing running").assertDoesNotExist()
        compose.onNodeWithText("Your sessions show up once r1v3r answers.").assertExists()
    }

    @Test
    fun pins_show_before_the_laptop_ever_answered() {
        show(HomeUi(hostname = "r1v3r", link = Link.LaptopDown(null), pinned = base.pinned))
        compose.onNodeWithText("remoter").assertExists()
    }

    @Test
    fun exited_banner_reads_as_started() {
        val s = session("rc-c", "site", SessionState.Exited)
        show(base.copy(sessions = persistentListOf(s)))
        compose.onNodeWithText("started ${clockTime(s.started)}", substring = true).assertExists()
        compose.onNodeWithContentDescription("Session site, exited, started at ${clockTime(s.started)}").assertExists()
        compose.onAllNodesWithText("running", substring = true).assertCountEquals(0)
    }

    @Test
    fun exited_banner_clears_by_button_and_a11y_action() {
        val cleared = mutableListOf<String>()
        show(base.copy(sessions = persistentListOf(session("rc-c", "site", SessionState.Exited))), HomeCallbacks(onClearSession = { cleared += it }))
        compose.onNodeWithContentDescription("Clear site").performClick()
        val banner = compose.onNode(hasContentDescription("Session site", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        val actions = banner.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == "Clear" }.action() }
        assertEquals(listOf("rc-c", "rc-c"), cleared)
    }

    @Test
    fun every_session_gets_a_card_and_the_count_says_how_many() {
        val list = persistentListOf(
            session("a", "one", SessionState.Ready), session("b", "two", SessionState.Ready), session("c", "three", SessionState.Exited),
        )
        show(base.copy(sessions = list))
        listOf("one", "two", "three").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithContentDescription("2 running").assertExists()
    }

    @Test
    fun home_rows_have_a_pin_button_both_ways() {
        val toggled = mutableListOf<Pair<String, Boolean>>()
        show(base, HomeCallbacks(onTogglePin = { f, p -> toggled += f.name to p }))
        compose.onNodeWithContentDescription("Pin remoter").performClick()
        compose.onNodeWithContentDescription("Pin api").performClick()
        assertEquals(listOf("remoter" to true, "api" to false), toggled)
    }

    @Test
    fun reorder_shows_with_two_pins() {
        var reorder = false
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) { Column { NewSession(base.copy(pinned = base.recent), HomeCallbacks(onReorder = { reorder = true })) } }
        }
        compose.onNodeWithText("Reorder").performClick()
        assertTrue(reorder)
    }

    @Test
    fun the_map_says_it_opens_details() {
        var details = 0
        show(base, HomeCallbacks(onMapDetails = { details++ }))
        compose.onNodeWithText("Details").performClick()
        assertEquals(1, details)
    }

    @Test
    fun retry_shows_checking_then_still_no_answer() {
        val down = base.copy(link = Link.LaptopDown(null))
        show(down.copy(retrying = true))
        compose.onNodeWithText("Checking…").assertExists()
    }

    @Test
    fun a_retry_that_found_nobody_says_so() {
        show(base.copy(link = Link.LaptopDown(null), stillDown = 1))
        compose.onNodeWithText("Still no answer from r1v3r", substring = true).assertExists()
    }

    @Test
    fun direct_details_say_the_route_and_the_address_fix() {
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { Column { MapDetails(base.copy(direct = true, link = Link.LaptopDown(null))) } } }
        compose.onNodeWithText("Straight to the laptop").assertExists()
        compose.onNodeWithText("turn the tunnel off and on", substring = true).assertExists()
    }

    @Test
    fun wireguard_is_unknown_while_reconnecting() {
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { Column { MapDetails(base.copy(link = Link.Reconnecting)) } } }
        compose.onNodeWithText("Unknown").assertExists()
    }

    @Test
    fun a_handle_drags_at_once_without_a_long_press() {
        val moves = mutableListOf<Pair<Int, Int>>()
        val pins = persistentListOf(FolderItem("a", "alpha", false), FolderItem("b", "beta", false), FolderItem("c", "gamma", false))
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) { Column { Reorder(base.copy(pinned = pins), { f, t -> moves += f to t }) {} } }
        }
        compose.onNodeWithContentDescription("Drag to reorder alpha", useUnmergedTree = true).performTouchInput {
            // Well inside the long-press timeout: a handle that waits for a long press ignores this.
            down(center)
            repeat(16) { moveBy(Offset(0f, 8.dp.toPx()), delayMillis = 10) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(0 to 2), moves)
    }
}
