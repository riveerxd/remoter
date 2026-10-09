package me.river.remoter.feature.session

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.Health
import me.river.remoter.core.net.IdEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.SessionDetail
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.MemoryStore
import me.river.remoter.core.testing.SchedulerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

// a laptop ran 11.2 s slow and "Updated" flickered between 9 and 10 s on every poll
@OptIn(ExperimentalCoroutinesApi::class)
class ClockSkewAgeTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun fresh_capture_reads_live_despite_skew() = runTest(main.dispatcher.scheduler) {
        val clock = SchedulerClock(testScheduler)
        val laptopBehindMs = 11_200L
        val real = FixtureBackend(nowMs = clock::nowMs)
        val api = object : RemoterApi by real {
            override suspend fun health(): Health = real.health().copy(serverTime = clock.nowMs() - laptopBehindMs)
            override suspend fun session(id: String, viewToken: String): SessionDetail =
                real.session(id, viewToken).copy(tailAt = clock.nowMs() - laptopBehindMs)
            // stream down, so the age comes from fetched captures alone
            override fun events(id: String, viewToken: String?, lastEventId: String?): Flow<IdEvent> =
                flow { throw java.io.IOException("stream down") }
        }
        val signer = FakeSigner(clock)
        val hub = SessionsHub(api, signer, clock)
        val monitor = ConnectionMonitor(api, FakeVpnNetworks(), clock).also { it.start(backgroundScope) }
        val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
        val vm = SessionDetailViewModel("rc-01k6b7y3m4n5p6q7r8s9t0v1w2", api, hub, clock, monitor, store, LiveSync(api, monitor, hub))
        try {
            runCurrent()
            advanceTimeBy(3_500)
            runCurrent()
            val ui = vm.ui.value
            assertFalse(ui.live)
            assertEquals("Live", ui.ageLabel())
        } finally { vm.viewModelScope.cancel() }
    }
}
