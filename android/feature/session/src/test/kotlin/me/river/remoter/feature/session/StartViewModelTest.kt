package me.river.remoter.feature.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.net.SpawnMode
import me.river.remoter.core.net.StuckReason
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.MemoryStore
import me.river.remoter.core.testing.SchedulerClock
import me.river.remoter.core.testing.SpawnScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val target = StartTarget("Projects/remoter", "remoter", isGit = true)

    private class Rig(scope: TestScope) {
        val clock = SchedulerClock(scope.testScheduler)
        val api = FixtureBackend(phaseStepMs = 700)
        val signer = FakeSigner(clock)
        val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
        val hub = SessionsHub(api, signer, clock)
        val vm = StartViewModel(api, signer, clock, ConnectionMonitor(api, FakeVpnNetworks(), clock), store, hub)
        val state get() = vm.ui.value.state
    }

    private fun runRig(block: suspend TestScope.(Rig) -> Unit) = runTest(main.dispatcher.scheduler) { block(Rig(this)) }

    @Test
    fun happy_path_reaches_ready_with_link() = runRig { r ->
        r.vm.open(target)
        assertEquals("remoter", r.vm.ui.value.form.name)
        r.vm.start()
        assertEquals(StartState.AwaitingFingerprint, r.state)
        runCurrent()
        val s = r.state as StartState.Starting
        assertEquals(listOf(true, false, false, false, false), s.steps.map { it.done })
        assertEquals("Start session in ~/Projects/remoter on r1v3r", r.signer.prompts.single().title)
        advanceTimeBy(701)
        assertEquals(2, (r.state as StartState.Starting).steps.count { it.done })
        advanceUntilIdle()
        val ready = r.state as StartState.Ready
        assertEquals("remoter", ready.name)
        assertNotNull("link", ready.claude)
        assertEquals(1, r.store.state.value!!.startTimesMs.size)
    }

    @Test
    fun second_tap_starts_nothing() = runRig { r ->
        r.vm.open(target)
        r.vm.start()
        r.vm.start()
        runCurrent()
        r.vm.start()
        advanceUntilIdle()
        assertEquals(1, r.signer.prompts.size)
        assertEquals(1, r.api.spawnCalls.size)
    }

    @Test
    fun cancelled_finger_back_to_idle() = runRig { r ->
        r.signer.outcomes += FakeSigner.Next.Cancel
        r.vm.open(target)
        r.vm.start()
        advanceUntilIdle()
        assertEquals(StartState.Idle, r.state)
        assertEquals(0, r.api.spawnCalls.size)
    }

    @Test
    fun unreachable_resends_same_bytes_for_30s() = runRig { r ->
        r.api.unreachable = true
        r.vm.open(target)
        r.vm.start()
        runCurrent()
        val na = r.state as StartState.NotAccepted
        assertEquals(AppError.Unreachable, na.error)
        assertEquals(30, r.vm.ui.value.retryLeftS)
        // advanceTimeBy stops just before the tick at 6 s
        advanceTimeBy(6_001)
        assertEquals(24, r.vm.ui.value.retryLeftS)

        r.api.unreachable = false
        r.vm.retry()
        advanceUntilIdle()
        assertTrue(r.state is StartState.Ready)
        assertEquals("prompts", 1, r.signer.prompts.size)
        assertEquals(2, r.api.spawnCalls.size)
        assertSame("same bytes", r.api.spawnCalls[0], r.api.spawnCalls[1])
    }

    @Test
    fun expired_window_needs_new_fingerprint() = runRig { r ->
        r.api.unreachable = true
        r.vm.open(target)
        r.vm.start()
        runCurrent()
        advanceTimeBy(30_500)
        val na = r.state as StartState.NotAccepted
        assertNull("needs finger", na.retryUntilMs)
        assertNull(r.vm.ui.value.retryLeftS)
        r.api.unreachable = false
        r.vm.retry()
        advanceUntilIdle()
        assertEquals(2, r.signer.prompts.size)
        assertTrue(r.api.spawnCalls[0].nonce != r.api.spawnCalls[1].nonce)
    }

    @Test
    fun server_refusal_needs_new_signature() = runRig { r ->
        r.api.failWith = ErrorCode.Locked
        r.vm.open(target)
        r.vm.start()
        advanceUntilIdle()
        assertEquals(StartState.NotAccepted(AppError.Locked, null), r.state)
    }

    @Test
    fun key_invalidated_goes_to_pair_again() = runRig { r ->
        r.signer.outcomes += FakeSigner.Next.Invalidate
        r.vm.open(target)
        r.vm.start()
        advanceUntilIdle()
        assertEquals(StartState.NotAccepted(AppError.KeyInvalidated, null), r.state)
    }

    @Test
    fun sse_drop_holds_stepper() = runRig { r ->
        r.api.spawnScript = SpawnScript.DropOnce
        r.vm.open(target)
        r.vm.start()
        runCurrent()
        advanceTimeBy(1_401)
        runCurrent()
        val s = r.state as StartState.Starting
        assertTrue("reconnecting", s.streamReconnecting)
        assertEquals("held", 3, s.steps.count { it.done })
        advanceUntilIdle()
        assertTrue(r.state is StartState.Ready)
    }

    @Test
    fun closing_mid_start_cancels_nothing() = runRig { r ->
        r.vm.open(target)
        r.vm.start()
        runCurrent()
        r.vm.close()
        advanceUntilIdle()
        assertTrue(r.state is StartState.Ready)
        assertNotNull(r.vm.ui.value.readySnack)
        r.vm.snackShown()
        assertNull(r.vm.ui.value.readySnack)
    }

    @Test
    fun ready_with_sheet_open_no_snack() = runRig { r ->
        r.vm.open(target)
        r.vm.start()
        advanceUntilIdle()
        assertNull(r.vm.ui.value.readySnack)
    }

    @Test
    fun stuck_carries_reason_and_tail() = runRig { r ->
        r.api.spawnScript = SpawnScript.Stuck
        r.vm.open(target)
        r.vm.start()
        advanceUntilIdle()
        val s = r.state as StartState.Stuck
        assertEquals(StuckReason.Untrusted, s.reason)
        assertTrue(s.tail.isNotEmpty())
    }

    @Test
    fun exited_carries_code() = runRig { r ->
        r.api.spawnScript = SpawnScript.Exited
        r.vm.open(target)
        r.vm.start()
        advanceUntilIdle()
        assertEquals(1, (r.state as StartState.Exited).code)
    }

    @Test
    fun slow_after_ten_seconds() = runRig { r ->
        r.api.spawnScript = SpawnScript.Slow
        r.vm.open(target)
        r.vm.start()
        runCurrent()
        advanceTimeBy(9_500)
        assertEquals(false, (r.state as StartState.Starting).slow)
        advanceTimeBy(1_000)
        assertEquals(true, (r.state as StartState.Starting).slow)
    }

    @Test
    fun invalid_name_waits_for_submit() = runRig { r ->
        r.vm.open(target)
        r.vm.setName("-bad")
        assertEquals("typing", false, r.vm.ui.value.form.nameInvalid)
        r.vm.start()
        assertEquals(true, r.vm.ui.value.form.nameInvalid)
        assertEquals(StartState.Idle, r.state)
        r.vm.setName("good")
        assertEquals(false, r.vm.ui.value.form.nameInvalid)
    }

    @Test
    fun end_from_stuck_deletes_and_resets() = runRig { r ->
        r.api.spawnScript = SpawnScript.Stuck
        r.vm.open(target)
        r.vm.setMode(SpawnMode.Worktree)
        r.vm.start()
        advanceUntilIdle()
        r.vm.endIt()
        advanceUntilIdle()
        assertEquals(1, r.api.killCalls.size)
        assertEquals("DELETE", r.api.killCalls[0].method)
        assertTrue(r.signer.prompts.last().title.startsWith("End remoter on r1v3r"))
        assertEquals(StartState.Idle, r.state)
    }
}
