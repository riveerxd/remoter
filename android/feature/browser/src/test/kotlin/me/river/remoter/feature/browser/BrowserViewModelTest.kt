package me.river.remoter.feature.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.MemoryStore
import me.river.remoter.core.testing.SchedulerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun rig(t: kotlinx.coroutines.test.TestScope): Triple<BrowserViewModel, FixtureBackend, FakeSigner> {
        val clock = SchedulerClock(t.testScheduler)
        val api = FixtureBackend()
        val signer = FakeSigner(clock)
        return Triple(BrowserViewModel("Projects", api, signer, MemoryStore(), ConnectionMonitor(api, FakeVpnNetworks(), clock), clock), api, signer)
    }

    @Test
    fun deeper_search_waits_250ms_and_two_characters() = runTest(main.dispatcher.scheduler) {
        val (vm, _, _) = rig(this)
        runCurrent()
        vm.setQuery("r")
        advanceTimeBy(1_000)
        assertTrue(vm.ui.value.deeper.isEmpty())
        vm.setQuery("re")
        advanceTimeBy(249)
        assertTrue("not before 250 ms idle", vm.ui.value.deeper.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertTrue(vm.ui.value.deeper.isNotEmpty())
    }

    @Test
    fun new_folder_validates_on_submit_and_clears_once_valid() = runTest(main.dispatcher.scheduler) {
        val (vm, api, signer) = rig(this)
        advanceUntilIdle()
        vm.openNewFolder()
        vm.setNewName("-bad")
        assertEquals("no error while typing", null, vm.ui.value.newFolder.error)
        vm.submitNewFolder()
        assertEquals(AppError.Validation(ErrorCode.NameInvalid), vm.ui.value.newFolder.error)
        assertEquals("nothing signed", 0, signer.prompts.size)
        vm.setNewName("good-name")
        assertEquals(null, vm.ui.value.newFolder.error)
        vm.submitNewFolder()
        advanceUntilIdle()
        assertEquals(1, api.mkdirCalls.size)
        assertTrue(signer.prompts.single().title.startsWith("Create ~/Projects/good-name on"))
        val names = vm.ui.value.list!!.entries.map { it.name }
        assertTrue("lands in its sorted spot", names.indexOf("good-name") < names.indexOf("remoter"))
    }

    @Test
    fun a_refused_mkdir_shakes_collapses_and_offers_retry() = runTest(main.dispatcher.scheduler) {
        val (vm, api, _) = rig(this)
        advanceUntilIdle()
        api.failWith = ErrorCode.Locked
        vm.openNewFolder("x")
        vm.submitNewFolder()
        advanceUntilIdle()
        val u = vm.ui.value
        assertEquals(1, u.newFolder.shake)
        assertEquals(false, u.newFolder.editing)
        assertEquals(true, u.snackRetry)
    }
}
