package me.river.remoter.feature.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.components.RemoterSheet
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class StartUiTest {
    @get:Rule val compose = createComposeRule()

    private val clock = me.river.remoter.core.net.Clock.System
    // Robolectric's paused looper doesn't run coroutine delays while the compose clock advances
    private val api = FixtureBackend(phaseStepMs = 0)
    private val signer = FakeSigner(clock, fingerMs = 0)
    private val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
    private val vm = StartViewModel(api, signer, clock, ConnectionMonitor(api, FakeVpnNetworks(), clock), store, SessionsHub(api, FakeSigner(clock), clock))

    private fun show() {
        vm.open(StartTarget("Projects/remoter", "remoter", true))
        compose.setContent {
            val ui by vm.ui.collectAsStateWithLifecycle()
            RemoterTheme(dark = true, reducedMotion = true) {
                Box(Modifier.fillMaxSize()) {
                    RemoterSheet(ui.open, vm::close) {
                        StartContent(ui, StartCallbacks(onStart = vm::start, onRetry = vm::retry, onClose = vm::close))
                    }
                }
            }
        }
    }

    @Test
    fun start_ignores_a_second_tap() {
        val finger = kotlinx.coroutines.CompletableDeferred<Unit>()
        signer.gate = finger
        show()
        compose.onNodeWithText("Start session").performClick()
        // prompt is up, the button still gets hammered
        compose.onNodeWithText("Start session").performClick()
        compose.onNodeWithText("Start session").performClick()
        finger.complete(Unit)
        compose.waitUntil(5_000) { vm.ui.value.state is StartState.Ready }
        assertEquals(1, signer.prompts.size)
        assertEquals(1, api.spawnCalls.size)
    }

    @Test
    fun cancelled_finger_resets() {
        signer.outcomes += FakeSigner.Next.Cancel
        show()
        compose.onNodeWithText("Start session").performClick()
        compose.waitUntil(3_000) { signer.prompts.size == 1 && vm.ui.value.state == StartState.Idle }
        compose.onNodeWithText("Start session").assertIsDisplayed()
        compose.onNodeWithText("Couldn't reach r1v3r", substring = true).let { runCatching { it.assertIsDisplayed() }.isFailure.let { gone -> assert(gone) } }
    }

    @Test
    fun retry_resends_same_bytes() {
        api.unreachable = true
        show()
        compose.onNodeWithText("Start session").performClick()
        compose.waitUntil(3_000) { vm.ui.value.retryLeftS != null }
        compose.onNodeWithText("Retry now", substring = true).assertIsDisplayed()
        api.unreachable = false
        compose.onNodeWithText("Retry now", substring = true).performClick()
        compose.waitUntil(5_000) { vm.ui.value.state is StartState.Ready }
        assertEquals(1, signer.prompts.size)
        assertSame(api.spawnCalls[0], api.spawnCalls[1])
    }
}
