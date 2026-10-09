package me.river.remoter.feature.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.components.RemoterSheet
import me.river.remoter.core.design.shots.spoken
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReadingOrderTest {
    @get:Rule val compose = createComposeRule()

    private fun show(name: String) {
        val u = startStates.first { it.first == name }.second
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { Box(Modifier.fillMaxSize()) { RemoterSheet(true, {}) { StartContent(u, StartCallbacks()) } } } }
        compose.waitForIdle()
    }

    @Test
    fun form_reads_top_to_bottom() {
        show("idle")
        val s = compose.spoken()
        val order = listOf("remoter", "Same folder", "Worktree", "Name", "Bypass permissions", "Start session")
        val at = order.map { w -> s.indexOfFirst { it.contains(w) } }
        assertTrue("reading order was $s", at.all { it >= 0 } && at == at.sorted())
    }

    @Test
    fun stepper_is_live_region() {
        show("starting_accepted")
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite), useUnmergedTree = true).assertExists()
    }
}
