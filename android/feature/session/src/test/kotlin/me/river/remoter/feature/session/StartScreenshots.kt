package me.river.remoter.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.collections.immutable.persistentListOf
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.shots.Variant
import me.river.remoter.core.design.shots.shot
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ClaudeLink
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.SessionSummary
import me.river.remoter.core.net.StuckReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val target = StartTarget("Projects/remoter", "remoter", isGit = true)
private val id = SessionId("rc-01k6b7y3m4n5p6q7r8s9t0v1w2")
private val link = ClaudeLink("session_1", "https://claude.ai/code/session_1", null)
private val tail = persistentListOf("·✔︎· Connecting · remoter · main", "Error: Workspace not trusted. Please run `claude` in ~/Projects/remoter first")
private fun steps(done: Int) = listOf("Fingerprint", "Accepted by r1v3r", "Opening the terminal", "Launching Claude", "Connecting Remote Control")
    .mapIndexed { i, l -> Step(l, i < done) }.let { kotlinx.collections.immutable.persistentListOf(*it.toTypedArray()) }

private fun ui(state: StartState, retry: Int? = null, typical: Int? = 6) = StartUi(
    open = true, target = target, form = StartForm(name = "remoter"), state = state,
    hostname = "r1v3r", typicalStartS = typical, retryLeftS = retry, elapsedS = 7,
)

internal val startStates: List<Pair<String, StartUi>> = listOf(
    "idle" to ui(StartState.Idle),
    "idle_first_starts" to ui(StartState.Idle, typical = null),
    "idle_name_invalid" to ui(StartState.Idle).copy(form = StartForm(name = "-bad", nameInvalid = true)),
    "awaiting_fingerprint" to ui(StartState.AwaitingFingerprint),
    "sending" to ui(StartState.Sending(0)),
    "not_accepted_retry" to ui(StartState.NotAccepted(AppError.Unreachable, 1), retry = 24),
    "not_accepted_needs_finger" to ui(StartState.NotAccepted(AppError.Unreachable, null)),
    "not_accepted_vpn_off" to ui(StartState.NotAccepted(AppError.VpnOff, null)),
    "not_accepted_locked" to ui(StartState.NotAccepted(AppError.Locked, null)),
    "not_accepted_security" to ui(StartState.NotAccepted(AppError.Security(ErrorCode.SigInvalid, "01K6B7Y3M4N5P6Q7R8S9T0V1X1"), null)),
    "not_accepted_rate_limited" to ui(StartState.NotAccepted(AppError.RateLimited(17), null)),
    "not_accepted_cap" to ui(StartState.NotAccepted(AppError.SessionCap(listOf(SessionSummary("rc-1", "api", "Projects/api", null, 0, SessionState.Ready, null, null))), null)),
    "not_accepted_clock" to ui(StartState.NotAccepted(AppError.ClockSkew(47_000), null)),
    "not_accepted_untrusted" to ui(StartState.NotAccepted(AppError.Untrusted, null)),
    "not_accepted_server" to ui(StartState.NotAccepted(AppError.Server(ErrorCode.SpawnFailed, "01K6B7Y3M4N5P6Q7R8S9T0V1X1"), null)),
    "not_accepted_desktop_down" to ui(StartState.NotAccepted(AppError.Server(ErrorCode.DesktopDown, "01K6B7Y3M4N5P6Q7R8S9T0V1X1"), null)),
    "not_accepted_pair_again" to ui(StartState.NotAccepted(AppError.KeyInvalidated, null)),
    "starting_accepted" to ui(StartState.Starting(id, steps(2), 0, streamReconnecting = false, slow = false)),
    "starting_reconnecting" to ui(StartState.Starting(id, steps(3), 0, streamReconnecting = true, slow = false)),
    "starting_slow" to ui(StartState.Starting(id, steps(4), 0, streamReconnecting = false, slow = true)).copy(elapsedS = 12),
    "ready" to ui(StartState.Ready(id, "remoter", link)),
    "direct_starting_accepted" to ui(StartState.Starting(id, steps(2), 0, streamReconnecting = false, slow = false)).copy(direct = true),
    "direct_ready" to ui(StartState.Ready(id, "remoter", link)).copy(direct = true),
    "direct_stuck" to ui(StartState.Stuck(id, StuckReason.Untrusted, tail)).copy(direct = true),
    "ready_no_link_yet" to ui(StartState.Ready(id, "remoter", null)),
    "stuck" to ui(StartState.Stuck(id, StuckReason.Untrusted, tail)),
    "exited" to ui(StartState.Exited(id, 1, tail)),
    "stuck_ending" to ui(StartState.Stuck(id, StuckReason.Untrusted, tail)).copy(ending = true),
    "stuck_end_failed" to ui(StartState.Stuck(id, StuckReason.Untrusted, tail)).copy(endError = AppError.Unreachable),
)

