package me.river.remoter.feature.session

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
class WorktreeHeaderTest {
    @get:Rule val compose = createComposeRule()

    private val s = SessionSummary("rc-a", "remoter", "Projects/remoter", null, 0, SessionState.Ready, null, null)

    private fun show(s: SessionSummary) = compose.setContent {
        RemoterTheme(dark = true, reducedMotion = true) { SessionDetailContent(DetailUi(s, nowMs = 60_000, hostname = "r1v3r"), {}, {}, {}, {}) }
    }

    @Test
    fun the_header_names_the_worktree_under_the_path() {
        show(s.copy(worktree = "bright-otter-3f2a"))
        compose.onNodeWithText("~/Projects/remoter").assertExists()
        compose.onNodeWithText("worktree · bright-otter-3f2a").assertExists()
    }

    @Test
    fun no_worktree_no_line() {
        show(s)
        compose.onNodeWithText("worktree", substring = true).assertDoesNotExist()
    }
}
