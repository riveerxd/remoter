package me.river.remoter.feature.session

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.Event
import me.river.remoter.core.net.IdEvent
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.net.SessionDetail
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.MemoryStore
import me.river.remoter.core.testing.SchedulerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val ID = "rc-01k6b7y3m4n5p6q7r8s9t0v1w2"

// detail used to fetch the whole session every second
@OptIn(ExperimentalCoroutinesApi::class)
class DetailStreamTest {
    @get:Rule val main = MainDispatcherRule()

    private class Rig(scope: TestScope) {
        val clock = SchedulerClock(scope.testScheduler)
        val fixture = FixtureBackend(nowMs = clock::nowMs)
        val streams = mutableListOf<Channel<IdEvent>>()
        val tokens = mutableListOf<String?>()
        var refuse = false
        var detailCalls = 0

        // told apart from what the stream sent
        var polledTail = listOf("polled")
        val api = object : RemoterApi by fixture {
            // same clock on both ends
            override suspend fun health() = fixture.health().copy(serverTime = clock.nowMs())
            override fun events(id: String, viewToken: String?, lastEventId: String?): Flow<IdEvent> = flow {
                val ch = Channel<IdEvent>(Channel.UNLIMITED)
                streams += ch
                tokens += viewToken
                if (refuse) throw java.io.IOException("refused")
                for (e in ch) emit(e)
            }
            override suspend fun session(id: String, viewToken: String): SessionDetail {
                detailCalls++
                return fixture.session(id, viewToken).copy(tail = polledTail, tailAt = clock.nowMs())
            }
        }
        val hub = SessionsHub(api, FakeSigner(clock), clock)
        val monitor = ConnectionMonitor(api, FakeVpnNetworks(), clock).also { it.start(scope.backgroundScope) }
        val live = LiveSync(api, monitor, hub).also { it.start(scope.backgroundScope) }
        val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
        val vm = SessionDetailViewModel(ID, api, hub, clock, monitor, store, live)
        val ui get() = vm.ui.value

        fun tail(vararg lines: String) = streams.last().trySend(IdEvent(null, Event.TailEvent(lines.toList(), clock.nowMs())))
    }

    private fun runRig(block: suspend TestScope.(Rig) -> Unit) = runTest(main.dispatcher.scheduler) {
        val r = Rig(this)
        try {
            runCurrent()
            block(r)
        } finally {
            r.live.stop()
            r.vm.viewModelScope.cancel()
        }
    }

    @Test
    fun tail_from_stream_no_poll() = runRig { r ->
        assertTrue("token", r.tokens.last() != null)
        r.tail("$ cargo test", "ok")
        runCurrent()
        assertEquals(listOf("$ cargo test", "ok"), r.ui.tail)
        assertTrue(r.ui.live)
        val calls = r.detailCalls
        assertEquals("one fetch on open", 1, calls)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("polled", calls, r.detailCalls)
        r.tail("$ cargo test", "ok", "done")
        runCurrent()
        assertEquals(3, r.ui.tail.size)
    }

    @Test
    fun same_tail_same_list() = runRig { r ->
        r.tail("a", "b")
        runCurrent()
        val shown = r.ui.tail
        r.tail("a", "b")
        runCurrent()
        assertTrue("new list", shown === r.ui.tail)
    }

    @Test
    fun state_comes_from_the_stream() = runRig { r ->
        r.streams.last().trySend(IdEvent("9", Event.StateEvent(SessionState.Exited, null, 3)))
        runCurrent()
        assertEquals(SessionState.Exited, r.ui.session?.state)
        assertEquals(3, r.ui.session?.exitCode)
    }

    @Test
    fun dropped_stream_polls_until_back() = runRig { r ->
        r.tail("from the stream")
        runCurrent()
        r.refuse = true
        r.streams.last().close(java.io.IOException("dropped"))
        runCurrent()
        assertFalse(r.ui.live)
        assertTrue("emptied", r.ui.tail.isNotEmpty())
        val calls = r.detailCalls
        advanceTimeBy(3_100)
        runCurrent()
        assertTrue("polls every second", r.detailCalls >= calls + 3)
        assertEquals(listOf("polled"), r.ui.tail)

        r.refuse = false
        advanceTimeBy(4_000)
        runCurrent()
        r.tail("back on the stream")
        runCurrent()
        assertTrue(r.ui.live)
        val after = r.detailCalls
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("still polling", after, r.detailCalls)
        assertEquals(listOf("back on the stream"), r.ui.tail)
    }

    @Test
    fun age_counts_from_the_drop() = runRig { r ->
        r.tail("idle prompt")
        runCurrent()
        // an idle screen sends nothing for minutes and is still live
        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(r.ui.live)
        r.refuse = true
        r.polledTail = listOf("idle prompt")
        r.streams.last().close(java.io.IOException("dropped"))
        runCurrent()
        assertEquals("Live", r.ui.ageLabel())
    }
}
