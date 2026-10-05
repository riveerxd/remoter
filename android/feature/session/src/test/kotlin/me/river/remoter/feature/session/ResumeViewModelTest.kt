package me.river.remoter.feature.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.Conversation
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.net.RemoterJson
import me.river.remoter.core.net.SpawnMode
import me.river.remoter.core.net.SpawnRequest
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.MemoryStore
import me.river.remoter.core.testing.SchedulerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ResumeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val target = StartTarget("Projects/remoter", "remoter", isGit = true)

    // 2026-10-07 12:00 UTC, so "yesterday" and "today" are fixed.
    private val now = 1_791_374_400_000L
    private val banner = Conversation("c82d8b5c-edd4-453e-8d59-4748ff325c03", "Fix: the banner's overlap", "check the sheet", now - 26 * 3_600_000L, now - 20 * 3_600_000L, "main", open = false)
    private val busy = Conversation("1170e57c-4a8e-4fb2-9437-21f0fdc2cef3", "remoter", null, now - 7_200_000L, now - 60_000L, null, open = true)

    private class Rig(scope: TestScope, now: Long, list: List<Conversation>) {
        val clock = SchedulerClock(scope.testScheduler, epochMs = now)
        val api = FixtureBackend(phaseStepMs = 700, nowMs = { clock.nowMs() }).apply { history = { list } }
        val signer = FakeSigner(clock)
        val hub = SessionsHub(api, signer, clock)
        val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
        val vm = StartViewModel(api, signer, clock, ConnectionMonitor(api, FakeVpnNetworks(), clock), store, hub).apply { zone = ZoneOffset.UTC }
        val ui get() = vm.ui.value
        fun unlocked() = hub.setViewToken("tok", clock.nowMs() + 15 * 60_000)
        fun body(i: Int = 0) = RemoterJson.decodeFromString(SpawnRequest.serializer(), api.spawnCalls[i].body.decodeToString())
    }

    private fun runRig(list: List<Conversation> = emptyList(), block: suspend TestScope.(Rig) -> Unit) =
        runTest(main.dispatcher.scheduler) { block(Rig(this, now, list.ifEmpty { listOf(banner, busy) })) }

    private fun Rig.rows() = (ui.past as PastState.Loaded).rows

    @Test
    fun list_loads_with_times_in_words() = runRig { r ->
        r.unlocked()
        r.vm.open(target)
        assertEquals(PastState.Loading, r.ui.past)
        runCurrent()
        assertEquals(listOf("Projects/remoter"), r.api.historyCalls)
        assertEquals(listOf("yesterday 16:00", "today 11:59"), r.rows().map { it.whenLabel })
        assertEquals(listOf(banner, busy), r.rows().map { it.conversation })
    }

    /** No app lock any more: the list loads with the phone's key alone. */
    @Test
    fun the_list_loads_without_a_fingerprint() = runRig { r ->
        r.vm.open(target)
        runCurrent()
        assertEquals(2, r.rows().size)
        assertEquals(1, r.api.historyCalls.size)
        assertTrue(r.signer.prompts.isEmpty())
    }

    @Test
    fun a_failure_offers_retry() = runRig { r ->
        r.api.unreachable = true
        r.vm.open(target)
        runCurrent()
        assertEquals(PastState.Failed, r.ui.past)
        r.api.unreachable = false
        r.vm.retryPast()
        runCurrent()
        assertEquals(2, r.rows().size)
    }

    @Test
    fun picking_forces_same_folder_and_names_it() = runRig { r ->
        r.unlocked()
        r.vm.open(target)
        runCurrent()
        r.vm.setMode(SpawnMode.Worktree)
        r.vm.setName("my own name")
        r.vm.selectResume(banner)
        assertEquals(banner, r.ui.form.resume)
        assertEquals(SpawnMode.SameDir, r.ui.form.mode)
        assertEquals("Fix the banner s overlap", r.ui.form.name)

        r.vm.selectResume(banner)
        assertNull(r.ui.form.resume)
        assertEquals("remoter", r.ui.form.name)
    }

    @Test
    fun tapping_a_mode_card_drops_the_pick() = runRig { r ->
        r.unlocked()
        r.vm.open(target)
        runCurrent()
        r.vm.selectResume(banner)
        r.vm.setMode(SpawnMode.SameDir)
        assertNull(r.ui.form.resume)
        assertEquals("remoter", r.ui.form.name)
        assertEquals(SpawnMode.SameDir, r.ui.form.mode)
    }

    @Test
    fun an_open_conversation_is_picked_only_as_a_handoff() = runRig { r ->
        r.unlocked()
        r.vm.open(target)
        runCurrent()
        r.vm.selectResume(busy)
        assertEquals(busy, r.ui.form.resume)
        assertTrue("reading an open one is fine, resuming it isn't", r.ui.form.handoff)
        r.vm.setHandoff(false)
        assertTrue("Continue it stays refused", r.ui.form.handoff)
    }

    @Test
    fun signed_body_carries_the_conversation() = runRig { r ->
        r.unlocked()
        r.vm.open(target)
        runCurrent()
        r.vm.selectResume(banner)
        r.vm.start()
        advanceUntilIdle()
        assertEquals("Resume Fix the banner s overlap in ~/Projects/remoter on r1v3r", r.signer.prompts.single().title)
        val body = r.body()
        assertEquals(banner.id, body.resume)
        assertEquals(SpawnMode.SameDir, body.mode)
        assertEquals("Fix the banner s overlap", body.name)
        assertTrue(r.ui.state is StartState.Ready)
    }

    @Test
    fun a_plain_start_sends_no_resume() = runRig { r ->
        r.unlocked()
        r.vm.open(target)
        runCurrent()
        r.vm.start()
        advanceUntilIdle()
        assertNull(r.body().resume)
        assertTrue(!r.api.spawnCalls[0].body.decodeToString().contains("resume"))
    }

    @Test
    fun untrusted_and_resumed_says_both() = runRig { r ->
        r.unlocked()
        r.vm.open(target.copy(untrusted = true))
        runCurrent()
        r.vm.selectResume(banner)
        r.vm.start()
        advanceUntilIdle()
        assertEquals("Trust ~/Projects/remoter and resume Fix the banner s overlap on r1v3r", r.signer.prompts.single().title)
        assertTrue(r.body().trust)
        assertEquals(banner.id, r.body().resume)
    }

    @Test
    fun open_conversation_refusal_keeps_pick() = runRig { r ->
        r.unlocked()
        r.vm.open(target)
        runCurrent()
        r.vm.selectResume(banner)
        r.api.failWith = ErrorCode.ConversationOpen
        r.vm.start()
        advanceUntilIdle()
        val s = r.ui.state as StartState.NotAccepted
        assertEquals(AppError.ConversationOpen, s.error)
        assertEquals("That conversation is open on r1v3r right now", s.error.copy("r1v3r").title)
        assertEquals("Hand it off to a fresh session instead, or close it there and try again.", s.error.copy("r1v3r").body)

        r.api.failWith = null
        r.vm.retry()
        advanceUntilIdle()
        assertEquals(2, r.api.spawnCalls.size)
        assertEquals(banner.id, r.body(1).resume)
        assertEquals(banner, r.ui.form.resume)
    }

    @Test
    fun new_pick_after_refusal_signs_fresh() = runRig { r ->
        r.unlocked()
        r.vm.open(target)
        runCurrent()
        r.vm.selectResume(banner)
        r.api.unreachable = true
        r.vm.start()
        advanceUntilIdle()
        assertTrue(r.ui.state is StartState.NotAccepted)
        r.vm.selectResume(banner)
        assertEquals(StartState.Idle, r.ui.state)
        r.api.unreachable = false
        r.vm.start()
        advanceUntilIdle()
        assertNull("the cleared pick is what got signed", r.body(1).resume)
        assertEquals(2, r.signer.prompts.size)
    }

    @Test
    fun show_more_lists_them_all() = runRig(List(5) { i -> banner.copy(id = "c82d8b5c-edd4-453e-8d59-4748ff32${5000 + i}", title = "t$i") }) { r ->
        r.unlocked()
        r.vm.open(target)
        runCurrent()
        assertEquals(false, r.ui.pastExpanded)
        r.vm.expandPast()
        assertEquals(true, r.ui.pastExpanded)
        r.vm.open(target)
        assertEquals("a fresh sheet starts folded", false, r.ui.pastExpanded)
    }
}
