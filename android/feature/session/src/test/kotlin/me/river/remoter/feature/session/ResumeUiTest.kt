package me.river.remoter.feature.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.components.RemoterSheet
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.Conversation
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MemoryStore
import androidx.compose.ui.test.assertIsNotEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ResumeUiTest {
    @get:Rule val compose = createComposeRule()

    private val clock = me.river.remoter.core.net.Clock.System
    private val now = clock.nowMs()
    private val api = FixtureBackend(phaseStepMs = 0)
    private val signer = FakeSigner(clock, fingerMs = 0)
    private val hub = SessionsHub(api, signer, clock)
    private val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
    private val vm = StartViewModel(api, signer, clock, ConnectionMonitor(api, FakeVpnNetworks(), clock), store, hub)

    private fun conv(i: Int, title: String, open: Boolean = false) =
        Conversation("c82d8b5c-edd4-453e-8d59-4748ff32${5000 + i}", title, "prompt $i", now - 3_600_000L * (i + 2), now - 3_600_000L * (i + 1), "main", open)

    private val five = listOf(conv(0, "fix the banner"), conv(1, "busy one", open = true), conv(2, "release signing"), conv(3, "pairing qr"), conv(4, "first sketch"))

    private fun show(list: List<Conversation> = five, unlocked: Boolean = true) {
        api.history = { list }
        if (unlocked) hub.setViewToken("tok", now + 900_000)
        vm.open(StartTarget("Projects/remoter", "remoter", true))
        compose.setContent {
            val ui by vm.ui.collectAsStateWithLifecycle()
            RemoterTheme(dark = true, reducedMotion = true) {
                Box(Modifier.fillMaxSize()) {
                    RemoterSheet(ui.open, vm::close) {
                        StartContent(
                            ui,
                            StartCallbacks(
                                onMode = vm::setMode, onStart = vm::start, onRetry = vm::retry, onClose = vm::close,
                                onPickPast = vm::selectResume, onRetryPast = vm::retryPast, onMorePast = vm::expandPast,
                            ),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun two_show_then_the_rest_on_show_more() {
        show()
        compose.onNodeWithText("Previous sessions").assertIsDisplayed()
        compose.onNodeWithText("release signing").assertDoesNotExist()
        compose.onNodeWithText("Show all 5 sessions").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("pairing qr").assertExists()
        compose.onNodeWithText("first sketch").assertExists()
        compose.onNodeWithText("Show all 5 sessions").assertDoesNotExist()
    }

    @Test
    fun picking_one_keeps_the_list_still() {
        show()
        compose.onNodeWithText("Worktree").assertExists()
        val label = pastWhen(five[0].updated, clock.nowMs(), vm.zone)
        val row = compose.onNodeWithContentDescription("Previous session fix the banner, $label").performScrollTo()
        val before = row.fetchSemanticsNode().boundsInRoot
        row.performClick()
        compose.waitForIdle()
        assertEquals(five[0], vm.ui.value.form.resume)
        assertEquals("the picked row stays under the finger", before, row.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("Previous session fix the banner, $label").assertIsSelected()
        compose.onNodeWithText("Worktree").assertIsNotSelected()
        compose.onNodeWithText("Resume session").assertExists()
        compose.onNodeWithText("Start session").assertDoesNotExist()
        compose.onNodeWithText("Same folder").assertIsNotSelected()

        compose.onNodeWithText("Same folder").performScrollTo().performClick()
        compose.waitForIdle()
        assertNull(vm.ui.value.form.resume)
        compose.onNodeWithText("Same folder").assertIsSelected()
        compose.onNodeWithText("Start session").assertExists()
    }

    @Test
    fun open_one_picks_as_handoff() {
        show()
        compose.onNodeWithText("Open on r1v3r now", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("busy one").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("busy one", vm.ui.value.form.resume?.title)
        assertEquals(true, vm.ui.value.form.handoff)
        compose.onNodeWithText("Start with handoff").assertExists()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertEquals(true, vm.ui.value.form.handoff)
    }

    @Test
    fun the_list_shows_without_a_fingerprint() {
        show(unlocked = false)
        compose.waitUntil(3_000) { vm.ui.value.past is PastState.Loaded }
        compose.waitForIdle()
        compose.onNodeWithText("fix the banner").assertExists()
        assertTrue("no fingerprint just to read", signer.prompts.isEmpty())
    }

    @Test
    fun a_folder_with_no_past_shows_no_section() {
        show(emptyList())
        compose.onNodeWithText("Previous sessions").assertDoesNotExist()
        compose.onNodeWithText("Start session").assertExists()
    }

    @Test
    fun a_failed_list_offers_retry() {
        api.unreachable = true
        show()
        compose.waitUntil(3_000) { vm.ui.value.past == PastState.Failed }
        compose.onNodeWithText("Couldn't load previous sessions").assertExists()
        api.unreachable = false
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(3_000) { vm.ui.value.past is PastState.Loaded }
        compose.waitForIdle()
        compose.onNodeWithText("fix the banner").assertExists()
    }

    @Test
    fun open_refusal_retries_same_pick() {
        show()
        val label = pastWhen(five[0].updated, clock.nowMs(), vm.zone)
        compose.onNodeWithContentDescription("Previous session fix the banner, $label").performScrollTo().performClick()
        api.failWith = me.river.remoter.core.net.ErrorCode.ConversationOpen
        compose.onNodeWithText("Resume session").performScrollTo().performClick()
        compose.waitUntil(3_000) { vm.ui.value.state is StartState.NotAccepted }
        compose.waitForIdle()
        compose.onNodeWithText("That conversation is open on r1v3r right now").assertExists()
        api.failWith = null
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(3_000) { api.spawnCalls.size == 2 }
        assertEquals(2, signer.prompts.size)
        assertEquals(five[0], vm.ui.value.form.resume)
    }
}
