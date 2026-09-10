package me.river.remoter.core.design

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.components.RemoterSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Start sheet only closed when dragged by its 4 dp handle:
 * the content scrolls, and the scroller swallowed every drag that began on it.
 * A thumb grabs a sheet anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SheetDragTest {
    @get:Rule val compose = createComposeRule()

    private var dismissed = 0

    private fun show(tall: Boolean = false) = compose.setContent {
        RemoterTheme(dark = true, reducedMotion = true) {
            var visible by remember { mutableStateOf(true) }
            RemoterSheet(visible = visible, onDismiss = { dismissed++; visible = false }) {
                Text("Sheet body")
                if (tall) repeat(30) { Text("Row $it", Modifier.fillMaxWidth().height(56.dp)) }
            }
        }
    }

    @Test
    fun dragging_the_content_down_dismisses_the_sheet() {
        show()
        compose.waitForIdle()
        compose.onNodeWithText("Sheet body").performTouchInput { swipeDown(startY = centerY, endY = centerY + 600f * density, durationMillis = 300) }
        compose.waitForIdle()
        assertEquals("a drag that starts on the content must close the sheet", 1, dismissed)
    }

    @Test
    fun a_small_drag_on_the_content_springs_back() {
        show()
        compose.waitForIdle()
        compose.onNodeWithText("Sheet body").performTouchInput { swipeDown(startY = centerY, endY = centerY + 20f * density, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(0, dismissed)
    }

    @Test
    fun scrolled_content_scrolls_back_before_the_sheet_moves() {
        show(tall = true)
        compose.waitForIdle()
        // Scroll the tall content down a bit, then drag down a little less than that:
        // the content should scroll back, and the sheet must stay open.
        compose.onNodeWithText("Row 3").performTouchInput { swipeUp(startY = centerY, endY = centerY - 300f * density, durationMillis = 500) }
        compose.waitForIdle()
        compose.onNodeWithText("Row 8").performTouchInput { swipeDown(startY = centerY, endY = centerY + 120f * density, durationMillis = 500) }
        compose.waitForIdle()
        assertEquals("scrolling content back to its top must not close the sheet", 0, dismissed)
    }
}
