package me.river.remoter.feature.session

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.AppError
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Start in a new worktree on the folder busy screen used to kill the app: the error layout, still
 * fading out, cast the new state to NotAccepted. Every way off an error screen is walked here with
 * the clock stopped halfway through the fade, where it crashed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LeaveErrorTest {
    @get:Rule val compose = createComposeRule()

    private val target = StartTarget("Projects/remoter", "remoter", isGit = true)
    private val errors = listOf(
        AppError.FolderBusy(emptyList()),
        AppError.Untrusted,
        AppError.ConversationOpen,
        AppError.Locked,
        AppError.SessionCap(emptyList()),
    )
    private val next = listOf(
        StartState.Idle,
        StartState.AwaitingFingerprint,
        StartState.Sending(retryUntilMs = Long.MAX_VALUE),
        StartState.Starting(SessionId("rc-01k6b7y3m4n5p6q7r8s9t0v1w2"), kotlinx.collections.immutable.persistentListOf(), 0L, streamReconnecting = false, slow = false),
    )

    @Test
    fun leaving_an_error_mid_fade_never_crashes() {
        val ui = mutableStateOf(StartUi(open = true, target = target))
        compose.setContent { RemoterTheme(dark = true, reducedMotion = false) { StartContent(ui.value, StartCallbacks()) } }
        for (error in errors) {
            for (to in next) {
                ui.value = ui.value.copy(state = StartState.NotAccepted(error, null))
                compose.waitForIdle()
                compose.mainClock.autoAdvance = false
                ui.value = ui.value.copy(state = to)
                compose.mainClock.advanceTimeBy(120)
                compose.mainClock.advanceTimeBy(120)
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                if (to is StartState.Idle) compose.onNodeWithText("Start session").assertExists()
            }
        }
    }

    /** The demo's laptop refuses a second same-folder start the way the real one does, and lets a worktree through. */
    @Test
    fun demo_laptop_refuses_busy_folder() = kotlinx.coroutines.test.runTest {
        val api = me.river.remoter.core.testing.FixtureBackend(oneSessionPerFolder = true, phaseStepMs = 1)
        val busy = api.sessions().sessions.first { it.state != me.river.remoter.core.net.SessionState.Exited }
        fun body(mode: me.river.remoter.core.net.SpawnMode) = me.river.remoter.core.net.RemoterJson.encodeToString(
            me.river.remoter.core.net.SpawnRequest.serializer(), me.river.remoter.core.net.SpawnRequest(busy.path, "x", mode),
        ).toByteArray()
        fun signed(b: ByteArray, nonce: String) = me.river.remoter.core.net.Signed("POST", "/v1/sessions", b, "dev", 0, nonce, ByteArray(0))
        val refused = runCatching { api.spawn(signed(body(me.river.remoter.core.net.SpawnMode.SameDir), "n1")) }.exceptionOrNull()
        val e = refused as me.river.remoter.core.net.ApiException
        org.junit.Assert.assertEquals(me.river.remoter.core.net.ErrorCode.FolderBusy, e.body.code)
        org.junit.Assert.assertTrue("names the session in the way", e.body.sessions.orEmpty().any { it.id == busy.id })
        api.spawn(signed(body(me.river.remoter.core.net.SpawnMode.Worktree), "n2"))
    }
}