// over a stand-in for home so anchoring and scrim show
@Composable
internal fun SheetBody(u: StartUi) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Remoter.colors.bg)) {
        Column(Modifier.padding(24.dp)) {
            androidx.compose.material3.Text("r1v3r", style = Remoter.type.title, color = Remoter.colors.text)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 80.dp))
            androidx.compose.material3.Text("home behind the sheet", style = Remoter.type.body, color = Remoter.colors.textMuted)
        }
        me.river.remoter.core.design.components.RemoterSheet(visible = true, onDismiss = {}) {
            StartContent(u, StartCallbacks(errors = ErrorActions(onLockLaptop = {}, onPairAgain = {}, onOpenDateSettings = {}, onOpenWireGuard = {}, onEnd = {})))
        }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// S25 Ultra: 1440x3120 px is 411x891 dp
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class StartScreenshots(private val v: Variant) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = Variant.params()
    }

    @get:Rule val compose = createComposeRule()

    @Test fun idle() = one("idle")
    @Test fun idle_first_starts() = one("idle_first_starts")
    @Test fun idle_name_invalid() = one("idle_name_invalid")
    @Test fun awaiting_fingerprint() = one("awaiting_fingerprint")
    @Test fun sending() = one("sending")
    @Test fun not_accepted_retry() = one("not_accepted_retry")
    @Test fun not_accepted_needs_finger() = one("not_accepted_needs_finger")
    @Test fun not_accepted_vpn_off() = one("not_accepted_vpn_off")
    @Test fun not_accepted_locked() = one("not_accepted_locked")
    @Test fun not_accepted_security() = one("not_accepted_security")
    @Test fun not_accepted_rate_limited() = one("not_accepted_rate_limited")
    @Test fun not_accepted_cap() = one("not_accepted_cap")
    @Test fun not_accepted_clock() = one("not_accepted_clock")
    @Test fun not_accepted_untrusted() = one("not_accepted_untrusted")
    @Test fun not_accepted_server() = one("not_accepted_server")
    @Test fun not_accepted_desktop_down() = one("not_accepted_desktop_down")
    @Test fun not_accepted_pair_again() = one("not_accepted_pair_again")
    @Test fun starting_accepted() = one("starting_accepted")
    @Test fun starting_reconnecting() = one("starting_reconnecting")
    @Test fun starting_slow() = one("starting_slow")
    @Test fun ready() = one("ready")
    @Test fun direct_starting_accepted() = one("direct_starting_accepted")
    @Test fun direct_ready() = one("direct_ready")
    @Test fun direct_stuck() = one("direct_stuck")
    @Test fun stuck_ending() = one("stuck_ending")
    @Test fun stuck_end_failed() = one("stuck_end_failed")
    @Test fun ready_no_link_yet() = one("ready_no_link_yet")
    @Test fun stuck() = one("stuck")
    @Test fun exited() = one("exited")

    private fun one(name: String) {
        val u = startStates.first { it.first == name }.second
        compose.shot("start_$name", v) { SheetBody(u) }
    }
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReadyRingFrames {
    @get:Rule val compose = createComposeRule()

    @Test
    fun ring_closes_over_300ms() {
        val state = mutableStateOf(ui(StartState.Starting(id, steps(5), 0, false, false)))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = false) { SheetBody(state.value) }
        }
        compose.mainClock.advanceTimeBy(500)
        state.value = ui(StartState.Ready(id, "remoter", link))
        // one frame for the state to land, then 50 ms steps named by time since Ready
        compose.mainClock.advanceTimeByFrame()
        var t = 0
        while (t <= 300) {
            compose.onRoot().captureRoboImage("src/test/snapshots/ready_ring_${"%03d".format(t)}ms.png")
            compose.mainClock.advanceTimeBy(50)
            t += 50
        }
    }
}
