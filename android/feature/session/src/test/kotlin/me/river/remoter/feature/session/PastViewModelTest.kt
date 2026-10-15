package me.river.remoter.feature.session

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.AskResponse
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.net.PastScope
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.MemoryStore
import me.river.remoter.core.testing.SchedulerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset

class PastViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val folder = "Projects/remoter"

    private class Rig(scope: TestScope, path: String) {
        val clock = SchedulerClock(scope.testScheduler, epochMs = 1_790_611_200_000)
        val api = FixtureBackend(nowMs = { clock.nowMs() })
        val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
        val vm = PastViewModel(path, api, clock, ConnectionMonitor(api, FakeVpnNetworks(), clock), store).apply { zone = ZoneOffset.UTC }
        val ui get() = vm.ui.value
    }

    private fun runRig(block: suspend TestScope.(Rig) -> Unit) = runTest(main.dispatcher.scheduler) {
        val r = Rig(this, folder)
        try { block(r) } finally { r.vm.viewModelScope.cancel() }
    }

    @Test
    fun browses_newest_first() = runRig { r ->
        runCurrent()
        val b = r.ui.browse as Browse.Loaded
        assertEquals(30, b.hits.size)
        assertTrue(b.more)
        assertEquals(b.hits.sortedByDescending { it.conversation.updated }, b.hits)
        assertEquals(FixtureBackend.FindCall(folder, "", PastScope.Project, null), r.api.findCalls.single())
    }

    @Test
    fun pages_back() = runRig { r ->
        runCurrent()
        val first = r.ui.browse as Browse.Loaded
        r.vm.loadMore()
        r.vm.loadMore()
        runCurrent()
        val b = r.ui.browse as Browse.Loaded
        assertEquals("one call for two asks", 2, r.api.findCalls.size)
        assertEquals(first.hits.last().conversation.updated, r.api.findCalls[1].before)
        assertEquals(40, b.hits.size)
        assertEquals(b.hits.size, b.hits.map { it.conversation.id }.toSet().size)
        assertTrue(!b.more)
        r.vm.loadMore()
        runCurrent()
        assertEquals("no more to ask", 2, r.api.findCalls.size)
    }

    @Test
    fun find_waits_for_typing() = runRig { r ->
        runCurrent()
        r.vm.setQuery("b")
        r.vm.setQuery("ba")
        r.vm.setQuery("banner")
        assertEquals(Matches.Searching, r.ui.matches)
        advanceTimeBy(PastViewModel.DEBOUNCE_MS - 10)
        runCurrent()
        assertEquals(1, r.api.findCalls.size)
        advanceTimeBy(20)
        runCurrent()
        assertEquals("banner", r.api.findCalls.last().q)
        assertEquals(2, r.api.findCalls.size)
        val m = r.ui.matches as Matches.Found
        assertEquals("fix the banner", m.hits.first().conversation.title)
        assertTrue(m.hits.first().lines.isNotEmpty())
    }

    @Test
    fun clearing_drops_matches() = runRig { r ->
        r.vm.setQuery("banner")
        advanceUntilIdle()
        r.vm.setQuery("")
        assertNull(r.ui.matches)
        assertTrue(r.ui.browse is Browse.Loaded)
    }

    @Test
    fun ask_widens_on_a_miss() = runRig { r ->
        runCurrent()
        r.vm.setQuery("relay round trip under 40 ms")
        r.vm.ask()
        runCurrent()
        val running = r.ui.asking as Asking.Running
        assertEquals(StepState.Reading, running.steps.single().state)
        advanceTimeBy(r.api.askMs + 1)
        runCurrent()
        val widening = r.ui.asking as Asking.Running
        assertEquals(listOf(StepState.Missed, StepState.Reading), widening.steps.map { it.state })
        assertTrue(widening.steps[0].detail.startsWith("Not in any of its"))
        advanceUntilIdle()
        val a = r.ui.asking as Asking.Answered
        assertEquals(listOf(PastScope.Project, PastScope.All), r.api.askCalls.map { it.scope })
        assertEquals("Projects/remoter-public", a.found?.hit?.path)
        assertEquals(StepState.Found, a.steps[1].state)
    }

    @Test
    fun ask_off_in_settings() = runRig { r ->
        r.store.update { it.copy(prefs = it.prefs.copy(askClaude = false)) }
        runCurrent()
        assertEquals(false, r.ui.canAsk)
        r.vm.setQuery("relay round trip")
        r.vm.ask()
        advanceUntilIdle()
        assertTrue("no model call", r.api.askCalls.isEmpty())
        assertNull(r.ui.asking)
        assertNotNull("keywords still work", r.ui.matches)
    }

    @Test
    fun ask_off_drops_answer() = runRig { r ->
        r.vm.setQuery("relay")
        r.vm.ask()
        advanceUntilIdle()
        assertNotNull(r.ui.asking)
        r.store.update { it.copy(prefs = it.prefs.copy(askClaude = false)) }
        runCurrent()
        assertNull(r.ui.asking)
    }

    @Test
    fun found_in_project_stops() = runRig { r ->
        val near = r.api.ask
        r.api.ask = { p, q, s -> if (s == PastScope.Project) near(p, q, PastScope.All).copy(searched = 40) else error("asked twice") }
        r.vm.setQuery("relay")
        r.vm.ask()
        advanceUntilIdle()
        val a = r.ui.asking as Asking.Answered
        assertEquals(1, r.api.askCalls.size)
        assertNotNull(a.found)
        assertEquals(StepState.Found, a.steps.single().state)
    }

    @Test
    fun ask_all_once() = runRig { r ->
        r.vm.setScope(PastScope.All)
        r.vm.setQuery("relay")
        r.vm.ask()
        advanceUntilIdle()
        assertEquals(listOf(PastScope.All), r.api.askCalls.map { it.scope })
        assertTrue(r.ui.asking?.steps?.single()?.title.orEmpty().startsWith("Everything on"))
    }

    @Test
    fun nothing_anywhere() = runRig { r ->
        r.api.ask = { _, _, _ -> AskResponse(12, null, emptyList()) }
        r.vm.setQuery("a thing that never happened")
        r.vm.ask()
        advanceUntilIdle()
        val a = r.ui.asking as Asking.Answered
        assertNull(a.found)
        assertEquals(listOf(StepState.Missed, StepState.Missed), a.steps.map { it.state })
        assertEquals("Nothing fits in 12 sessions", a.steps[1].detail)
    }

    @Test
    fun ask_failure() = runRig { r ->
        r.api.askFails = true
        r.vm.setQuery("relay")
        r.vm.ask()
        advanceUntilIdle()
        assertTrue(r.ui.asking is Asking.Failed)
        r.api.askFails = false
        r.vm.ask()
        advanceUntilIdle()
        assertTrue(r.ui.asking is Asking.Answered)
    }

    @Test
    fun typing_drops_answer() = runRig { r ->
        r.vm.setQuery("relay")
        r.vm.ask()
        advanceUntilIdle()
        r.vm.setQuery("relay keepalive")
        assertNull(r.ui.asking)
    }

    @Test
    fun blank_ask_ignored() = runRig { r ->
        r.vm.setQuery("   ")
        r.vm.ask()
        advanceUntilIdle()
        assertNull(r.ui.asking)
        assertTrue(r.api.askCalls.isEmpty())
    }

    @Test
    fun scope_reloads() = runRig { r ->
        runCurrent()
        r.vm.setScope(PastScope.All)
        runCurrent()
        assertEquals(PastScope.All, r.api.findCalls.last().scope)
        val b = r.ui.browse as Browse.Loaded
        assertTrue(b.hits.any { it.path == "Projects/remoter-public" })
    }

    @Test
    fun browse_error_then_retry() = runRig { r ->
        r.api.unreachable = true
        runCurrent()
        assertTrue(r.ui.browse is Browse.Failed)
        r.api.unreachable = false
        r.vm.retryBrowse()
        runCurrent()
        assertTrue(r.ui.browse is Browse.Loaded)
    }

    @Test
    fun select_toggles() = runRig { r ->
        runCurrent()
        val h = (r.ui.browse as Browse.Loaded).hits[2]
        r.vm.select(h)
        assertEquals(h, r.ui.selected)
        r.vm.select(h)
        assertNull(r.ui.selected)
    }

    @Test
    fun day_labels() {
        val now = 1_790_611_200_000 + 15 * 3_600_000L
        val h = 3_600_000L
        assertEquals("Today", dayLabel(now - h, now, ZoneOffset.UTC))
        assertEquals("Yesterday", dayLabel(now - 24 * h, now, ZoneOffset.UTC))
        assertTrue(dayLabel(now - 3 * 24 * h, now, ZoneOffset.UTC).endsWith("day"))
        assertTrue(dayLabel(now - 20 * 24 * h, now, ZoneOffset.UTC).first().isDigit())
    }
}
