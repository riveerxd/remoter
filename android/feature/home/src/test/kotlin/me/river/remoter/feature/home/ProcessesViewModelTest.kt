package me.river.remoter.feature.home

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.RemoterJson
import me.river.remoter.core.net.Signal
import me.river.remoter.core.net.SignalRequest
import me.river.remoter.core.net.Signed
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.SchedulerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessesViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class Rig(scope: TestScope) {
        val clock = SchedulerClock(scope.testScheduler)
        val fixture = FixtureBackend()
        var refuse: ErrorCode? = null
        val api = object : RemoterApi by fixture {
            override suspend fun signal(signed: Signed) {
                refuse?.let { throw fixture.error(it) }
                fixture.signal(signed)
            }
        }
        val signer = FakeSigner(clock)
        val monitor = ConnectionMonitor(api, FakeVpnNetworks(), clock)
        val vm = ProcessesViewModel(api, signer, monitor, clock)
    }

    private fun runRig(block: suspend TestScope.(Rig) -> Unit) = runTest(main.dispatcher.scheduler) {
        val r = Rig(this)
        r.monitor.onLiveHealth(r.fixture.health())
        try { block(r) } finally { r.vm.viewModelScope.cancel() }
    }

    @Test
    fun sorts_by_cpu_then_memory() = runRig { r ->
        r.vm.load()
        val ui = r.vm.ui.value
        assertTrue(ui.loaded)
        assertEquals(ui.procs.map { it.cpuPct }.sortedDescending(), ui.procs.map { it.cpuPct })
        r.vm.sort(ProcSort.Memory)
        val rss = r.vm.ui.value.procs.map { it.rss }
        assertEquals(rss.sortedDescending(), rss)
        assertTrue("sessions come tagged", r.vm.ui.value.procs.any { it.session?.name == "remoter" })
    }

    @Test
    fun signal_signs_exact_process() = runRig { r ->
        r.vm.load()
        val firefox = r.vm.ui.value.procs.first { it.name == "firefox" }
        r.vm.select(firefox)
        r.vm.signal(firefox, Signal.Term)
        runCurrent()
        val sent = r.fixture.signalCalls.single()
        assertEquals("POST", sent.method)
        assertEquals("/v1/procs/${firefox.pid}/signal", sent.target)
        assertEquals(SignalRequest(firefox.start, Signal.Term), RemoterJson.decodeFromString(SignalRequest.serializer(), sent.body.decodeToString()))
        assertEquals("Quit firefox on r1v3r", r.signer.prompts.single().title)
        val ui = r.vm.ui.value
        assertNull("the sheet closes", ui.selected)
        assertEquals("Sent SIGTERM to firefox", ui.note)
        assertTrue("gone from the next list", ui.procs.none { it.pid == firefox.pid })
    }

    @Test
    fun prompt_names_session() = runRig { r ->
        r.vm.load()
        val claude = r.vm.ui.value.procs.first { it.session != null }
        r.vm.signal(claude, Signal.Kill)
        runCurrent()
        val p = r.signer.prompts.single()
        assertEquals("Kill claude in ${claude.session!!.name} on r1v3r", p.title)
        assertEquals("SIGKILL to pid ${claude.pid}", p.subtitle)
    }

    @Test
    fun cancel_sends_nothing() = runRig { r ->
        r.vm.load()
        val p = r.vm.ui.value.procs.first { it.killable }
        r.vm.select(p)
        r.signer.outcomes += FakeSigner.Next.Cancel
        r.vm.signal(p, Signal.Kill)
        runCurrent()
        assertTrue(r.fixture.signalCalls.isEmpty())
        assertEquals(p, r.vm.ui.value.selected)
        assertNull(r.vm.ui.value.signalError)
    }

    @Test
    fun refusal_stays_on_sheet() = runRig { r ->
        r.vm.load()
        val p = r.vm.ui.value.procs.first { it.killable }
        r.vm.select(p)
        r.refuse = ErrorCode.ProcessDenied
        r.vm.signal(p, Signal.Term)
        runCurrent()
        assertEquals(AppError.Denied(ErrorCode.ProcessDenied), r.vm.ui.value.signalError)
        assertEquals(p, r.vm.ui.value.selected)
        assertNull(r.vm.ui.value.sending)
    }

    @Test
    fun ended_process_closes_sheet() = runRig { r ->
        r.vm.load()
        val p = r.vm.ui.value.procs.first { it.name == "cargo" }
        r.vm.select(p)
        r.fixture.endProcess(p.pid)
        r.vm.load()
        assertNull(r.vm.ui.value.selected)
    }

    @Test
    fun failed_poll_keeps_list() = runRig { r ->
        val job = launch { r.vm.poll() }
        runCurrent()
        val before = r.vm.ui.value.procs
        r.fixture.unreachable = true
        advanceTimeBy(ProcessesViewModel.PollMs + 1)
        assertEquals(before, r.vm.ui.value.procs)
        assertEquals(AppError.Unreachable, r.vm.ui.value.error)
        r.fixture.unreachable = false
        advanceTimeBy(ProcessesViewModel.PollMs + 1)
        assertNull(r.vm.ui.value.error)
        job.cancel()
    }
}
