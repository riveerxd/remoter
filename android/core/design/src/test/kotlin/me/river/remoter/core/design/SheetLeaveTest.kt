package me.river.remoter.core.design

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import me.river.remoter.core.design.components.LocalSheetSlot
import me.river.remoter.core.design.components.RemoterSheet
import me.river.remoter.core.design.components.RemoterSheetFor
import me.river.remoter.core.design.components.SheetSlot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// a menu emptied itself while it slid away, and starting from a menu slid it out under the
// Start sheet: two sheets and two scrims at once
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SheetLeaveTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun closing_sheet_keeps_its_content() {
        val item = mutableStateOf<String?>("Start here")
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = false) {
                RemoterSheetFor(item.value, onDismiss = { item.value = null }) { Text(it) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        item.value = null
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Start here").assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Start here").assertDoesNotExist()
    }

    @Test
    fun replaced_sheet_leaves_at_once() {
        val menu = mutableStateOf(true)
        val start = mutableStateOf(false)
        val slot = SheetSlot()
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = false) {
                CompositionLocalProvider(LocalSheetSlot provides slot) {
                    RemoterSheet(visible = menu.value, onDismiss = { menu.value = false }) { Text("Menu") }
                    RemoterSheet(visible = start.value, onDismiss = { start.value = false }) { Text("Start sheet") }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        start.value = true
        // its own exit is 180 ms. frame by frame because the handoff runs through effects,
        // which a single jump skips
        repeat(6) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Menu").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Start sheet").assertExists()
    }
}
