package me.river.remoter.feature.session

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.components.RemoterSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SheetBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val dismissed = mutableIntStateOf(0)
    private val visible = mutableStateOf(true)

    private fun show() = compose.setContent {
        RemoterTheme(dark = true, reducedMotion = true) {
            Box(Modifier.fillMaxSize()) {
                RemoterSheet(visible.value, { dismissed.intValue++; visible.value = false }) { Text("sheet body") }
            }
        }
    }

    private fun ev(p: Float) = BackEventCompat(10f, 500f, p, BackEventCompat.EDGE_LEFT)

    @Test
    fun cancelled_back_gesture_keeps_the_sheet() {
        show()
        compose.waitForIdle()
        val d = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            d.dispatchOnBackStarted(ev(0f))
            d.dispatchOnBackProgressed(ev(0.6f))
            d.dispatchOnBackCancelled()
        }
        compose.waitForIdle()
        assertEquals(0, dismissed.intValue)
        compose.onNodeWithTextCompat("sheet body")
    }

    @Test
    fun committed_back_gesture_dismisses_once() {
        show()
        compose.waitForIdle()
        val d = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            d.dispatchOnBackStarted(ev(0f))
            d.dispatchOnBackProgressed(ev(0.8f))
            d.onBackPressed()
        }
        compose.waitForIdle()
        assertEquals(1, dismissed.intValue)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onNodeWithTextCompat(t: String) =
        onNode(androidx.compose.ui.test.hasText(t)).assertExists()
}
