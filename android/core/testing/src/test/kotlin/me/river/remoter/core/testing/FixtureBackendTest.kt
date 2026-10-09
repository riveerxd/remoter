package me.river.remoter.core.testing

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.LiveEvent
import me.river.remoter.core.net.SessionState
import org.junit.Assert.assertFalse
import me.river.remoter.core.net.ApiException
import me.river.remoter.core.net.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureBackendTest {
    @Test
    fun serves_the_fixture_responses() = runTest {
        val b = FixtureBackend()
        assertEquals("r1v3r", b.health().hostname)
        assertEquals(4, b.list("Projects", false).entries.size)
        assertEquals(8, b.sessions().cap)
    }

    @Test
    fun fails_with_the_fixture_error_body() = runTest {
        val b = FixtureBackend(failWith = ErrorCode.RateLimited)
        val e = runCatching { b.health() }.exceptionOrNull() as ApiException
        assertEquals(429, e.status)
        assertEquals(17, e.body.retryAfterS)
    }

    @Test
    fun fake_clock_and_vpn_move_when_told() {
        val clock = FakeClock(now = 10, uptime = 5)
        clock.advance(1000)
        assertEquals(1010, clock.nowMs())
        val vpn = FakeVpnNetworks()
        vpn.absent()
        assertTrue(vpn.link.value is me.river.remoter.core.net.VpnLink.Absent)
    }

    @Test
    fun live_sends_a_snapshot_then_only_real_changes() = runTest {
        val b = FixtureBackend()
        val got = mutableListOf<LiveEvent>()
        val job = backgroundScope.launch { b.live().collect { got += it } }
        runCurrent()
        assertTrue(got[0] is LiveEvent.Health)
        val first = (got[1] as LiveEvent.Sessions).sessions
        assertEquals(b.sessions().sessions, first)
        assertTrue(got[2] is LiveEvent.Resources)
        b.setState(first[0].id, first[0].state)
        runCurrent()
        assertEquals("nothing changed, nothing sent", 3, got.size)
        b.endOnLaptop(first[0].id)
        runCurrent()
        val after = got.filterIsInstance<LiveEvent.Sessions>().last().sessions
        assertFalse(after.any { it.id == first[0].id })
        assertEquals("the count in health follows", after.size, got.filterIsInstance<LiveEvent.Health>().last().health.sessions)
        job.cancel()
    }

    @Test
    fun live_drops_when_the_laptop_goes_away() = runTest {
        val b = FixtureBackend()
        val r = backgroundScope.launch { runCatching { b.live().toList() }.also { assertTrue(it.isFailure) } }
        runCurrent()
        b.unreachable = true
        runCurrent()
        assertTrue(r.isCompleted)
    }

    @Test
    fun settled_session_stream_ends_with_it() = runTest {
        val b = FixtureBackend()
        val s = b.sessions().sessions.first { it.state == SessionState.Ready }
        val got = mutableListOf<me.river.remoter.core.net.Event>()
        val job = backgroundScope.launch { b.events(s.id, "tok", null).collect { got += it.event } }
        runCurrent()
        assertEquals(me.river.remoter.core.net.Event.StateEvent(SessionState.Ready, null, null), got[0])
        assertTrue(got[1] is me.river.remoter.core.net.Event.TailEvent)
        b.setState(s.id, SessionState.Stuck)
        runCurrent()
        assertEquals(SessionState.Stuck, (got[2] as me.river.remoter.core.net.Event.StateEvent).state)
        b.endOnLaptop(s.id)
        runCurrent()
        assertTrue("the stream ends with the session", job.isCompleted)
    }
}
