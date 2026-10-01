package me.river.remoter.feature.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
class WorktreeBannerTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_790_620_000_000
    private val plain = SessionSummary("rc-a", "remoter", "Projects/remoter", null, now - 60_000, SessionState.Ready, null, null)

    private fun show(vararg s: SessionSummary) {
        val ui = homeStates.getValue("up_no_sessions").copy(sessions = persistentListOf(*s), nowMs = now)
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { HomeContent(ui, HomeCallbacks(), false) } }
    }

    /** Two sessions of one folder, one in a worktree, looked the same on the banner. */
    @Test
    fun a_worktree_session_says_which_worktree() {
        show(plain.copy(id = "rc-b", worktree = "bright-otter-3f2a"))
        compose.onNodeWithText("worktree · bright-otter-3f2a").assertExists()
        compose.onNodeWithContentDescription("Session remoter, ready, running 1 minute, in worktree bright-otter-3f2a").assertExists()
    }

    @Test
    fun a_plain_session_has_no_worktree_line() {
        show(plain)
        compose.onNodeWithText("worktree", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Session remoter, ready, running 1 minute").assertExists()
    }
}
