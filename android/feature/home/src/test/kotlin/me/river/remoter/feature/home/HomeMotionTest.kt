package me.river.remoter.feature.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import kotlinx.collections.immutable.persistentListOf
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Gestures that follow the finger, rows that move instead of blinking. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeMotionTest {
    @get:Rule val compose = createComposeRule()

    private val exited = SessionSummary("rc-x", "old-session", "Projects/old", null, 0, SessionState.Exited, null, 1)

    private fun show(cleared: MutableList<String>) {
        val ui = homeStates.getValue("up_no_sessions").copy(sessions = persistentListOf(exited))
        compose.setContent { RemoterTheme(dark = true, reducedMotion = false) { HomeContent(ui, HomeCallbacks(onClearSession = { cleared += it }), entrance = false) } }
        compose.waitForIdle()
    }

    @Test
    fun a_short_pull_on_an_exited_banner_springs_back() {
        val cleared = mutableListOf<String>()
        show(cleared)
        compose.onNodeWithContentDescription("Session old-session", substring = true).performTouchInput { swipeDown(startY = centerY, endY = centerY + 40f) }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), cleared)
    }

    @Test
    fun a_long_pull_on_an_exited_banner_clears_it() {
        val cleared = mutableListOf<String>()
        show(cleared)
        compose.onNodeWithContentDescription("Session old-session", substring = true).performTouchInput { swipeDown(startY = centerY, endY = centerY + 600f) }
        compose.waitForIdle()
        assertEquals(listOf("rc-x"), cleared)
    }

    /** Pinning moves a folder from Recent to Pinned through the row animations, once, without a crash. */
    @Test
    fun pinning_moves_the_row_between_lists() {
        val base = homeStates.getValue("up_no_sessions")
        val folder = base.recent.first()
        val ui = mutableStateOf(base.copy(pinned = persistentListOf()))
        compose.setContent { RemoterTheme(dark = true, reducedMotion = false) { HomeContent(ui.value, HomeCallbacks(), entrance = false) } }
        compose.waitForIdle()
        ui.value = ui.value.copy(pinned = persistentListOf(folder))
        compose.mainClock.advanceTimeBy(100)
        ui.value = ui.value.copy(pinned = persistentListOf())
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText(folder.name).fetchSemanticsNodes().size)
    }
}
