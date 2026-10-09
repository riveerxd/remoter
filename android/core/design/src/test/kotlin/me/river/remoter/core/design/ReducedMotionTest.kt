package me.river.remoter.core.design

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// the helpers once animated regardless, so a skeleton sat invisible for a third of a second
@RunWith(RobolectricTestRunner::class)
class ReducedMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun helpers_land_on_the_next_frame() {
        val on = mutableStateOf(false)
        var alpha = -1f
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) {
                Column {
                    FadeSwap(on.value) { Text(if (it) "swapped in" else "swapped out") }
                    Appear(on.value) { Text("appeared") }
                    FadeInPlace(on.value) { Text("faded in") }
                    AnimatedItems(if (on.value) listOf("row") else emptyList(), key = { it }) { Text(it) }
                    alpha = animatedAlpha(on.value)
                    Text("probe", Modifier.alpha(alpha))
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        on.value = true
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(48)

        compose.onNodeWithText("swapped out").assertDoesNotExist()
        compose.onNodeWithText("swapped in").assertIsDisplayed()
        compose.onNodeWithText("appeared").assertIsDisplayed()
        compose.onNodeWithText("faded in").assertIsDisplayed()
        compose.onNodeWithText("row").assertIsDisplayed()
        assertEquals(1f, alpha)
    }
}
