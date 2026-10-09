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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartEdgeCasesTest {
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

    // Worktree after a failed send, then Retry, used to resend the old same folder bytes
    @Test
    fun edit_after_failure_drops_signed_bytes() = runRig { r ->
        r.api.unreachable = true
        r.vm.open(target)
        r.vm.start()
        runCurrent()
        assertTrue(r.state is StartState.NotAccepted)
        assertEquals(1, r.api.spawnCalls.size)
        r.vm.setMode(me.river.remoter.core.net.SpawnMode.Worktree)
        assertEquals(StartState.Idle, r.state)
        r.api.unreachable = false
        r.vm.retry()
        runCurrent()
        assertEquals("nothing old goes out", 1, r.api.spawnCalls.size)
        r.vm.start()
        runCurrent()
        assertEquals(2, r.signer.prompts.size)
        assertTrue("old bytes", r.api.spawnCalls.last().body.decodeToString().contains("\"worktree\""))
    }

    // the name used to change under the fingerprint prompt
    @Test
    fun form_frozen_while_request_out() = runRig { r ->
        r.vm.open(target)
        r.vm.start()
        assertEquals(StartState.AwaitingFingerprint, r.state)
        r.vm.setName("something else")
        r.vm.setMode(me.river.remoter.core.net.SpawnMode.Worktree)
        assertEquals("remoter", r.vm.ui.value.form.name)
        assertEquals(me.river.remoter.core.net.SpawnMode.SameDir, r.vm.ui.value.form.mode)
        advanceUntilIdle()
    }

    @Test
    fun open_conversation_hands_off() = runRig { r ->
        val conv = me.river.remoter.core.net.Conversation("c82d8b5c-edd4-453e-8d59-4748ff325c03", "fix the banner", null, 0, 0, null, open = false)
        r.vm.open(target)
        advanceUntilIdle()
        r.vm.selectResume(conv)
        r.api.failWith = me.river.remoter.core.net.ErrorCode.ConversationOpen
        r.vm.start()
        runCurrent()
        assertEquals(AppError.ConversationOpen, (r.state as StartState.NotAccepted).error)
        r.api.failWith = null
        r.vm.handoffInstead()
        runCurrent()
        assertEquals(2, r.signer.prompts.size)
        val body = r.api.spawnCalls.last().body.decodeToString()
        assertTrue(body, body.contains("\"handoff\":\"${conv.id}\"") && !body.contains("\"resume\""))
        advanceUntilIdle()
    }

    @Test
    fun untrusted_retries_plain() = runRig { r ->
        r.api.spawnScript = me.river.remoter.core.testing.SpawnScript.Stuck
        r.vm.open(target)
        r.vm.start()
        advanceUntilIdle()
        assertEquals(me.river.remoter.core.net.StuckReason.Untrusted, (r.state as StartState.Stuck).reason)
        r.api.spawnScript = me.river.remoter.core.testing.SpawnScript.Ready
        r.vm.retry()
        runCurrent()
        assertEquals(2, r.api.spawnCalls.size)
        assertFalse(r.api.spawnCalls.last().body.decodeToString().contains("trust"))
        advanceUntilIdle()
        assertTrue(r.state is StartState.Ready)
    }

    // End on a quick laptop left a dead Gone screen: the gone landed before the call returned.
    // here the laptop says gone from inside the call, so the race always happens
    @Test
    fun quick_end_goes_home() = runTest(main.dispatcher.scheduler) {
        val clock = SchedulerClock(testScheduler)
        val fixture = FixtureBackend(nowMs = clock::nowMs)
        lateinit var hub: SessionsHub
        val api = object : me.river.remoter.core.net.RemoterApi by fixture {
            override suspend fun kill(signed: me.river.remoter.core.net.Signed) {
                fixture.kill(signed)
                hub.publish(fixture.sessions().sessions)
            }
        }
        hub = SessionsHub(api, FakeSigner(clock), clock)
        val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
        val monitor = ConnectionMonitor(api, FakeVpnNetworks(), clock)
        val live = LiveSync(api, monitor, hub)
        val id = fixture.sessions().sessions.first { it.state == me.river.remoter.core.net.SessionState.Ready }.id
        val vm = SessionDetailViewModel(id, api, hub, clock, monitor, store, live)
        try {
            hub.refresh()
            runCurrent()
            val home = backgroundScope.async { vm.goHome.first() }
            vm.end()
            runCurrent()
            assertTrue(home.isCompleted)
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}

