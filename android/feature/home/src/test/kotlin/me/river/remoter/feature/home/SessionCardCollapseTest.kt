package me.river.remoter.feature.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.collections.immutable.persistentListOf
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.SessionSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SessionCardCollapseTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun gone_session_collapses_over_200ms() {
        val s = SessionSummary("rc-a", "zeta-session", "Projects/zeta", null, 0, SessionState.Ready, null, null)
        val base = homeStates.getValue("up_no_sessions")
        val ui = mutableStateOf(base.copy(sessions = persistentListOf(s), ending = setOf("rc-a")))
        compose.mainClock.autoAdvance = false
        compose.setContent { RemoterTheme(dark = true, reducedMotion = false) { HomeContent(ui.value, HomeCallbacks(), entrance = false) } }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("zeta-session").assertExists()
        ui.value = base
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("zeta-session").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("zeta-session").assertDoesNotExist()
    }
}
