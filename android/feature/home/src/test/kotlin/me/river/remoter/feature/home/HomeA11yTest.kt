package me.river.remoter.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeA11yTest(private val name: String) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = homeStates.keys.map { arrayOf<Any>(it) }
    }

    @get:Rule val compose = createComposeRule()

    @Test
    fun labelled_and_unclipped_at_200_percent() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) {
                RemoterTheme(dark = true, reducedMotion = true) { HomeContent(homeStates.getValue(name), HomeCallbacks(), false) }
            }
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), compose.unlabelledClickables())
        assertEquals(emptyList<String>(), compose.clippedText())
    }
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BannerSpeechTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun banner_reads_status() {
        val now = 1_790_620_000_000
        val s = SessionSummary("rc-a", "remoter", "Projects/remoter", null, now - (83 * 60 + 7) * 1000L, SessionState.Ready, null, null)
        val ui = homeStates.getValue("up_no_sessions").copy(sessions = persistentListOf(s), nowMs = now)
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { HomeContent(ui, HomeCallbacks(), false) } }
        compose.onNodeWithContentDescription("Session remoter, ready, running 1 hour 23 minutes").assertExists()
    }
}
