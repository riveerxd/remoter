package me.river.remoter.feature.session

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.ApiException
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.RemoterJson
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.Signed
import me.river.remoter.core.net.SpawnMode
import me.river.remoter.core.net.SpawnRequest
import me.river.remoter.core.testing.FixtureBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Start in a new worktree on the folder busy screen used to crash: the error layout, still fading
// out, cast the new state to NotAccepted. walks every way off an error screen, stopped mid fade
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
        StartState.Starting(SessionId("rc-01k6b7y3m4n5p6q7r8s9t0v1w2"), persistentListOf(), 0L, streamReconnecting = false, slow = false),
    )

    @Test
    fun leave_error_mid_fade() {
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

    // like the real laptop: refuses a second same folder start, lets a worktree through
    @Test
    fun demo_laptop_refuses_busy_folder() = runTest {
        val api = FixtureBackend(oneSessionPerFolder = true, phaseStepMs = 1)
        val busy = api.sessions().sessions.first { it.state != SessionState.Exited }
        fun body(mode: SpawnMode) = RemoterJson.encodeToString(
            SpawnRequest.serializer(), SpawnRequest(busy.path, "x", mode),
        ).toByteArray()
        fun signed(b: ByteArray, nonce: String) = Signed("POST", "/v1/sessions", b, "dev", 0, nonce, ByteArray(0))
        val refused = runCatching { api.spawn(signed(body(SpawnMode.SameDir), "n1")) }.exceptionOrNull()
        val e = refused as ApiException
        assertEquals(ErrorCode.FolderBusy, e.body.code)
        assertTrue("names the session", e.body.sessions.orEmpty().any { it.id == busy.id })
        api.spawn(signed(body(SpawnMode.Worktree), "n2"))
    }
}
