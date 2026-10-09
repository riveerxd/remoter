package me.river.remoter.feature.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.components.RemoterSheet
import me.river.remoter.core.design.shots.clippedText
import me.river.remoter.core.design.shots.unlabelledClickables
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class StartA11yTest(private val name: String) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = (startStates + resumeStates.map { "resume_${it.first}" to it.second }).map { arrayOf<Any>(it.first) }
    }

    @get:Rule val compose = createComposeRule()

    @Test
    fun labelled_and_unclipped_at_200_percent() {
        val u = (startStates + resumeStates.map { "resume_${it.first}" to it.second }).first { it.first == name }.second
        // the last prompt is a one line preview on purpose
        val previews = (u.past as? PastState.Loaded)?.rows?.mapNotNull { it.conversation.lastPrompt }.orEmpty().toSet()
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) {
                RemoterTheme(dark = true, reducedMotion = true) {
                    Box(Modifier.fillMaxSize()) { RemoterSheet(true, {}) { StartContent(u, StartCallbacks(errors = ErrorActions(onLockLaptop = {}, onPairAgain = {}, onOpenDateSettings = {}, onOpenWireGuard = {}, onEnd = {}))) } }
                }
            }
        }
        compose.waitForIdle()
        assertEquals("unlabelled", emptyList<String>(), compose.unlabelledClickables())
        // terminal lines scroll sideways, they never wrap
        assertEquals("clipped", emptyList<String>(), compose.clippedText { it.startsWith("~/") || it.startsWith("…/") || u.state.hasTail() || it in previews })
    }

    private fun StartState.hasTail() = this is StartState.Stuck || this is StartState.Exited
}
