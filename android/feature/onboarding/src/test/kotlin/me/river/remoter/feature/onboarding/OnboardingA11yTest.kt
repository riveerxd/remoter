package me.river.remoter.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.shots.clippedText
import me.river.remoter.core.design.shots.unlabelledClickables
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

private val screens: Map<String, @Composable () -> Unit> = mapOf(
    "connect" to { OnboardingContent(OnboardingStep.Connect(true, false), CameraAccess.Denied, OnboardingCallbacks(), false) },
    "scan_denied" to { OnboardingContent(OnboardingStep.Scan(), CameraAccess.Denied, OnboardingCallbacks(), false) },
    "scan_forever" to { OnboardingContent(OnboardingStep.Scan(), CameraAccess.DeniedForever, OnboardingCallbacks(), false) },
    "paste" to { OnboardingContent(OnboardingStep.Scan(pasting = true, pasteInvalid = true), CameraAccess.Denied, OnboardingCallbacks(), false) },
    "confirm" to { OnboardingContent(OnboardingStep.Confirm("481207", "A1F309CE"), CameraAccess.Denied, OnboardingCallbacks(), false) },
    "stop" to { OnboardingContent(OnboardingStep.Stop(HardStop.ServerKeyMismatch), CameraAccess.Denied, OnboardingCallbacks(), false) },
    "pair_again" to { OnboardingContent(OnboardingStep.PairAgain(PairAgainReason.KeyInvalidated), CameraAccess.Denied, OnboardingCallbacks(), false) },
)

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingA11yTest(private val name: String) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = screens.keys.map { arrayOf<Any>(it) }
    }

    @get:Rule val compose = createComposeRule()

    @Test
    fun labelled_and_unclipped_at_200_percent() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) { RemoterTheme(dark = true, reducedMotion = true) { screens.getValue(name)() } }
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), compose.unlabelledClickables())
        assertEquals(emptyList<String>(), compose.clippedText())
    }
}
