package me.river.remoter.feature.browser

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.testing.FakeSigner
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

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSearchPinRetryTest {
    @get:Rule val main = MainDispatcherRule()

    private class Rig(val vm: BrowserViewModel, val api: FixtureBackend, val store: MemoryStore)

    private suspend fun TestScope.rig(pinned: List<String> = emptyList(), block: suspend TestScope.(Rig) -> Unit) {
        val clock = SchedulerClock(testScheduler)
        val api = FixtureBackend()
        val store = MemoryStore(LocalState(pinned = pinned))
        val vm = BrowserViewModel("Projects", api, FakeSigner(clock), store, ConnectionMonitor(api, FakeVpnNetworks(), clock), clock)
        val r = Rig(vm, api, store)
        try {
            runCurrent()
            block(r)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun searching_flag_set_on_keystroke() = runTest(main.dispatcher.scheduler) {
        rig { r ->
            r.vm.setQuery("xy")
            assertTrue("searching before the 250 ms wait", r.vm.ui.value.searching)
            r.vm.setQuery("x")
            assertEquals(false, r.vm.ui.value.searching)
        }
    }

    @Test
    fun failed_deep_search_is_an_error() = runTest(main.dispatcher.scheduler) {
        rig { r ->
            r.api.unreachable = true
            r.vm.setQuery("xy")
            advanceUntilIdle()
            assertNotNull(r.vm.ui.value.searchError)
            assertEquals(false, r.vm.ui.value.searching)
            r.api.unreachable = false
            r.vm.retrySearch()
            assertNull("retry clears it at once", r.vm.ui.value.searchError)
            advanceUntilIdle()
            assertNull(r.vm.ui.value.searchError)
            assertTrue(r.vm.ui.value.deeper.isNotEmpty())
        }
    }

    @Test
    fun a_failed_create_says_why() = runTest(main.dispatcher.scheduler) {
        rig { r ->
            advanceUntilIdle()
            r.api.failWith = ErrorCode.Locked
            r.vm.openNewFolder("x")
            r.vm.submitNewFolder()
            advanceUntilIdle()
            val snack = r.vm.ui.value.snack!!
            assertTrue(snack, snack.startsWith("Couldn't create 'x': "))
            assertTrue(snack, snack.endsWith("is locked"))
            assertNull("a create error isn't a listing error", r.vm.ui.value.error)
        }
    }

    @Test
    fun undo_puts_an_unpin_back_in_place() = runTest(main.dispatcher.scheduler) {
        rig(pinned = listOf("a", "Projects/remoter", "b")) { r ->
            r.vm.togglePin("Projects/remoter")
            advanceUntilIdle()
            assertEquals(listOf("a", "b"), r.store.state.value!!.pinned)
            assertEquals(1 to "Projects/remoter", r.vm.ui.value.undoUnpin)
            r.vm.undoUnpin()
            advanceUntilIdle()
            assertEquals(listOf("a", "Projects/remoter", "b"), r.store.state.value!!.pinned)
            assertNull(r.vm.ui.value.undoUnpin)
        }
    }

    @Test
    fun pinning_needs_no_undo() = runTest(main.dispatcher.scheduler) {
        rig { r ->
            r.vm.togglePin("Projects/api")
            advanceUntilIdle()
            assertEquals(listOf("Projects/api"), r.store.state.value!!.pinned)
            assertNull(r.vm.ui.value.undoUnpin)
        }
    }

    @Test
    fun retry_after_a_failed_first_load_lists_the_folder() = runTest(main.dispatcher.scheduler) {
        val clock = SchedulerClock(testScheduler)
        val api = FixtureBackend(unreachable = true)
        val vm = BrowserViewModel("Projects", api, FakeSigner(clock), MemoryStore(), ConnectionMonitor(api, FakeVpnNetworks(), clock), clock)
        try {
            runCurrent()
            assertEquals(AppError.Unreachable, vm.ui.value.error)
            api.unreachable = false
            vm.load()
            runCurrent()
            assertNotNull(vm.ui.value.list)
            assertNull(vm.ui.value.error)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun trust_command_survives_spaces_and_quotes() {
        assertEquals("~/Projects/api", shellPath("Projects/api"))
        assertEquals("~/'my app'", shellPath("my app"))
        assertEquals("~/'it'\\''s'", shellPath("it's"))
        assertEquals("~", shellPath(""))
    }
}
