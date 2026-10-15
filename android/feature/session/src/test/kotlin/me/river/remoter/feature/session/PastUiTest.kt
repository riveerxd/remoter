package me.river.remoter.feature.session

import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.net.PastHit
import me.river.remoter.core.net.PastScope
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PastUiTest {
    @get:Rule val compose = createComposeRule()

    private val clock = me.river.remoter.core.net.Clock.System
    private val api = FixtureBackend(nowMs = { clock.nowMs() }).apply { askMs = 0 }
    private val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
    private val vm = PastViewModel("Projects/remoter", api, clock, ConnectionMonitor(api, FakeVpnNetworks(), clock), store)
    private val resumed = mutableListOf<Pair<PastHit, Boolean>>()

    private fun show() {
        compose.setContent {
            val ui by vm.ui.collectAsStateWithLifecycle()
            RemoterTheme(dark = true, reducedMotion = true) {
                PastContent(
                    ui,
                    PastCallbacks(
                        onQuery = vm::setQuery, onScope = vm::setScope, onAsk = vm::ask, onClearAsk = vm::clearAsk,
                        onMore = vm::loadMore, onSelect = vm::select, onResume = { h, off -> resumed += h to off },
                    ),
                )
            }
        }
        compose.waitUntil(5_000) { vm.ui.value.browse is Browse.Loaded }
    }

    @Test
    fun pick_then_resume() {
        show()
        compose.onNodeWithText("fix the banner").performClick()
        compose.onNodeWithContentDescription("Session fix the banner", substring = true).assertIsSelected()
        compose.onNodeWithTag("past-pick").assertIsDisplayed()
        compose.onNodeWithText("Resume").performClick()
        assertEquals("fix the banner", resumed.single().first.conversation.title)
        assertEquals(false, resumed.single().second)
    }

    @Test
    fun open_hands_off_only() {
        show()
        compose.onNodeWithText("remoter").performClick()
        compose.onNodeWithText("Open on r1v3r, so hand off only").assertIsDisplayed()
        compose.onAllNodesWithText("Resume").fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onNodeWithText("Hand off").performClick()
        assertEquals(true, resumed.single().second)
    }

    @Test
    fun ask_from_keyboard_widens() {
        show()
        compose.onNodeWithTag("past-query").performTextInput("relay round trip")
        compose.onNodeWithTag("past-query").performImeAction()
        compose.waitUntil(5_000) { vm.ui.value.asking is Asking.Answered }
        assertEquals(listOf(PastScope.Project, PastScope.All), api.askCalls.map { it.scope })
        compose.onNodeWithText("Found it").assertIsDisplayed()
        compose.onNodeWithText("remoter-public").assertIsDisplayed()
        compose.onNodeWithText("Resume").performClick()
        assertEquals("Projects/remoter-public", resumed.single().first.path)
    }

    @Test
    fun ask_off_keywords_only() {
        kotlinx.coroutines.runBlocking { store.update { it.copy(prefs = it.prefs.copy(askClaude = false)) } }
        show()
        compose.onNodeWithText("Search your sessions").assertIsDisplayed()
        compose.onNodeWithTag("past-query").performTextInput("banner")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PastViewModel.DEBOUNCE_MS + 50))
        compose.waitUntil(5_000) { vm.ui.value.matches is Matches.Found }
        compose.onNodeWithText("1 session mentions it").assertIsDisplayed()
        compose.onAllNodesWithTag("past-ask").fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onNodeWithTag("past-query").performImeAction()
        compose.waitForIdle()
        assertEquals(0, api.askCalls.size)
    }

    @Test
    fun keyword_then_ask_row() {
        show()
        compose.onNodeWithTag("past-query").performTextInput("banner")
        // the debounce runs on the main looper, which Robolectric only moves on when asked
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PastViewModel.DEBOUNCE_MS + 50))
        compose.waitUntil(5_000) { vm.ui.value.matches is Matches.Found }
        compose.onNodeWithText("1 session mentions it").assertIsDisplayed()
        compose.onNodeWithTag("past-ask").performClick()
        compose.waitUntil(5_000) { vm.ui.value.asking is Asking.Answered }
        compose.onNodeWithText("Back to keyword matches").performClick()
        compose.onNodeWithText("1 session mentions it").assertIsDisplayed()
    }

    @Test
    fun scrolling_loads_more() {
        show()
        compose.onNodeWithTag("past-list").performScrollToNode(hasText("theme setting again"))
        compose.waitUntil(5_000) { (vm.ui.value.browse as Browse.Loaded).hits.size == 40 }
        assertEquals(2, api.findCalls.size)
    }
}
