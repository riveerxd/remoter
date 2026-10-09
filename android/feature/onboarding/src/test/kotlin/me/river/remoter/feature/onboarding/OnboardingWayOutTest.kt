package me.river.remoter.feature.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.river.remoter.core.design.RemoterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// screens that used to be dead ends, a message and nothing to press
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingWayOutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun key_mismatch_offers_start_over() {
        var started = 0
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) {
                OnboardingContent(OnboardingStep.Stop(HardStop.ServerKeyMismatch), CameraAccess.Denied, OnboardingCallbacks(onStartOver = { started++ }), false)
            }
        }
        compose.onNodeWithText("Start over").performClick()
        assertEquals(1, started)
    }

    @Test
    fun paste_mode_offers_scan_instead() {
        var back = 0
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) {
                OnboardingContent(OnboardingStep.Scan(pasting = true), CameraAccess.Denied, OnboardingCallbacks(onScanInstead = { back++ }), false)
            }
        }
        compose.onNodeWithText("Scan instead").performClick()
        assertEquals(1, back)
    }

    @Test
    fun paste_fills_from_clipboard() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", "remoter://pair?v=1"))
        var edited = 0
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) {
                OnboardingContent(OnboardingStep.Scan(pasting = true), CameraAccess.Denied, OnboardingCallbacks(onLinkEdited = { edited++ }), false)
            }
        }
        compose.onNodeWithText("Paste").performClick()
        compose.onNodeWithText("remoter://pair?v=1").assertExists()
        assertEquals(1, edited)
    }
}
