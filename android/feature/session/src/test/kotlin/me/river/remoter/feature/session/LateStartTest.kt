package me.river.remoter.feature.session

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.Event
import me.river.remoter.core.net.IdEvent
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.SessionDetail
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.StuckReason
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.MemoryStore
import me.river.remoter.core.testing.SchedulerClock
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A start went red at 20 s; claude connected at 31 s. Once the sheet
 * showed Stuck it gave up on the session, so a dropped stream meant it never
 * learned about the Ready that followed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LateStartTest {
    @get:Rule val main = MainDispatcherRule()

    /** The stream says Stuck and then drops; a poll afterwards finds the session as the laptop now has it. */
    private fun rig(reason: StuckReason, nowState: SessionState, block: suspend kotlinx.coroutines.test.TestScope.(StartViewModel) -> Unit) =
        runTest(main.dispatcher.scheduler) {
            val clock = SchedulerClock(testScheduler)
            val real = FixtureBackend(phaseStepMs = 0)
            val api = object : RemoterApi by real {
                override fun events(id: String, viewToken: String?, lastEventId: String?): Flow<IdEvent> = flow {
                    if (lastEventId == null) emit(IdEvent("1", Event.StateEvent(SessionState.Stuck, reason, null)))
                    throw java.io.IOException("stream dropped")
                }
                override suspend fun session(id: String, viewToken: String): SessionDetail =
                    real.session(id, viewToken).let { it.copy(session = it.session.copy(state = nowState)) }
            }
            val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
            val vm = StartViewModel(api, FakeSigner(clock), clock, ConnectionMonitor(api, FakeVpnNetworks(), clock), store, SessionsHub(api, FakeSigner(clock), clock))
            try { block(vm) } finally { vm.viewModelScope.cancel() }
        }

    @Test
    fun late_start_after_timeout_ends_ready() = rig(StuckReason.Timeout, SessionState.Ready) { vm ->
        vm.open(StartTarget("Projects/remoter", "remoter", true))
        vm.start()
        runCurrent()
        assertTrue(vm.ui.value.state.toString(), vm.ui.value.state is StartState.Stuck)
        advanceTimeBy(StartViewModel.POLL_MS + 100)
        runCurrent()
        assertTrue(vm.ui.value.state.toString(), vm.ui.value.state is StartState.Ready)
    }

    @Test
    fun a_real_failure_stays_stuck_and_stops_polling() = rig(StuckReason.Untrusted, SessionState.Ready) { vm ->
        vm.open(StartTarget("Projects/remoter", "remoter", true))
        vm.start()
        runCurrent()
        advanceTimeBy(StartViewModel.POLL_MS * 3)
        runCurrent()
        assertTrue(vm.ui.value.state.toString(), vm.ui.value.state is StartState.Stuck)
    }
}
