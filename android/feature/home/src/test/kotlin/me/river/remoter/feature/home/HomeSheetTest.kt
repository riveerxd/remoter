package me.river.remoter.feature.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.RemoterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// pulled all the way up, the sheet once couldn't be dragged or backed down, and + New was
// drawn over it. screen 891 dp, peek 440 dp, so a collapsed sheet's top sits near 451 dp
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(cb: HomeCallbacks = HomeCallbacks()) =
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { HomeContent(homeStates.getValue("up_sessions"), cb, entrance = false) } }

    private fun headerTop() = compose.onNodeWithText("Sessions").getUnclippedBoundsInRoot().top

    private fun expand() {
        // the handle, just below the sheet's top edge
        val handleY = headerTop() - 40.dp
        compose.onRoot().performTouchInput { swipeUp(startY = handleY.toPx(), endY = 20.dp.toPx(), durationMillis = 400) }
        compose.waitForIdle()
        assertTrue("header at ${headerTop()}", headerTop() < 200.dp)
    }

    private fun assertCollapsed() {
        compose.waitForIdle()
        assertTrue("header at ${headerTop()}", headerTop() > 400.dp)
    }

    @Test
    fun expanded_sheet_drags_down() {
        show()
        expand()
        compose.onNodeWithText("Sessions").performTouchInput { swipeDown(startY = centerY, endY = centerY + 700f * density, durationMillis = 300) }
        assertCollapsed()
    }

    @Test
    fun back_collapses_sheet() {
        show()
        expand()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertCollapsed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun new_moves_to_header_when_raised() {
        show()
        assertEquals("floating one only", 1, compose.newNodes().size)
        expand()
        val sheetTop = headerTop() - 48.dp
        val shown = compose.newNodes().filter { it.isDisplayed() }
        assertEquals("header one only", 1, shown.size)
        assertTrue("over the sheet", shown.single().getUnclippedBoundsInRoot().top >= sheetTop)
    }

    @Test
    fun new_above_sheet_at_peek() {
        show()
        val sheetTop = headerTop() - 48.dp
        compose.newNodes().forEach { assertTrue("overlaps the sheet", it.getUnclippedBoundsInRoot().bottom <= sheetTop) }
    }

    @Test
    fun pull_refreshes_at_peek() {
        var refreshed = false
        show(HomeCallbacks(onRefresh = { refreshed = true }))
        compose.onNodeWithText("Sessions").performTouchInput { swipeDown(startY = centerY, endY = centerY + 300f * density, durationMillis = 400) }
        compose.waitForIdle()
        assertTrue(refreshed)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.newNodes(): List<androidx.compose.ui.test.SemanticsNodeInteraction> {
        val m = androidx.compose.ui.test.hasContentDescription("New session")
        val n = onAllNodes(m).fetchSemanticsNodes().size
        return (0 until n).map { onAllNodes(m)[it] }
    }
}
