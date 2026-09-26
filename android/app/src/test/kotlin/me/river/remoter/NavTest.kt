package me.river.remoter

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Nav rules on a real NavDisplay: every folder is its own entry, so a breadcrumb
 * is a pop, scroll lives per entry, and back follows the gesture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class NavTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var back: NavBackStack<NavKey>
    private val states = mutableMapOf<String, LazyListState>()

    private fun show(vararg keys: NavKey) = compose.setContent {
        back = rememberNavBackStack(*keys)
        NavDisplay(
            back,
            onBack = { back.removeAt(back.lastIndex) },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<Home> { Text("home") }
                entry<Browser> { k ->
                    val st = rememberLazyListState()
                    states[k.path] = st
                    LazyColumn(Modifier.testTag("list:${k.path}"), state = st) {
                        items(100) { i -> Text("${k.path} row $i") }
                    }
                }
            },
        )
    }

    @Test
    fun breadcrumb_pops_exactly_to_its_folder() {
        show(Home, Browser(""), Browser("Projects"), Browser("Projects/clients"), Browser("Projects/clients/acme"))
        compose.runOnUiThread { back.popTo { it is Browser && it.path == "Projects" } }
        compose.waitForIdle()
        assertEquals(listOf(Home, Browser(""), Browser("Projects")), back.toList())
    }

    @Test
    fun breadcrumb_off_the_stack_changes_nothing() {
        show(Home, Browser("Projects/x"))
        var found = true
        compose.runOnUiThread { found = back.popTo { it is Browser && it.path == "Projects" } }
        assertEquals(false, found)
        assertEquals(2, back.size)
    }

    @Test
    fun scroll_is_restored_per_folder() {
        show(Home, Browser("Projects"))
        compose.onNodeWithTag("list:Projects").performScrollToIndex(40)
        compose.waitForIdle()
        val before = states.getValue("Projects").firstVisibleItemIndex
        compose.runOnUiThread { back.add(Browser("Projects/remoter")) }
        compose.waitForIdle()
        compose.runOnUiThread { back.removeAt(back.lastIndex) }
        compose.waitForIdle()
        assertEquals(before, states.getValue("Projects").firstVisibleItemIndex)
        assertEquals(40, before)
    }

    private fun ev(p: Float) = BackEventCompat(0f, 800f, p, BackEventCompat.EDGE_LEFT)

    @Test
    fun predictive_back_cancelled_stays_in_the_folder() {
        show(Home, Browser("Projects"), Browser("Projects/remoter"))
        val d = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            d.dispatchOnBackStarted(ev(0f))
            d.dispatchOnBackProgressed(ev(0.5f))
            d.dispatchOnBackCancelled()
        }
        compose.waitForIdle()
        assertEquals(3, back.size)
        compose.onNodeWithText("Projects/remoter row 0").assertExists()
    }

    @Test
    fun predictive_back_committed_pops_one_folder() {
        show(Home, Browser("Projects"), Browser("Projects/remoter"))
        val d = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            d.dispatchOnBackStarted(ev(0f))
            d.dispatchOnBackProgressed(ev(0.7f))
            d.onBackPressed()
        }
        compose.waitForIdle()
        assertEquals(listOf(Home, Browser("Projects")), back.toList())
    }

}
