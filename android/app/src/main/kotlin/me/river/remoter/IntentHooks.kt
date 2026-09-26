package me.river.remoter

import android.content.Intent

// fixture steering for the debug build; release binds a no-op
fun interface IntentHooks {
    fun apply(intent: Intent?)
}
