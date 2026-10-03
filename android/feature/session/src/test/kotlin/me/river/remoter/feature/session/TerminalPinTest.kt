package me.river.remoter.feature.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import kotlinx.collections.immutable.toImmutableList
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A pushed tail lands up to twice a second now; reading back up must not be yanked to the bottom by it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TerminalPinTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_790_620_000_000
    private val sess = SessionSummary("rc-a", "remoter", "Projects/remoter", null, now - 60_000, SessionState.Ready, null, null)
    private fun lines(n: Int) = (1..n).map { "line $it" }.toImmutableList()
    private var ui by mutableStateOf(DetailUi(sess, tail = lines(60), tailAtMs = now, nowMs = now, hostname = "r1v3r", live = true))

    private fun scroll() = compose.onNodeWithTag("terminal").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    private fun show() = compose.setContent {
        RemoterTheme(dark = true, reducedMotion = true) {
            SessionDetailContent(ui, {}, {}, {}, {})
        }
    }

    @Test
    fun at_the_bottom_a_new_tail_keeps_it_there() {
        show()
        compose.onNodeWithText("Jump to latest").performClick()
        compose.waitForIdle()
        assertEquals(scroll().maxValue(), scroll().value())
        ui = ui.copy(tail = lines(80))
        compose.waitForIdle()
        assertTrue("the bottom moved", scroll().maxValue() > 0)
        assertEquals("still pinned to the newest line", scroll().maxValue(), scroll().value())
        compose.onNodeWithText("Jump to latest").assertDoesNotExist()
    }

    @Test
    fun scrolled_up_new_tail_does_not_jump() {
        show()
        compose.onNodeWithText("Jump to latest").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("terminal").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val at = scroll().value()
        assertTrue(at < scroll().maxValue())
        ui = ui.copy(tail = lines(80))
        compose.waitForIdle()
        assertEquals("reading back must not jump", at, scroll().value())
    }
}
