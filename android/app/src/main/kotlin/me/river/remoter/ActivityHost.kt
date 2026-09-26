package me.river.remoter

import androidx.fragment.app.FragmentActivity
import me.river.remoter.core.crypto.PromptHost
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/** The resumed activity, for the fingerprint prompt. Weak, so a finished activity is never held. */
@Singleton
class ActivityHost @Inject constructor() : PromptHost {
    private var ref = WeakReference<FragmentActivity>(null)
    fun set(a: FragmentActivity?) {
        ref = WeakReference(a)
    }
    override fun current(): FragmentActivity? = ref.get()
}
