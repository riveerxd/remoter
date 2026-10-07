package me.river.remoter.feature.session

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.toImmutableList
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ClaudeLink
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DetailStatesTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_790_620_000_000
    private val sess = SessionSummary("rc-a", "remoter", "Projects/remoter", null, now - 60_000, SessionState.Ready, null, null, ClaudeLink("s", "https://claude.ai/code/s", null))
    private val shown = DetailUi(sess, nowMs = now, hostname = "r1v3r")

    private var backs = 0
    private var ends = 0
    private var dismissed = 0
    private val zooms = mutableListOf<Float>()

    private fun detail(ui: DetailUi, errors: ErrorActions = ErrorActions()) = compose.setContent {
        RemoterTheme(dark = true, reducedMotion = true) {
            SessionDetailContent(ui, { backs++ }, {}, { ends++ }, { zooms += it }, errors) { dismissed++ }
        }
    }

    private fun error(e: AppError, actions: ErrorActions) = compose.setContent {
        RemoterTheme(dark = true, reducedMotion = true) { ErrorContent(e, "r1v3r", actions) }
    }

    @Test
    fun skeletons_before_first_answer() {
        detail(DetailUi(hostname = "r1v3r"))
        compose.onNodeWithContentDescription("Loading session").assertExists()
    }

    @Test
    fun a_gone_session_says_so_and_offers_back() {
        detail(DetailUi(hostname = "r1v3r", gone = true))
        compose.onNodeWithText("This session is gone").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun ending_locks_end_and_shows_it_in_the_pill() {
        detail(shown.copy(ending = true, tail = listOf("x").toImmutableList(), tailAtMs = now))
        compose.onNodeWithContentDescription("Ending…").assertIsDisplayed()
        // While ending there is no End to press a second time, only what is happening.
        compose.onNodeWithText("End session").assertDoesNotExist()
        assertEquals("the pill and the End slot both say it", 2, compose.onAllNodesWithText("Ending\u2026", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, ends)
    }

    @Test
    fun exited_has_no_open_or_end_only_done() {
        detail(shown.copy(session = sess.copy(state = SessionState.Exited, exitCode = 1)))
        compose.onNodeWithText("Open in Claude").assertDoesNotExist()
        compose.onNodeWithText("End session").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun header_shows_host_and_workspace() {
        detail(shown)
        compose.onNodeWithText("Running on r1v3r, workspace 9").assertExists()
        compose.onNodeWithText("Open on r1v3r, workspace 9").assertDoesNotExist()
    }

    @Test
    fun locked_error_sheet_shows_unlock_command() {
        detail(shown.copy(error = AppError.Locked, failed = DetailAction.End))
        compose.onNodeWithText("r1v3r is locked").assertIsDisplayed()
        compose.onNodeWithText("sudo remoterctl lock off").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun a_wiped_key_error_offers_pair_again() {
        var paired = 0
        detail(shown.copy(error = AppError.KeyInvalidated, failed = DetailAction.End), ErrorActions(onPairAgain = { paired++ }))
        compose.onNodeWithText("Pair again").performClick()
        assertEquals(1, paired)
    }

    @Test
    fun unlocked_output_skeletons_until_the_first_capture() {
        detail(shown)
        compose.onNodeWithContentDescription("Loading terminal output").assertExists()
    }

    @Test
    fun one_finger_scrolls_and_jump_pill_is_full_size() {
        detail(shown.copy(tail = List(200) { "line $it" }.toImmutableList(), tailAtMs = now))
        compose.onNodeWithTag("terminal").performTouchInput { swipeDown() }
        compose.onNodeWithText("Jump to latest").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertTrue("a one finger drag must not zoom", zooms.isEmpty())
    }

    @Test
    fun two_fingers_zoom_the_terminal() {
        detail(shown.copy(tail = List(200) { "line $it" }.toImmutableList(), tailAtMs = now))
        compose.onNodeWithTag("terminal").performTouchInput {
            pinch(center - Offset(20f, 0f), center + Offset(20f, 0f), center - Offset(200f, 0f), center + Offset(200f, 0f))
        }
        assertTrue("pinching out grows the text", zooms.isNotEmpty() && zooms.last() > 12f)
    }

    @Test
    fun text_size_buttons_step_one_sp() {
        detail(shown.copy(tail = listOf("x").toImmutableList(), tailAtMs = now))
        compose.onNodeWithContentDescription("Larger text").performClick()
        compose.onNodeWithContentDescription("Smaller text").performClick()
        assertEquals(listOf(13f, 11f), zooms)
    }

    @Test
    fun security_sheet_lock_needs_a_hold() {
        var locks = 0
        error(AppError.Security(ErrorCode.SigInvalid, "r"), ErrorActions(onLockLaptop = { locks++ }))
        compose.onNodeWithText("Hold to lock r1v3r").performClick()
        assertEquals(0, locks)
        compose.onNode(androidx.compose.ui.test.hasStateDescription("Press and hold")).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("TalkBack's double tap still locks", 1, locks)
    }

    @Test
    fun lock_in_flight_ignores_another_confirm() {
        var locks = 0
        error(AppError.Security(ErrorCode.SigInvalid, "r"), ErrorActions(onLockLaptop = { locks++ }, locking = true))
        compose.onNodeWithText("Hold to lock r1v3r", useUnmergedTree = true).assertExists()
        compose.onNode(androidx.compose.ui.test.hasStateDescription("Press and hold")).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(0, locks)
    }

    @Test
    fun cap_row_spins_while_ending_then_leaves() {
        val other = sess.copy(id = "rc-b", name = "notes")
        val cap = AppError.SessionCap(listOf(sess, other))
        var starts = 0
        error(cap, ErrorActions(onEnd = {}, onRetry = { starts++ }, endingIds = setOf("rc-a")))
        compose.onNodeWithContentDescription("Ending remoter").assertExists()
        compose.onNodeWithText("Start now").assertDoesNotExist()
    }

    @Test
    fun ended_cap_row_drops_and_offers_start() {
        val other = sess.copy(id = "rc-b", name = "notes")
        var starts = 0
        error(AppError.SessionCap(listOf(sess, other)), ErrorActions(onEnd = {}, onRetry = { starts++ }, endedIds = setOf("rc-a")))
        compose.onNodeWithText("remoter").assertDoesNotExist()
        compose.onNodeWithText("notes").assertExists()
        compose.onNodeWithText("Start now").performClick()
        assertEquals(1, starts)
    }
}
