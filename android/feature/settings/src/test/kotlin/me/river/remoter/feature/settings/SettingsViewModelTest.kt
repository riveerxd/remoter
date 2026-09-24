package me.river.remoter.feature.settings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.MemoryStore
import me.river.remoter.core.testing.SchedulerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Lock and unpair failed in silence: the view model set a snack and an error
 * that no screen ever showed, and a double tap on Lock sent two requests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private lateinit var store: MemoryStore
    private lateinit var signer: FakeSigner

    private fun rig(api: FixtureBackend, block: suspend kotlinx.coroutines.test.TestScope.(SettingsViewModel) -> Unit) = runTest(main.dispatcher.scheduler) {
        val clock = SchedulerClock(testScheduler)
        val monitor = ConnectionMonitor(api, FakeVpnNetworks(), clock)
        store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
        signer = FakeSigner(clock)
        val vm = SettingsViewModel(store, api, monitor, signer, clock)
        try { block(vm) } finally { vm.viewModelScope.cancel() }
    }


    @Test
    fun a_double_press_on_lock_sends_one_request() {
        val api = FixtureBackend()
        rig(api) { vm ->
            vm.lockLaptop()
            vm.lockLaptop()
            runCurrent()
            assertEquals(1, api.lockCalls)
            assertTrue(vm.ui.value.locked)
        }
    }

    @Test
    fun a_failed_lock_says_why_next_to_the_button() {
        val api = FixtureBackend(failWith = ErrorCode.AgentDown)
        rig(api) { vm ->
            vm.lockLaptop()
            runCurrent()
            assertEquals(AppError.AgentDown, vm.ui.value.lockError)
            assertTrue("the button comes back for another try", !vm.ui.value.busy)
        }
    }

    @Test
    fun a_failed_unpair_says_why_and_lets_you_retry() {
        val api = FixtureBackend(failWith = ErrorCode.AgentDown)
        rig(api) { vm ->
            vm.unpair()
            runCurrent()
            assertEquals(AppError.AgentDown, vm.ui.value.unpairError)
            assertTrue(!vm.ui.value.unpairing)
        }
    }

}
