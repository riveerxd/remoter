package me.river.remoter.feature.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.components.RemoterSheet
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.Names
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class StartRecoveryTest {
    @get:Rule val compose = createComposeRule()

    private val clock = me.river.remoter.core.net.Clock.System
    private val target = StartTarget("Projects/remoter", "remoter", true)

    private fun sheet(ui: StartUi, cb: StartCallbacks = StartCallbacks()) = compose.setContent {
        RemoterTheme(dark = true, reducedMotion = true) {
            Box(Modifier.fillMaxSize()) { RemoterSheet(true, {}) { StartContent(ui, cb) } }
        }
    }

    private fun ui(state: StartState, retry: Int? = null) = StartUi(
        open = true, target = target, form = StartForm(name = "remoter"), state = state,
        hostname = "r1v3r", typicalStartS = 12, retryLeftS = retry,
    )

    private class Vm(clock: me.river.remoter.core.net.Clock) {
        val api = FixtureBackend(phaseStepMs = 0)
        val signer = FakeSigner(clock, fingerMs = 0)
        val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
        val vm = StartViewModel(api, signer, clock, ConnectionMonitor(api, FakeVpnNetworks(), clock), store, SessionsHub(api, FakeSigner(clock), clock))
    }

    private fun withVm(block: (Vm) -> Unit) {
        val v = Vm(clock)
        try {
            block(v)
        } finally {
            v.vm.viewModelScope.cancel()
        }
    }

    @Test
    fun name_problem_matches_laptop_rules() {
        val names = listOf("", "   ", "ok", "-lead", "a-b", "a b", "a/b", "é", "._x", "x".repeat(48), "x".repeat(49), " spaced ", "a\tb")
        names.forEach { assertEquals(it, Names.isValidSessionName(it), sessionNameProblem(it) == null) }
        assertEquals("Can't start with a dash", sessionNameProblem("-lead"))
        assertEquals("Too long, 48 max", sessionNameProblem("x".repeat(49)))
        assertEquals("Can't use \"/\" in a name", sessionNameProblem("a/b"))
        assertEquals("Give it a name", sessionNameProblem("  "))
    }

    @Test
    fun refused_name_refocuses_and_says_why() = withVm { v ->
        v.vm.open(target)
        v.vm.setName("-bad")
        compose.setContent {
            val ui by v.vm.ui.collectAsStateWithLifecycle()
            RemoterTheme(dark = true, reducedMotion = true) {
                Box(Modifier.fillMaxSize()) {
                    RemoterSheet(ui.open, v.vm::close) {
                        StartContent(ui, StartCallbacks(onName = v.vm::setName, onStart = v.vm::start))
                    }
                }
            }
        }
        compose.onNodeWithText("Start session").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Can't start with a dash").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        // A second refusal of the same name still counts, so it shakes and refocuses again.
        compose.onNodeWithText("Start session").performClick()
        assertEquals(2, v.vm.ui.value.form.nameRefusals)
        assertEquals(StartState.Idle, v.vm.ui.value.state)
    }

    @Test
    fun retry_copy_says_the_timer_is_about_the_fingerprint() {
        sheet(ui(StartState.NotAccepted(AppError.Unreachable, 1), retry = 12))
        compose.onNodeWithText("Retry now").assertIsDisplayed()
        compose.onNodeWithText("No fingerprint needed for 12 s").assertIsDisplayed()
    }

    @Test
    fun vpn_off_on_form_offers_wireguard() {
        var opened = 0
        sheet(
            ui(StartState.NotAccepted(AppError.VpnOff, null)),
            StartCallbacks(errors = ErrorActions(onOpenWireGuard = { opened++ })),
        )
        compose.onNodeWithText("WireGuard is off").assertIsDisplayed()
        compose.onNodeWithText("Retry with fingerprint").assertIsDisplayed()
        compose.onNodeWithText("Turn on WireGuard").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun typical_time_sits_under_the_button() {
        sheet(ui(StartState.Idle))
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("new worktree")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("~12 s")).fetchSemanticsNodes().size)
        compose.onNodeWithText("Usually ready in about 12 s").assertIsDisplayed()
    }

    @Test
    fun starting_offers_cancel_start_from_the_first_second() {
        var ended = 0
        val steps = kotlinx.collections.immutable.persistentListOf(Step("Fingerprint", true))
        sheet(ui(StartState.Starting(SessionId("rc-1"), steps, 0, streamReconnecting = false, slow = false)), StartCallbacks(onEndIt = { ended++ }))
        compose.onNodeWithText("Cancel start").performClick()
        assertEquals(1, ended)
    }

    @Test
    fun cancel_start_kills_the_session_and_resets() = withVm { v ->
        val finger = kotlinx.coroutines.CompletableDeferred<Unit>()
        // Phases a minute apart keep it on Starting for the whole test.
        v.api.phaseStepMs = 60_000
        v.vm.open(target)
        v.vm.start()
        compose.waitUntil(3_000) { v.vm.ui.value.state is StartState.Starting }
        v.signer.gate = finger
        v.vm.endIt()
        assertEquals("spinner from the fingerprint on", true, v.vm.ui.value.ending)
        v.vm.endIt()
        finger.complete(Unit)
        compose.waitUntil(3_000) { v.vm.ui.value.state == StartState.Idle }
        assertEquals(1, v.api.killCalls.size)
        assertEquals(false, v.vm.ui.value.ending)
    }

    @Test
    fun failed_end_keeps_sheet_open_with_reason() = withVm { v ->
        v.api.spawnScript = me.river.remoter.core.testing.SpawnScript.Stuck
        v.vm.open(target)
        v.vm.start()
        compose.waitUntil(3_000) { v.vm.ui.value.state is StartState.Stuck }
        v.api.unreachable = true
        v.vm.endIt()
        compose.waitUntil(3_000) { !v.vm.ui.value.ending }
        val u = v.vm.ui.value
        assertEquals(true, u.open)
        assertEquals(true, u.state is StartState.Stuck)
        assertEquals(AppError.Unreachable, u.endError)
        // Trying again clears the old reason and can succeed.
        v.api.unreachable = false
        v.vm.endIt()
        assertNull(v.vm.ui.value.endError)
        compose.waitUntil(3_000) { v.vm.ui.value.state == StartState.Idle }
    }

    @Test
    fun retry_is_ignored_while_the_kill_is_in_flight() = withVm { v ->
        val finger = kotlinx.coroutines.CompletableDeferred<Unit>()
        v.api.spawnScript = me.river.remoter.core.testing.SpawnScript.Stuck
        v.vm.open(target)
        v.vm.start()
        compose.waitUntil(3_000) { v.vm.ui.value.state is StartState.Stuck }
        v.signer.gate = finger
        v.vm.endIt()
        v.vm.retry()
        finger.complete(Unit)
        compose.waitUntil(3_000) { v.vm.ui.value.state == StartState.Idle }
        assertEquals("no second start", 1, v.api.spawnCalls.size)
        assertEquals(1, v.api.killCalls.size)
    }

    @Test
    fun end_error_shows_under_the_stuck_sheet() {
        sheet(ui(StartState.Stuck(SessionId("rc-1"), null, kotlinx.collections.immutable.persistentListOf())).copy(endError = AppError.Unreachable))
        compose.onNodeWithText("Couldn't end it. Couldn't reach r1v3r").assertIsDisplayed()
        compose.onNodeWithText("End session").assertIsDisplayed()
    }
}
