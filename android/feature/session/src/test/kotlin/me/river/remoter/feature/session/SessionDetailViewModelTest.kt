package me.river.remoter.feature.session

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.net.SessionState
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

private const val LIVE = "rc-01k6b7y3m4n5p6q7r8s9t0v1w2"

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class Rig(scope: TestScope, id: String, liveOn: Boolean) {
        val clock = SchedulerClock(scope.testScheduler)
        val api = FixtureBackend(nowMs = clock::nowMs)
        val signer = FakeSigner(clock)
        val hub = SessionsHub(api, signer, clock)
        val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
        val monitor = ConnectionMonitor(api, FakeVpnNetworks(), clock)
        val live = LiveSync(api, monitor, hub).also { if (liveOn) it.start(scope.backgroundScope) }
        val vm = SessionDetailViewModel(id, api, hub, clock, monitor, store, live)
        val ui get() = vm.ui.value
    }

    // The view model ticks forever, so the scope is cancelled by hand or runTest never ends.
    private fun runRig(id: String = LIVE, liveOn: Boolean = true, block: suspend TestScope.(Rig) -> Unit) = runTest(main.dispatcher.scheduler) {
        val r = Rig(this, id, liveOn)
        try {
            block(r)
        } finally {
            r.vm.viewModelScope.cancel()
        }
    }

    @Test
    fun second_end_tap_asks_and_deletes_once() = runRig { r ->
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        r.signer.gate = gate
        r.vm.end()
        runCurrent()
        assertTrue("End shows as busy from the tap on", r.ui.ending)
        r.vm.end()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, r.signer.prompts.size)
        assertEquals(1, r.api.killCalls.size)
    }

    @Test
    fun cancelled_end_prompt_shows_no_error() = runRig { r ->
        runCurrent()
        r.signer.outcomes += FakeSigner.Next.Cancel
        r.vm.end()
        runCurrent()
        assertFalse(r.ui.ending)
        assertNull(r.ui.error)
    }

    @Test
    fun failed_end_keeps_error_for_retry() = runRig { r ->
        runCurrent()
        r.api.failWith = ErrorCode.Locked
        r.vm.end()
        runCurrent()
        assertFalse(r.ui.ending)
        assertEquals(AppError.Locked, r.ui.error)
        assertEquals(DetailAction.End, r.ui.failed)
        r.vm.clearError()
        assertNull(r.ui.error)
    }

    @Test
    fun unlisted_session_is_gone_not_loading() = runRig("rc-nope") { r ->
        runCurrent()
        assertTrue(r.ui.gone)
        assertNull(r.ui.session)
    }

    @Test
    fun a_session_that_ends_while_open_turns_gone() = runRig { r ->
        runCurrent()
        assertEquals(SessionState.Ready, r.ui.session?.state)
        r.api.endOnLaptop(LIVE)
        runCurrent()
        assertTrue("the live list says so at once", r.ui.gone)
        assertEquals(SessionState.Gone, r.ui.session?.state)
    }

    @Test
    fun no_live_stream_detail_polls() = runRig(liveOn = false) { r ->
        runCurrent()
        r.api.endOnLaptop(LIVE)
        advanceTimeBy(LIST_POLL_MS + 1)
        assertTrue(r.ui.gone)
        assertEquals(SessionState.Gone, r.ui.session?.state)
    }

    @Test
    fun an_unreachable_laptop_says_so_under_the_skeleton() = runRig(liveOn = false) { r ->
        r.api.unreachable = true
        runCurrent()
        assertTrue(r.ui.unreachable)
        assertFalse(r.ui.gone)
    }

}
