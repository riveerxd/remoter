package me.river.remoter.feature.settings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.AuditPage
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.SchedulerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// a failed page used to leave the skeleton spinning, and the first load raced the list's own request
@OptIn(ExperimentalCoroutinesApi::class)
class AuditViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class Slow(val real: FixtureBackend) : RemoterApi by real {
        var calls = 0
        override suspend fun audit(before: Long?): AuditPage {
            calls++
            delay(100)
            return real.audit(before)
        }
    }

    @Test
    fun failed_first_page_keeps_error_until_retry() = runTest(main.dispatcher.scheduler) {
        val api = FixtureBackend(failWith = ErrorCode.AgentDown)
        val vm = AuditViewModel(api, SchedulerClock(testScheduler))
        try {
            runCurrent()
            assertEquals(AppError.AgentDown, vm.ui.value.error)
            assertTrue(vm.ui.value.days.isEmpty())
            api.failWith = null
            vm.more()
            runCurrent()
            assertNull(vm.ui.value.error)
            assertTrue(vm.ui.value.days.isNotEmpty())
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun more_while_page_in_flight_sends_one_request() = runTest(main.dispatcher.scheduler) {
        val api = Slow(FixtureBackend())
        val vm = AuditViewModel(api, SchedulerClock(testScheduler))
        try {
            runCurrent()
            vm.more()
            vm.more()
            advanceUntilIdle()
            assertEquals(1, api.calls)
        } finally { vm.viewModelScope.cancel() }
    }
}
