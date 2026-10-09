package me.river.remoter.core.design.shots

import android.content.Context
import android.view.View

class HapticRecorder(context: Context) : View(context) {
    val fired = mutableListOf<Int>()
    override fun performHapticFeedback(feedbackConstant: Int): Boolean {
        fired += feedbackConstant
        return true
    }
}
