package me.river.remoter.feature.session

import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.collections.immutable.persistentListOf
import me.river.remoter.core.design.shots.Variant
import me.river.remoter.core.design.shots.shot
import me.river.remoter.core.net.ClaudeLink
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.SessionSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val now = 1_790_620_000_000
private val sess = SessionSummary("rc-a", "remoter", "Projects/remoter", null, now - 4_987_000, SessionState.Ready, null, null, ClaudeLink("s", "https://claude.ai/code/s", null))
private val tail = persistentListOf(
    "·✔︎· Connected · remoter · main",
    "    Capacity: 1/32 · New sessions will be created in the current directory",
    "    remoter",
    "Continue coding in the Claude mobile app or https://claude.ai/code?environment=env_01Kd3fPzQw8nVb2sLxRt6uYm",
)

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DetailScreenshots(private val v: Variant) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = Variant.params()
    }

    @get:Rule val compose = createComposeRule()

    private fun one(k: String, ui: DetailUi) = compose.shot("detail_$k", v) { SessionDetailContent(ui, {}, {}, {}, {}) }

    @Test fun shown() = one("shown", DetailUi(sess, tail, now - 2_000, nowMs = now, hostname = "r1v3r"))
    @Test fun starting() = one("starting", DetailUi(sess.copy(state = SessionState.Starting, claude = null), nowMs = now, hostname = "r1v3r"))
    @Test fun worktree() = one("worktree", DetailUi(sess.copy(worktree = "bright-otter-3f2a"), tail, now - 2_000, nowMs = now, hostname = "r1v3r"))
    @Test fun exited() = one("exited", DetailUi(sess.copy(state = SessionState.Exited, exitCode = 1, claude = null), tail, now, nowMs = now, hostname = "r1v3r"))
}
