package me.river.remoter.feature.home

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.Link
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.net.RecentResponse
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.UnreachableException
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.MemoryStore
import me.river.remoter.core.testing.SchedulerClock
import me.river.remoter.feature.session.LiveSync
import me.river.remoter.feature.session.SessionsHub
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import me.river.remoter.core.net.LiveEvent
import me.river.remoter.core.net.SessionsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Found by hand on the emulator: home only reloaded the session list when the
 * link came up or on a pull, so an ended session sat on "Ending…" forever and a
 * banner never followed a session's state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class Rig(scope: TestScope, down: Boolean = false) {
        val clock = SchedulerClock(scope.testScheduler)
        val fixture = FixtureBackend().also { it.unreachable = down }

        /** Health keeps answering while only the folder list fails. */
        var failRecent = false
        var sessionCalls = 0

        /** Replaces the fixture's live stream when a test needs to push its own events. */
        var liveFeed: Flow<LiveEvent>? = null
        val api = object : RemoterApi by fixture {
            override suspend fun recent(): RecentResponse = if (failRecent) throw UnreachableException() else fixture.recent()
            override suspend fun sessions(): SessionsResponse = fixture.sessions().also { sessionCalls++ }
            override fun live(): Flow<LiveEvent> = liveFeed ?: fixture.live()
        }
        val signer = FakeSigner(clock)
        val hub = SessionsHub(api, signer, clock)
        val monitor = ConnectionMonitor(api, FakeVpnNetworks(), clock).also { it.start(scope.backgroundScope) }
        val live = LiveSync(api, monitor, hub).also { it.start(scope.backgroundScope) }
        val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
        val vm = HomeViewModel(api, monitor, hub, store, clock)
    }

    // The view model ticks a clock every second forever; cancel it or runTest never goes idle.
    private fun runRig(down: Boolean = false, block: suspend TestScope.(Rig) -> Unit) = runTest(main.dispatcher.scheduler) {
        val r = Rig(this, down)
        try { block(r) } finally { r.vm.viewModelScope.cancel() }
    }

    @Test
    fun an_ended_session_leaves_the_banners_without_a_pull() = runRig { r ->
        advanceTimeBy(1_000)
        runCurrent()
        val ready = r.vm.ui.value.sessions.first { it.state == SessionState.Ready }
        val calls = r.sessionCalls
        r.hub.end(ready, "r1v3r")
        // The fixture ends it at once and pushes that, so Ending only lasts until the push lands.
        assertTrue("the banner says Ending right away", ready.id in r.hub.ending.value)
        runCurrent()
        assertTrue("an ended session drops off with the push", r.vm.ui.value.sessions.none { it.id == ready.id })
        assertEquals("no fetch needed for it", calls, r.sessionCalls)
        assertTrue(r.vm.ui.value.ending.isEmpty())
    }

    @Test
    fun banners_follow_the_live_stream() = runRig { r ->
        advanceTimeBy(1_000)
        runCurrent()
        val before = r.vm.ui.value.sessions.map { it.id }.toSet()
        val calls = r.sessionCalls
        // A session ends on the laptop side (someone closed its window on workspace 9).
        r.fixture.endOnLaptop(before.first())
        runCurrent()
        assertTrue("home must pick it up at once, not on a probe", r.vm.ui.value.sessions.none { it.id == before.first() })
        assertEquals("and without fetching the list", calls, r.sessionCalls)
    }

    @Test
    fun the_live_list_updates_home_without_any_poll() = runRig { r ->
        advanceTimeBy(1_000)
        runCurrent()
        val calls = r.sessionCalls
        val s = r.vm.ui.value.sessions.first { it.state == SessionState.Ready }
        r.fixture.setState(s.id, SessionState.Stuck)
        runCurrent()
        assertEquals(SessionState.Stuck, r.vm.ui.value.sessions.first { it.id == s.id }.state)
        // A minute with something Stuck on screen used to mean a fetch every 2 s.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("no session list fetches in steady state", calls, r.sessionCalls)
    }

    @Test
    fun an_unchanged_snapshot_does_not_touch_the_list() = runRig { r ->
        val feed = MutableSharedFlow<LiveEvent>()
        r.liveFeed = feed
        r.live.stop()
        r.live.start(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        val list = r.fixture.sessions().sessions
        feed.emit(LiveEvent.Sessions(list))
        runCurrent()
        val shown = r.vm.ui.value.sessions
        feed.emit(LiveEvent.Sessions(list.map { it.copy() }))
        runCurrent()
        assertTrue("an equal snapshot must not hand compose a new list", shown === r.vm.ui.value.sessions)
    }

    @Test
    fun a_health_event_updates_battery_and_lock_live() = runRig { r ->
        val feed = MutableSharedFlow<LiveEvent>()
        r.liveFeed = feed
        r.live.stop()
        r.live.start(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        val h = r.fixture.health()
        feed.emit(LiveEvent.Health(h.copy(batteryPct = 12, onAc = false, locked = true)))
        runCurrent()
        assertEquals(12, r.vm.ui.value.battery)
        assertEquals(false, r.vm.ui.value.onAc)
        assertTrue("the lock banner shows before the next probe", r.vm.ui.value.account!!.locked)
    }

    // First launch with the folder list failing used to leave `loaded` false and the sheet on skeletons for good.
    @Test
    fun failed_first_load_shows_and_retry_clears() = runRig { r ->
        r.failRecent = true
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(r.vm.ui.value.link is Link.Up)
        assertFalse(r.vm.ui.value.loaded)
        assertTrue("the sheet must have something other than skeletons to show", r.vm.ui.value.loadFailed)
        r.failRecent = false
        r.vm.refresh(manual = true)
        runCurrent()
        assertTrue(r.vm.ui.value.loaded)
        assertFalse(r.vm.ui.value.loadFailed)
    }

    @Test
    fun failed_pull_says_so_background_refresh_quiet() = runRig { r ->
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(r.vm.ui.value.loaded)
        r.failRecent = true
        r.vm.refresh()
        runCurrent()
        assertFalse(r.vm.ui.value.refreshFailed)
        r.vm.refresh(manual = true)
        runCurrent()
        assertTrue("a pull that failed must not just end", r.vm.ui.value.refreshFailed)
        r.vm.refreshFailShown()
        assertFalse(r.vm.ui.value.refreshFailed)
    }

    @Test
    fun retry_stays_busy_until_probe_settles() = runRig(down = true) { r ->
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(r.vm.ui.value.link is Link.LaptopDown)
        r.vm.retry()
        runCurrent()
        assertTrue(r.vm.ui.value.retrying)
        advanceTimeBy(300)
        runCurrent()
        assertTrue("still busy while the monitor retries", r.vm.ui.value.retrying)
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(r.vm.ui.value.retrying)
        assertEquals("a retry that found nobody bumps the shake", 1, r.vm.ui.value.stillDown)
    }

    @Test
    fun quick_answer_holds_button_600ms_without_shake() = runRig(down = true) { r ->
        advanceTimeBy(10_000)
        runCurrent()
        r.fixture.unreachable = false
        r.vm.retry()
        runCurrent()
        assertTrue(r.vm.ui.value.link is Link.Up)
        assertTrue("a tap that did something reads as busy for a beat", r.vm.ui.value.retrying)
        advanceTimeBy(700)
        runCurrent()
        assertFalse(r.vm.ui.value.retrying)
        assertEquals(0, r.vm.ui.value.stillDown)
    }

    @Test
    fun pinning_from_home_adds_once() = runRig { r ->
        r.vm.pin("Projects/api")
        r.vm.pin("Projects/api")
        runCurrent()
        assertEquals(listOf("Projects/api"), r.store.state.value?.pinned)
    }
}
