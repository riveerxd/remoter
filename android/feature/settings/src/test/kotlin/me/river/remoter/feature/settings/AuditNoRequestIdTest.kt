package me.river.remoter.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.AuditEntry
import me.river.remoter.core.net.AuditPage
import me.river.remoter.core.net.RemoterApi
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MainDispatcherRule
import me.river.remoter.core.testing.SchedulerClock
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The audit log crashed on open: pairing, lock and unlock
 * entries have no request behind them and the laptop logs "-" for each, and
 * the list was keyed by request id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AuditNoRequestIdTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val main = MainDispatcherRule()

    private val t = 1_790_619_000_000
    private val entries = listOf(
        AuditEntry(t, null, "pair_open", "s25u", "ok", "-"),
        AuditEntry(t + 1_000, null, "pair", null, "attestation", "-"),
        AuditEntry(t + 2_000, "dev", "pair", null, "ok", "-"),
        AuditEntry(t + 3_000, "dev", "lock", null, "phone", "-"),
    )

    @Test
    fun entries_sharing_a_dash_request_id_all_render() {
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) {
                AuditContent(AuditUi(loading = false, end = true, days = persistentListOf(AuditDay("Today", entries.reversed().let { kotlinx.collections.immutable.persistentListOf(*it.toTypedArray()) }))), {}, {}, "r1v3r")
            }
        }
        compose.waitForIdle()
        assertEquals(4, compose.onAllNodesWithText(":", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun paging_keeps_every_entry_that_shares_a_request_id() = runTest(main.dispatcher.scheduler) {
        val api = object : RemoterApi by FixtureBackend() {
            override suspend fun audit(before: Long?) = AuditPage(entries, nextBefore = null)
        }
        val vm = AuditViewModel(api, SchedulerClock(testScheduler))
        try {
            runCurrent()
            assertEquals(4, vm.ui.value.days.sumOf { it.entries.size })
        } finally { vm.viewModelScope.cancel() }
    }
}
