package me.river.remoter.feature.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import me.river.remoter.core.design.shots.Variant
import me.river.remoter.core.design.shots.shot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingScreenshots(private val v: Variant) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = Variant.params()
    }

    @get:Rule val compose = createComposeRule()

    private fun one(k: String, s: OnboardingStep, cam: CameraAccess = CameraAccess.Denied) =
        compose.shot("onboarding_$k", v) { OnboardingContent(s, cam, OnboardingCallbacks(), showCamera = false) }

    @Test fun connect_waiting() = one("connect_waiting", OnboardingStep.Connect(false, false))
    @Test fun connect_tunnel() = one("connect_tunnel", OnboardingStep.Connect(true, false))
    @Test fun scan_camera_denied() = one("scan_camera_denied", OnboardingStep.Scan())
    @Test fun scan_denied_forever() = one("scan_denied_forever", OnboardingStep.Scan(), CameraAccess.DeniedForever)
    @Test fun scan_rejected() = one("scan_rejected", OnboardingStep.Scan(rejected = true))
    @Test fun paste() = one("paste", OnboardingStep.Scan(pasting = true))
    @Test fun paste_invalid() = one("paste_invalid", OnboardingStep.Scan(pasting = true, pasteInvalid = true))
    @Test fun confirm() = one("confirm", OnboardingStep.Confirm("481207", "A1F309CE"))
    @Test fun stop_expired() = one("stop_expired", OnboardingStep.Stop(HardStop.Expired))
    @Test fun stop_mismatch() = one("stop_mismatch", OnboardingStep.Stop(HardStop.ServerKeyMismatch))
    @Test fun stop_no_strongbox() = one("stop_no_strongbox", OnboardingStep.Stop(HardStop.NoStrongBox))
    @Test fun pair_again() = one("pair_again", OnboardingStep.PairAgain(PairAgainReason.KeyInvalidated))
}
