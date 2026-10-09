package me.river.remoter.feature.session

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.components.RemoterSheet
import me.river.remoter.core.design.shots.HapticRecorder
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ErrorFeedbackTest {
    @get:Rule val compose = createComposeRule()
    private val view = HapticRecorder(ApplicationProvider.getApplicationContext())

    private fun ui(state: StartState) = StartUi(open = true, target = StartTarget("Projects/remoter", "remoter", true), form = StartForm(name = "remoter"), state = state, hostname = "r1v3r")

    private fun sheet(u: StartUi) = compose.setContent {
        CompositionLocalProvider(LocalView provides view) {
            RemoterTheme(dark = true, reducedMotion = true) {
                Box(Modifier.fillMaxSize()) { RemoterSheet(true, {}) { StartContent(u, StartCallbacks()) } }
            }
        }
    }

    @Test
    fun opening_sheet_no_threshold_haptic() {
        compose.setContent {
            CompositionLocalProvider(LocalView provides view) {
                RemoterTheme(dark = true, reducedMotion = true) { Box(Modifier.fillMaxSize()) { RemoterSheet(true, {}) { Text("body") } } }
            }
        }
        compose.waitForIdle()
        assertFalse(view.fired.contains(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE))
    }

    @Test
    fun open_claude_shows_before_links_arrive() {
        sheet(ui(StartState.Ready(SessionId("rc-a"), "remoter", null)))
        compose.onNodeWithText("Open in Claude").assertIsDisplayed()
    }

    private fun rejects(e: AppError) {
        sheet(ui(StartState.NotAccepted(e, null)))
        compose.waitForIdle()
        assertTrue("$e: ${view.fired}", view.fired.contains(HapticFeedbackConstants.REJECT))
    }

    @Test fun locked_buzzes_reject() = rejects(AppError.Locked)
    @Test fun security_buzzes_reject() = rejects(AppError.Security(ErrorCode.SigInvalid, "r"))
    @Test fun session_cap_buzzes_reject() = rejects(AppError.SessionCap(emptyList()))
    @Test fun fingerprint_lockout_buzzes_reject() = rejects(AppError.FingerprintLockedOut)

    @Test
    fun unreachable_is_not_refusal() {
        sheet(ui(StartState.NotAccepted(AppError.Unreachable, 1)))
        compose.waitForIdle()
        assertFalse(view.fired.contains(HapticFeedbackConstants.REJECT))
    }

    @Test
    fun rate_limit_body_has_no_frozen_number() {
        val c = AppError.RateLimited(17).copy("r1v3r")
        assertFalse(c.body.orEmpty().contains("17"))
    }
}
