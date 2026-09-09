package me.river.remoter.core.design

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

val LocalHapticsEnabled = staticCompositionLocalOf { true }

/** Each one means one thing. No buzz on fingerprint success, the system prompt has its own. */
class Haptics(private val view: View, private val enabled: Boolean) {
    /** session ready, pairing done */
    fun confirm() = fire(HapticFeedbackConstants.CONFIRM)

    /** stuck, refused, locked */
    fun reject() = fire(HapticFeedbackConstants.REJECT)

    /** a Starting step lands */
    fun tick() = fire(HapticFeedbackConstants.SEGMENT_TICK)

    /** pull to refresh / sheet dismiss crossing its threshold */
    fun threshold() = fire(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)

    private fun fire(c: Int) {
        if (enabled) view.performHapticFeedback(c)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { Haptics(view, enabled) }
}
