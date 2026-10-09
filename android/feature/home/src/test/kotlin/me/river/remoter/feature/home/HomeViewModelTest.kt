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
import me.river.remoter.core.net.HomeSnapshot
import me.river.remoter.core.net.Health
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

// home once reloaded sessions only on link up or a pull, so an ended one sat on Ending forever
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class Rig(scope: TestScope, down: Boolean = false, snapshot: HomeSnapshot? = null) {
        val clock = SchedulerClock(scope.testScheduler)
        val fixture = FixtureBackend().also { it.unreachable = down }

        var failRecent = false
        var sessionCalls = 0

        var liveFeed: Flow<LiveEvent>? = null
        var tunnel: String? = null
        val api = object : RemoterApi by fixture {
            override suspend fun health(): Health = fixture.health().copy(tunnel = tunnel)
            override suspend fun recent(): RecentResponse = if (failRecent) throw UnreachableException() else fixture.recent()
            override suspend fun sessions(): SessionsResponse = fixture.sessions().also { sessionCalls++ }
            override fun live(): Flow<LiveEvent> = liveFeed ?: fixture.live()
        }
        val signer = FakeSigner(clock)
        val hub = SessionsHub(api, signer, clock)
        val monitor = ConnectionMonitor(api, FakeVpnNetworks(), clock).also { it.start(scope.backgroundScope) }
        val live = LiveSync(api, monitor, hub).also { it.start(scope.backgroundScope) }
        val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null), snapshot = snapshot))
        val vm = HomeViewModel(api, monitor, hub, store, clock)
    }

    // the vm ticks a clock forever, cancel it or runTest never idles
    private fun runRig(down: Boolean = false, snapshot: HomeSnapshot? = null, block: suspend TestScope.(Rig) -> Unit) = runTest(main.dispatcher.scheduler) {
        val r = Rig(this, down, snapshot)
        try { block(r) } finally { r.vm.viewModelScope.cancel() }
    }

    @Test
    fun ended_session_drops_without_pull() = runRig { r ->
        advanceTimeBy(1_000)
        runCurrent()
        val ready = r.vm.ui.value.sessions.first { it.state == SessionState.Ready }
        val calls = r.sessionCalls
        r.hub.end(ready, "r1v3r")
        assertTrue("ending", ready.id in r.hub.ending.value)
        runCurrent()
        assertTrue("dropped", r.vm.ui.value.sessions.none { it.id == ready.id })
        assertEquals("fetched", calls, r.sessionCalls)
        assertTrue(r.vm.ui.value.ending.isEmpty())
    }

    @Test
    fun banners_follow_the_live_stream() = runRig { r ->
        advanceTimeBy(1_000)
        runCurrent()
        val before = r.vm.ui.value.sessions.map { it.id }.toSet()
        val calls = r.sessionCalls
        // someone closed its window on the laptop
        r.fixture.endOnLaptop(before.first())
        runCurrent()
        assertTrue("still listed", r.vm.ui.value.sessions.none { it.id == before.first() })
        assertEquals("fetched", calls, r.sessionCalls)
    }

    @Test
    fun live_list_needs_no_poll() = runRig { r ->
        advanceTimeBy(1_000)
        runCurrent()
        val calls = r.sessionCalls
        val s = r.vm.ui.value.sessions.first { it.state == SessionState.Ready }
        r.fixture.setState(s.id, SessionState.Stuck)
        runCurrent()
        assertEquals(SessionState.Stuck, r.vm.ui.value.sessions.first { it.id == s.id }.state)
        // Stuck on screen used to mean a fetch every 2 s
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("fetched", calls, r.sessionCalls)
    }

    @Test
    fun equal_snapshot_keeps_list() = runRig { r ->
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
        assertTrue("new list", shown === r.vm.ui.value.sessions)
    }

    @Test
    fun health_event_updates_battery_and_lock() = runRig { r ->
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
        assertTrue(r.vm.ui.value.account!!.locked)
    }

    // a failing folder list on first launch used to leave the sheet on skeletons for good
    @Test
    fun failed_first_load_shows_and_retry_clears() = runRig { r ->
        r.failRecent = true
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(r.vm.ui.value.link is Link.Up)
        assertFalse(r.vm.ui.value.loaded)
        assertTrue(r.vm.ui.value.loadFailed)
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
        assertTrue(r.vm.ui.value.refreshFailed)
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
        assertTrue("still busy", r.vm.ui.value.retrying)
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(r.vm.ui.value.retrying)
        assertEquals("shake", 1, r.vm.ui.value.stillDown)
    }

    @Test
    fun quick_answer_holds_button_600ms_without_shake() = runRig(down = true) { r ->
        advanceTimeBy(10_000)
        runCurrent()
        r.fixture.unreachable = false
        r.vm.retry()
        runCurrent()
        assertTrue(r.vm.ui.value.link is Link.Up)
        assertTrue("held", r.vm.ui.value.retrying)
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

    @Test
    fun map_follows_tunnel() = runRig { r ->
        r.tunnel = "direct"
        advanceTimeBy(1_000)
        assertTrue(r.vm.ui.value.direct)
        r.tunnel = "hub"
        advanceTimeBy(ConnectionMonitor.INTERVAL_MS + 1_000)
        assertFalse(r.vm.ui.value.direct)
        r.tunnel = "direct"
        advanceTimeBy(ConnectionMonitor.INTERVAL_MS + 1_000)
        r.vm.refresh().join()
        assertTrue("saved", r.store.state.value?.snapshot?.direct == true)
    }

    // found on the emulator: switched while the app was open, the next cold start drew the old map
    @Test
    fun switch_while_open_is_saved() = runRig { r ->
        advanceTimeBy(1_000)
        r.vm.refresh().join()
        assertFalse(r.store.state.value?.snapshot?.direct == true)
        r.tunnel = "direct"
        advanceTimeBy(ConnectionMonitor.INTERVAL_MS + 1_000)
        assertTrue(r.store.state.value?.snapshot?.direct == true)
    }

    @Test
    fun direct_saved_from_first_answer() = runRig { r ->
        r.tunnel = "direct"
        runCurrent()
        advanceTimeBy(5_000)
        assertTrue(r.store.state.value?.snapshot?.direct == true)
    }

    @Test
    fun cold_start_uses_last_tunnel() = runRig(down = true, snapshot = HomeSnapshot("r1v3r", emptyList(), emptyList(), 50, true, 0, direct = true)) { r ->
        advanceTimeBy(30_000)
        assertTrue(r.vm.ui.value.link is Link.LaptopDown)
        assertTrue(r.vm.ui.value.direct)
    }
}
