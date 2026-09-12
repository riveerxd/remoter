package me.river.remoter.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.components.RemoterSnackbar
import me.river.remoter.core.design.components.RemoterSnackbarHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Snackbars used to pop in and out with no motion, four of them could sit on the
 * same spot at once, and none held still under a finger.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SnackbarHostTest {
    @get:Rule val compose = createComposeRule()

    private var first by mutableStateOf(true)
    private var second by mutableStateOf(false)
    private var inset by mutableStateOf(0.dp)
    private var dismissed = 0

    private fun show(reduced: Boolean = true, autoMs: Long? = null) {
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = reduced) {
                RemoterSnackbarHost(bottomInset = inset) {
                    Box(Modifier.fillMaxSize()) {
                        if (first) RemoterSnackbar("first news", { dismissed++; first = false }, autoDismissMs = autoMs)
                        if (second) RemoterSnackbar("second news", { second = false })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun older_comes_back_after_the_newer() {
        show()
        second = true
        compose.waitForIdle()
        compose.onNodeWithText("second news").assertExists()
        compose.onNodeWithText("first news").assertDoesNotExist()
        second = false
        compose.waitForIdle()
        compose.onNodeWithText("first news").assertExists()
    }

    @Test
    fun it_leaves_with_an_exit_instead_of_vanishing() {
        show(reduced = false)
        compose.mainClock.autoAdvance = false
        first = false
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("first news").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("first news").assertDoesNotExist()
    }

    @Test
    fun the_countdown_holds_while_a_finger_is_on_it() {
        show(autoMs = 5_000)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("first news").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(8_000)
        assertEquals("held for 8 s, still up", 0, dismissed)
        compose.onNodeWithText("first news").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(4_000)
        assertEquals(0, dismissed)
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals("the full 5 s runs once the finger lifts", 1, dismissed)
    }

    @Test
    fun a_buried_snackbar_does_not_count_down() {
        second = true
        show(autoMs = 5_000)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(8_000)
        assertEquals("it was never on screen, so its time never ran", 0, dismissed)
        second = false
        // The host hears about the removal from a dispose, which Robolectric only delivers on idle.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(6_000)
        assertEquals(1, dismissed)
    }

    @Test
    fun the_inset_lifts_it_clear_of_a_bottom_bar() {
        show()
        fun bottom(): Dp = compose.onNodeWithText("first news").getUnclippedBoundsInRoot().bottom
        val low = bottom()
        inset = 88.dp
        compose.waitForIdle()
        val root = compose.onRoot().getUnclippedBoundsInRoot().bottom
        assertTrue("snackbar bottom ${bottom()} must clear an 88 dp bar ending at $root", bottom() <= root - 88.dp)
        assertTrue(bottom() < low)
    }
}
