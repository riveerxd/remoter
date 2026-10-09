package me.river.remoter.feature.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.Conversation
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.net.RemoterJson
import me.river.remoter.core.net.SpawnMode
import me.river.remoter.core.net.SpawnRequest
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class HandoffViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val target = StartTarget("Projects/remoter", "remoter", isGit = true)
    private val now = 1_791_374_400_000L
    private val banner = Conversation("c82d8b5c-edd4-453e-8d59-4748ff325c03", "Fix: the banner's overlap", "check the sheet", now - 26 * 3_600_000L, now - 20 * 3_600_000L, "main", open = false)
    private val busy = Conversation("1170e57c-4a8e-4fb2-9437-21f0fdc2cef3", "remoter", null, now - 7_200_000L, now - 60_000L, null, open = true)

    private class Rig(scope: TestScope, now: Long, list: List<Conversation>) {
        val clock = SchedulerClock(scope.testScheduler, epochMs = now)
        val api = FixtureBackend(phaseStepMs = 700, nowMs = { clock.nowMs() }).apply {
            history = { list }
            handoffMs = 30_000
        }
        val signer = FakeSigner(clock)
        val hub = SessionsHub(api, signer, clock).apply { setViewToken("tok", now + 15 * 60_000) }
        val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
        val vm = StartViewModel(api, signer, clock, ConnectionMonitor(api, FakeVpnNetworks(), clock), store, hub).apply { zone = ZoneOffset.UTC }
        val ui get() = vm.ui.value
        fun body(i: Int = 0) = RemoterJson.decodeFromString(SpawnRequest.serializer(), api.spawnCalls[i].body.decodeToString())
    }

    private fun runRig(block: suspend TestScope.(Rig) -> Unit) = runTest(main.dispatcher.scheduler) { block(Rig(this, now, listOf(banner, busy))) }

    private suspend fun TestScope.picked(r: Rig, c: Conversation = banner) {
        r.vm.open(target)
        runCurrent()
        r.vm.selectResume(c)
    }

    @Test
    fun handoff_sends_handoff_not_resume() = runRig { r ->
        picked(r)
        r.vm.setHandoff(true)
        r.vm.setMode(SpawnMode.Worktree)
        assertEquals("source kept", banner, r.ui.form.resume)
        assertEquals(SpawnMode.Worktree, r.ui.form.mode)
        r.vm.start()
        runCurrent()
        val body = r.body()
        assertEquals(banner.id, body.handoff)
        assertNull(body.resume)
        assertEquals(SpawnMode.Worktree, body.mode)
        assertEquals("Fix the banner s overlap", body.name)
        assertEquals("Start Fix the banner s overlap in ~/Projects/remoter with a handoff on r1v3r", r.signer.prompts.single().title)
    }

    @Test
    fun back_to_continue_resumes_in_same_folder() = runRig { r ->
        picked(r)
        r.vm.setHandoff(true)
        r.vm.setMode(SpawnMode.Worktree)
        r.vm.setHandoff(false)
        assertEquals(SpawnMode.SameDir, r.ui.form.mode)
        assertTrue(r.ui.form.resumesAsIs)
        r.vm.start()
        runCurrent()
        assertEquals(banner.id, r.body().resume)
        assertNull(r.body().handoff)
        assertTrue(r.signer.prompts.single().title.startsWith("Resume "))
    }

    @Test
    fun open_conversation_only_hands_off() = runRig { r ->
        picked(r, busy)
        assertTrue(r.ui.form.handoff)
        r.vm.setHandoff(false)
        assertTrue(r.ui.form.handoff)
        r.vm.start()
        runCurrent()
        assertEquals(busy.id, r.body().handoff)
        assertNull(r.body().resume)
    }

    @Test
    fun stepper_gets_handoff_step() = runRig { r ->
        picked(r)
        r.vm.setHandoff(true)
        r.vm.start()
        runCurrent()
        val s = r.ui.state as StartState.Starting
        assertEquals(
            listOf("Fingerprint", "Accepted by r1v3r", "Opening the terminal", "Writing the handoff", "Launching Claude", "Connecting Remote Control"),
            s.steps.map { it.label },
        )
        advanceTimeBy(1_500)
        runCurrent()
        val done = (r.ui.state as StartState.Starting).steps.map { it.done }
        assertEquals("handoff pending", listOf(true, true, true, false, false, false), done)
        advanceUntilIdle()
        assertTrue(r.ui.state is StartState.Ready)
    }

    @Test
    fun plain_start_no_handoff_step() = runRig { r ->
        r.vm.open(target)
        runCurrent()
        r.vm.start()
        runCurrent()
        assertFalse((r.ui.state as StartState.Starting).steps.any { it.label == "Writing the handoff" })
    }

    @Test
    fun slow_counts_from_handoff_landing() = runRig { r ->
        picked(r)
        r.vm.setHandoff(true)
        r.vm.start()
        runCurrent()
        // the summarizer takes 30 s here, past 10 s and still not slow
        advanceTimeBy(25_000)
        runCurrent()
        assertFalse((r.ui.state as StartState.Starting).slow)
        // handoff lands near 32 s and the rest follows fast
        advanceUntilIdle()
        assertTrue(r.ui.state is StartState.Ready)
    }

    @Test
    fun plain_start_slow_after_10s() = runRig { r ->
        r.api.spawnScript = SpawnScript.Slow
        r.api.phaseStepMs = 4_000
        r.vm.open(target)
        runCurrent()
        r.vm.start()
        runCurrent()
        advanceTimeBy(11_500)
        runCurrent()
        assertTrue((r.ui.state as StartState.Starting).slow)
    }

    @Test
    fun failed_handoff_is_stuck_and_retry_restarts() = runRig { r ->
        r.api.spawnScript = SpawnScript.HandoffFailed
        picked(r)
        r.vm.setHandoff(true)
        r.vm.start()
        advanceUntilIdle()
        val stuck = r.ui.state as StartState.Stuck
        assertEquals(StuckReason.HandoffFailed, stuck.reason)
        assertEquals("Couldn't write the handoff from that session.", reasonText(stuck.reason))
        r.api.spawnScript = SpawnScript.Ready
        r.vm.retry()
        advanceUntilIdle()
        assertEquals(2, r.api.spawnCalls.size)
        assertEquals(banner.id, r.body(1).handoff)
        assertTrue(r.ui.state is StartState.Ready)
    }
}
