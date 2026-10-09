package me.river.remoter.feature.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.Health
import me.river.remoter.core.net.Link
import me.river.remoter.core.net.LiveEvent
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.SchedulerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSyncTest {
    @get:Rule val main = MainDispatcherRule()

    private class Rig(scope: TestScope) {
        val clock = SchedulerClock(scope.testScheduler)
        val fixture = FixtureBackend(nowMs = clock::nowMs)

        // one channel per connect, closing one with an error drops the link
        val streams = mutableListOf<Channel<LiveEvent>>()

        // fail before any event, like the laptop away
        var refuse = false
        var healthCalls = 0
        val api = object : RemoterApi by fixture {
            override suspend fun health(): Health = fixture.health().also { healthCalls++ }
            override fun live(): Flow<LiveEvent> = flow {
                val ch = Channel<LiveEvent>(Channel.UNLIMITED)
                streams += ch
                if (refuse) throw java.io.IOException("refused")
                for (e in ch) emit(e)
            }
        }
        val vpn = FakeVpnNetworks()
        val hub = SessionsHub(api, FakeSigner(clock), clock)
        val monitor = ConnectionMonitor(api, vpn, clock).also { it.start(scope.backgroundScope) }
        val live = LiveSync(api, monitor, hub).also { it.start(scope.backgroundScope) }
    }

    private fun runRig(block: suspend TestScope.(Rig) -> Unit) = runTest(main.dispatcher.scheduler) {
        val r = Rig(this)
        try { block(r) } finally { r.live.stop() }
    }

    @Test
    fun drop_keeps_list_and_backs_off() = runRig { r ->
        runCurrent()
        val list = r.fixture.sessions().sessions
        r.streams.last().send(LiveEvent.Sessions(list))
        runCurrent()
        assertTrue(r.live.connected.value)
        assertEquals(list, r.hub.sessions.value)

        r.refuse = true
        r.streams.last().close(java.io.IOException("tunnel went away"))
        runCurrent()
        assertFalse(r.live.connected.value)
        assertEquals("emptied", list, r.hub.sessions.value)
        assertEquals(1, r.streams.size)
        // 0.5, 1, 2, 4, then capped at 4 s
        for ((i, wait) in listOf(500L, 1_000L, 2_000L, 4_000L, 4_000L).withIndex()) {
            advanceTimeBy(wait - 1)
            runCurrent()
            assertEquals("not before $wait ms", i + 1, r.streams.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("retry ${i + 1} after $wait ms", i + 2, r.streams.size)
        }
        assertEquals(list, r.hub.sessions.value)

        r.refuse = false
        advanceTimeBy(4_000)
        runCurrent()
        val shorter = list.drop(1)
        r.streams.last().send(LiveEvent.Sessions(shorter))
        runCurrent()
        assertTrue(r.live.connected.value)
        assertEquals(shorter, r.hub.sessions.value)

        // a good connect resets the backoff
        r.streams.last().close(java.io.IOException("again"))
        runCurrent()
        val n = r.streams.size
        advanceTimeBy(500)
        runCurrent()
        assertEquals(n + 1, r.streams.size)
    }

    @Test
    fun drop_probes_link_at_once() = runRig { r ->
        runCurrent()
        r.streams.last().send(LiveEvent.Sessions(emptyList()))
        runCurrent()
        val before = r.healthCalls
        r.streams.last().close(java.io.IOException("gone"))
        runCurrent()
        assertEquals("waited for the probe", before + 1, r.healthCalls)
    }

    // kicking the monitor on every failed attempt restarted its retries, so with the laptop away
    // it sat on Reconnecting for good
    @Test
    fun away_laptop_reads_down() = runRig { r ->
        r.refuse = true
        r.fixture.unreachable = true
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(r.monitor.link.value.toString(), r.monitor.link.value is Link.LaptopDown)
        assertTrue("kept trying", r.streams.size > 5)
    }

    @Test
    fun no_attempts_while_vpn_off() = runRig { r ->
        runCurrent()
        r.vpn.absent()
        r.streams.last().close(java.io.IOException("tunnel off"))
        runCurrent()
        val n = r.streams.size
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(n, r.streams.size)
        r.vpn.ours()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(r.streams.size > n)
    }

    @Test
    fun health_event_reaches_monitor() = runRig { r ->
        runCurrent()
        val h = r.fixture.health().copy(batteryPct = 7, locked = true)
        r.streams.last().send(LiveEvent.Health(h))
        runCurrent()
        assertEquals(7, r.monitor.health.value?.batteryPct)
        assertTrue(r.monitor.account.value!!.locked)
    }
}
