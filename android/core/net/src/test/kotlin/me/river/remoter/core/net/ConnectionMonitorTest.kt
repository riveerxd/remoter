package me.river.remoter.core.net

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionMonitorTest {
    private class Vpn : VpnNetworks {
        override val link = MutableStateFlow<VpnLink>(VpnLink.Present(1))
    }

    /** Health that answers or fails on command, taking [latency] of virtual time. */
    private class Api(val scope: TestScope) : RemoterApi {
        var up = true
        var latency = 38L
        var calls = 0
        override suspend fun health(): Health {
            calls++
            kotlinx.coroutines.delay(latency)
            if (!up) throw UnreachableException()
            return Health("r1v3r", "0.1.0", scope.testScheduler.currentTime + 47_000, false, 0, true, 90, null)
        }
        override suspend fun list(path: String, hidden: Boolean) = TODO()
        override suspend fun search(query: String, path: String) = TODO()
        override suspend fun recent() = TODO()
        override suspend fun sessions() = TODO()
        override suspend fun session(id: String, viewToken: String) = TODO()
        override suspend fun history(path: String, viewToken: String) = TODO()
        override suspend fun audit(before: Long?) = TODO()
        override suspend fun lock() = TODO()
        override suspend fun mkdir(signed: Signed) = TODO()
        override suspend fun spawn(signed: Signed) = TODO()
        override suspend fun kill(signed: Signed) = TODO()
        override suspend fun viewToken(signed: Signed) = TODO()
        override suspend fun unpair(signed: Signed) = TODO()
        override suspend fun procs() = TODO()
        override suspend fun signal(signed: Signed) = TODO()
        override fun events(id: String, viewToken: String?, lastEventId: String?): Flow<IdEvent> = emptyFlow()
        override fun live(): Flow<LiveEvent> = emptyFlow()
    }

    private fun TestScope.clock() = object : Clock {
        override fun nowMs() = testScheduler.currentTime
        override fun uptimeMs() = testScheduler.currentTime
    }

    @Test
    fun healthy_link_goes_up_with_median_latency() = runTest {
        val api = Api(this)
        val m = ConnectionMonitor(api, Vpn(), clock())
        m.start(backgroundScope)
        advanceTimeBy(100)
        assertEquals(Link.Up(38), m.link.value)
        api.latency = 100; advanceTimeBy(10_200)
        api.latency = 20; advanceTimeBy(10_200)
        assertEquals("median of the last three", Link.Up(38), m.link.value)
        assertEquals(47_000L, m.account.value!!.clockSkewMs)
    }

    @Test
    fun one_failure_shows_reconnecting_not_down() = runTest {
        val api = Api(this)
        val m = ConnectionMonitor(api, Vpn(), clock())
        m.start(backgroundScope)
        advanceTimeBy(100)
        api.up = false
        m.link.test {
            assertEquals(Link.Up(38), awaitItem())
            advanceTimeBy(10_100)
            assertEquals(Link.Reconnecting, awaitItem())
            api.up = true
            advanceTimeBy(1_100)
            assertTrue(awaitItem() is Link.Up)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun laptop_down_after_three_retries() = runTest {
        val api = Api(this)
        val m = ConnectionMonitor(api, Vpn(), clock())
        m.start(backgroundScope)
        advanceTimeBy(100)
        val wentAwayAt = testScheduler.currentTime
        api.up = false
        val callsBefore = api.calls
        var downAt = -1L
        while (downAt < 0 && testScheduler.currentTime < wentAwayAt + 30_000) {
            advanceTimeBy(100)
            if (m.link.value is Link.LaptopDown) downAt = testScheduler.currentTime
        }
        assertEquals("one probe plus three retries", 4, api.calls - callsBefore)
        assertTrue("noticed in ${downAt - wentAwayAt} ms", downAt - wentAwayAt <= 21_000)
        assertEquals(wentAwayAt - 62, (m.link.value as Link.LaptopDown).lastSeenMs)
    }

    @Test
    fun vpn_off_is_immediate_and_sends_no_probe() = runTest {
        val api = Api(this)
        val vpn = Vpn()
        vpn.link.value = VpnLink.Absent
        val m = ConnectionMonitor(api, vpn, clock())
        m.start(backgroundScope)
        advanceTimeBy(30_000)
        assertEquals(Link.VpnOff, m.link.value)
        assertEquals(0, api.calls)
    }

    @Test
    fun network_change_probes_at_once() = runTest {
        val api = Api(this)
        val vpn = Vpn()
        val m = ConnectionMonitor(api, vpn, clock())
        m.start(backgroundScope)
        advanceTimeBy(100)
        val before = api.calls
        vpn.link.value = VpnLink.Present(2)
        runCurrent()
        advanceTimeBy(50)
        assertEquals(before + 1, api.calls)
    }

    @Test
    fun another_vpn_counts_as_vpn_off() = runTest {
        // AndroidVpnNetworks reports a foreign VPN as Absent; the monitor must treat it as off.
        val api = Api(this)
        val vpn = Vpn()
        val m = ConnectionMonitor(api, vpn, clock())
        m.start(backgroundScope)
        advanceTimeBy(100)
        vpn.link.value = VpnLink.Absent
        runCurrent()
        assertEquals(Link.VpnOff, m.link.value)
    }

    @Test
    fun stop_means_no_background_probes() = runTest {
        val api = Api(this)
        val m = ConnectionMonitor(api, Vpn(), clock())
        m.start(backgroundScope)
        advanceTimeBy(100)
        m.stop()
        val calls = api.calls
        advanceTimeBy(60_000)
        assertEquals(calls, api.calls)
    }

    @Test
    fun live_health_updates_without_a_probe() = runTest {
        val api = Api(this)
        val m = ConnectionMonitor(api, Vpn(), clock())
        m.start(backgroundScope)
        advanceTimeBy(100)
        val probed = m.health.value!!
        val calls = api.calls
        m.onLiveHealth(probed.copy(batteryPct = 41, onAc = false, locked = true, serverTime = probed.serverTime + 900_000))
        assertEquals(41, m.health.value!!.batteryPct)
        assertTrue(m.account.value!!.locked)
        assertEquals("the skew is the probe's, not the push's", 47_000L, m.account.value!!.clockSkewMs)
        assertEquals("no extra probe for it", calls, api.calls)
    }
}
