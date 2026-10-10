package me.river.remoter.feature.onboarding

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.crypto.PairEvent
import me.river.remoter.core.net.Pairing
import me.river.remoter.core.net.Weakness
import me.river.remoter.core.testing.FakePairer
import me.river.remoter.core.testing.FakeReachability
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.MemoryStore
import me.river.remoter.core.testing.SchedulerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun link(expUnix: Long) = Pairing.Link("10.66.66.3", 8443, 8444, ByteArray(32) { 1 }, ByteArray(32) { 2 }, ByteArray(16) { 3 }, expUnix)

    @Test
    fun connect_checks_itself() = runTest(main.dispatcher.scheduler) {
        val vpn = FakeVpnNetworks().also { it.absent() }
        val reach = FakeReachability(answers = false)
        val vm = OnboardingViewModel(vpn, reach, FakePairer(), MemoryStore(), SchedulerClock(testScheduler))
        vm.begin(null)
        runCurrent()
        assertEquals(OnboardingStep.Connect(false, false), vm.step.value)
        vpn.ours()
        runCurrent()
        assertEquals(OnboardingStep.Connect(true, false), vm.step.value)
        reach.answers = true
        advanceTimeBy(2_100)
        assertEquals(OnboardingStep.Connect(true, true), vm.step.value)
        advanceTimeBy(700)
        assertEquals(OnboardingStep.Scan(), vm.step.value)
    }

    @Test
    fun pasted_link_pairs_and_stores_laptop() = runTest(main.dispatcher.scheduler) {
        val clock = SchedulerClock(testScheduler)
        val store = MemoryStore()
        val vm = OnboardingViewModel(FakeVpnNetworks(), FakeReachability(), FakePairer(confirmMs = 1_000), store, clock)
        vm.submitLink(link(clock.nowMs() / 1000 + 300).toUri())
        advanceTimeBy(700)
        assertEquals(OnboardingStep.Confirm("481207", "A1F309CE"), vm.step.value)
        advanceUntilIdle()
        assertEquals(OnboardingStep.Done, vm.step.value)
        assertNotNull(store.state.value!!.laptop)
    }

    @Test
    fun weak_phone_pairs_and_keeps_the_warning() = runTest(main.dispatcher.scheduler) {
        val clock = SchedulerClock(testScheduler)
        val store = MemoryStore()
        val weak = listOf(Weakness.BootloaderUnlocked, Weakness.BootNotVerified)
        val vm = OnboardingViewModel(FakeVpnNetworks(), FakeReachability(), FakePairer(confirmMs = 1_000, weaknesses = weak), store, clock)
        vm.submitLink(link(clock.nowMs() / 1000 + 300).toUri())
        advanceTimeBy(700)
        assertEquals(OnboardingStep.Confirm("481207", "A1F309CE", weak), vm.step.value)
        advanceUntilIdle()
        assertEquals(OnboardingStep.Done, vm.step.value)
        assertEquals(weak, store.state.value!!.laptop!!.weaknesses)
    }

    @Test
    fun expired_link_stops_without_contacting_laptop() = runTest(main.dispatcher.scheduler) {
        val clock = SchedulerClock(testScheduler)
        val pairer = FakePairer()
        val vm = OnboardingViewModel(FakeVpnNetworks(), FakeReachability(), pairer, MemoryStore(), clock)
        vm.pair(link(clock.nowMs() / 1000 - 1))
        assertEquals(OnboardingStep.Stop(HardStop.Expired), vm.step.value)
    }

    @Test
    fun key_mismatch_stops() = runTest(main.dispatcher.scheduler) {
        val clock = SchedulerClock(testScheduler)
        val vm = OnboardingViewModel(FakeVpnNetworks(), FakeReachability(), FakePairer(outcome = PairEvent.ServerKeyMismatch), MemoryStore(), clock)
        vm.pair(link(clock.nowMs() / 1000 + 300))
        advanceUntilIdle()
        assertEquals(OnboardingStep.Stop(HardStop.ServerKeyMismatch), vm.step.value)
    }

    @Test
    fun mangled_paste_refused() = runTest(main.dispatcher.scheduler) {
        val vm = OnboardingViewModel(FakeVpnNetworks(), FakeReachability(), FakePairer(), MemoryStore(), SchedulerClock(testScheduler))
        vm.pairAgain()
        vm.paste()
        vm.submitLink("remoter://pair?v=1&h=10.66.66.3")
        assertEquals(OnboardingStep.Scan(pasting = true, pasteInvalid = true), vm.step.value)
    }

    private fun vm(scope: kotlinx.coroutines.test.TestScope, pairer: FakePairer = FakePairer(), vpn: FakeVpnNetworks = FakeVpnNetworks()) =
        OnboardingViewModel(vpn, FakeReachability(), pairer, MemoryStore(), SchedulerClock(scope.testScheduler))

    private fun OnboardingViewModel.close() = viewModelScope.cancel()

    @Test
    fun paste_back_to_camera() = runTest(main.dispatcher.scheduler) {
        val vm = vm(this)
        try {
            vm.pairAgain()
            vm.paste()
            vm.submitLink("nope")
            vm.scanInstead()
            assertEquals(OnboardingStep.Scan(), vm.step.value)
        } finally { vm.close() }
    }

    @Test
    fun invalid_hint_clears_on_edit() = runTest(main.dispatcher.scheduler) {
        val vm = vm(this)
        try {
            vm.pairAgain()
            vm.paste()
            vm.submitLink("nope")
            vm.linkEdited()
            assertEquals(OnboardingStep.Scan(pasting = true), vm.step.value)
        } finally { vm.close() }
    }

    @Test
    fun key_mismatch_can_start_over() = runTest(main.dispatcher.scheduler) {
        val clock = SchedulerClock(testScheduler)
        val vpn = FakeVpnNetworks().also { it.absent() }
        val vm = OnboardingViewModel(vpn, FakeReachability(), FakePairer(outcome = PairEvent.ServerKeyMismatch), MemoryStore(), clock)
        try {
            vm.pair(link(clock.nowMs() / 1000 + 300))
            advanceUntilIdle()
            assertEquals(OnboardingStep.Stop(HardStop.ServerKeyMismatch), vm.step.value)
            vm.startOver()
            runCurrent()
            assertEquals(OnboardingStep.Connect(false, false), vm.step.value)
        } finally { vm.close() }
    }
}
