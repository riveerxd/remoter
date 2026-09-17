package me.river.remoter.feature.session

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import kotlinx.collections.immutable.persistentListOf
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.shots.clippedText
import me.river.remoter.core.design.shots.unlabelledClickables
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DetailA11yTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun detail_labelled_and_unclipped_at_200_percent() {
        val s = SessionSummary("rc-a", "remoter", "Projects/remoter", null, 0, SessionState.Ready, null, null)
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) {
                RemoterTheme(dark = true, reducedMotion = true) {
                    SessionDetailContent(DetailUi(s, persistentListOf("line one", "line two"), 0, nowMs = 60_000, hostname = "r1v3r"), {}, {}, {}, {})
                }
            }
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), compose.unlabelledClickables())
        assertEquals(emptyList<String>(), compose.clippedText())
    }
}
