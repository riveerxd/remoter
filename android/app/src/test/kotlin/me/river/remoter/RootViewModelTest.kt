package me.river.remoter

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.crypto.Attester
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.SessionSummary
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.MemoryStore
import me.river.remoter.core.testing.SchedulerClock
import me.river.remoter.feature.session.LiveSync
import me.river.remoter.feature.session.SessionsHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val s = SessionSummary("rc-01k6b7y3m4n5p6q7r8s9t0v1w2", "remoter", "Projects/remoter", null, 0, SessionState.Ready, null, null)

    private class Rig(scope: TestScope) {
        val clock = SchedulerClock(scope.testScheduler)
        val api = FixtureBackend(nowMs = clock::nowMs)
        val signer = FakeSigner(clock)
        val hub = SessionsHub(api, signer, clock)
        val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
        val monitor = ConnectionMonitor(api, FakeVpnNetworks(), clock)
        val live = LiveSync(api, monitor, hub)
        val vm = RootViewModel(monitor, store, clock, api, hub, Attester { null }, live)
    }

    private fun runRig(block: suspend TestScope.(Rig) -> Unit) = runTest(main.dispatcher.scheduler) {
        val r = Rig(this)
        try {
            block(r)
        } finally {
            r.vm.viewModelScope.cancel()
        }
    }

    @Test
    fun double_lock_sends_one_request() = runRig { r ->
        r.vm.lockLaptop()
        assertTrue(r.vm.locking.value)
        r.vm.lockLaptop()
        runCurrent()
        assertEquals(1, r.api.lockCalls)
        assertFalse(r.vm.locking.value)
        assertEquals("r1v3r is locked", r.vm.snack.value)
    }

    @Test
    fun failed_lock_clears_busy() = runRig { r ->
        r.api.unreachable = true
        r.vm.lockLaptop()
        runCurrent()
        assertFalse(r.vm.locking.value)
        assertEquals("Couldn't reach r1v3r", r.vm.snack.value)
    }

    @Test
    fun end_spins_the_row_then_drops_it() = runRig { r ->
        val gate = CompletableDeferred<Unit>()
        r.signer.gate = gate
        r.vm.end(s)
        runCurrent()
        assertTrue(s.id in r.vm.ending.value)
        r.vm.end(s)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, r.signer.prompts.size)
        assertEquals(1, r.api.killCalls.size)
        assertFalse(s.id in r.vm.ending.value)
        assertTrue(s.id in r.vm.ended.value)
    }

    @Test
    fun cancelled_end_keeps_row() = runRig { r ->
        r.signer.outcomes += FakeSigner.Next.Cancel
        r.vm.end(s)
        runCurrent()
        assertFalse(s.id in r.vm.ending.value)
        assertFalse(s.id in r.vm.ended.value)
        assertEquals(null, r.vm.snack.value)
    }

    @Test
    fun wiped_key_on_end_is_reported() = runRig { r ->
        r.signer.outcomes += FakeSigner.Next.Invalidate
        r.vm.end(s)
        runCurrent()
        assertNotNull(r.vm.snack.value)
        assertTrue(r.vm.snack.value!!.contains("Pair again"))
    }

    @Test
    fun failed_end_says_why() = runRig { r ->
        r.api.failWith = ErrorCode.Locked
        r.vm.end(s)
        runCurrent()
        assertEquals("Couldn't end remoter: r1v3r is locked", r.vm.snack.value)
        assertFalse(s.id in r.vm.ended.value)
    }

    @Test
    fun live_stream_only_in_foreground() = runRig { r ->
        r.vm.foreground()
        runCurrent()
        assertEquals(1, r.api.liveCalls)
        assertTrue(r.live.connected.value)
        val first = r.hub.sessions.value!!.first()
        r.vm.background()
        runCurrent()
        assertFalse(r.live.connected.value)
        r.api.endOnLaptop(first.id)
        runCurrent()
        assertTrue("nothing listens in the background", r.hub.sessions.value!!.any { it.id == first.id })
        r.vm.foreground()
        runCurrent()
        assertEquals(2, r.api.liveCalls)
        assertTrue(r.hub.sessions.value!!.none { it.id == first.id })
    }
}
