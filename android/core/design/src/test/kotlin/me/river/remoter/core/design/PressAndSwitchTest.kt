package me.river.remoter.core.design

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.Indication
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.components.RemoterSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PressAndSwitchTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theme_swaps_ripple_for_press() {
        var indication: Indication? = null
        var taps = 0
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = false) {
                indication = LocalIndication.current
                Box(Modifier.size(48.dp).clickable { taps++ }) { Text("tap me") }
            }
        }
        compose.onNodeWithText("tap me").performClick()
        compose.onNodeWithText("tap me").performClick()
        assertEquals(2, taps)
        assertTrue("$indication", indication is PressIndication)
    }

    @Test
    fun switch_follows_its_row() {
        compose.setContent {
            RemoterTheme(dark = false, reducedMotion = false) {
                var on by remember { mutableStateOf(false) }
                Row(Modifier.toggleable(on, role = Role.Switch) { on = it }) {
                    Text("Haptics")
                    RemoterSwitch(on)
                }
            }
        }
        compose.onNodeWithText("Haptics", useUnmergedTree = true).assertExists()
        val row = compose.onNodeWithText("Haptics")
        row.assertIsOff()
        row.performClick()
        row.assertIsOn()
        row.performClick()
        row.assertIsOff()
    }
}
