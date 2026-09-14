package me.river.remoter.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.river.remoter.core.net.Clock
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Wall and uptime both follow virtual time, so timeouts and timestamps agree in tests. */
class SchedulerClock(private val scheduler: TestCoroutineScheduler, private val epochMs: Long = 1_790_611_106_000) : Clock {
    override fun nowMs() = epochMs + scheduler.currentTime
    override fun uptimeMs() = scheduler.currentTime
}

/** viewModelScope runs on Main; this puts Main on the test scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
