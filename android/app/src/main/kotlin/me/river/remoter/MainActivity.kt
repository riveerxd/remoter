package me.river.remoter

import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import android.view.View
import android.view.animation.PathInterpolator
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import dagger.hilt.android.AndroidEntryPoint
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.components.Intro
import me.river.remoter.core.design.components.IntroAnchor
import me.river.remoter.core.design.components.LocalIntroAnchor
import me.river.remoter.core.net.LocalStore
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var store: LocalStore
    @Inject lateinit var hooks: IntentHooks
    @Inject lateinit var host: ActivityHost
    private val root: RootViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        host.set(this)
    }

    override fun onPause() {
        if (host.current() === this) host.set(null)
        super.onPause()
    }

    /** Flips when the splash starts leaving; home plays its stagger then, never behind the splash. */
    private val entrance = mutableStateOf(false)

    private enum class IntroPhase { Off, Hold, Play }

    /**
     * Hold draws the finished mark under the system splash from the first frame, so removing
     * the splash view shows the same pixels and the intro can take over from there.
     */
    private val intro = mutableStateOf(IntroPhase.Off)
    private val anchor = IntroAnchor()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        hooks.apply(intent)
        val t0 = SystemClock.uptimeMillis()
        // Local state only, capped at 800 ms. Never the network: a splash stuck on a sleeping laptop is the worst case.
        var capArmed = false
        splash.setKeepOnScreenCondition {
            val keep = store.state.value == null && SystemClock.uptimeMillis() - t0 < KEEP_CAP_MS
            // Some launches never show a splash, so its exit never comes and the held overlay must
            // not outlive that. Counted from here, not onCreate: a slow first frame is not a lost exit.
            if (!keep && !capArmed) {
                capArmed = true
                window.decorView.postDelayed({
                    if (intro.value == IntroPhase.Hold) {
                        intro.value = IntroPhase.Off
                        entrance.value = true
                    }
                }, HOLD_CAP_MS)
            }
            keep
        }
        splash.setOnExitAnimationListener(::exitSplash)
        enableEdgeToEdge()
        // cold start only, a rotation or restored process has no splash to hand off from
        if (savedInstanceState == null && !reducedMotion()) {
            intro.value = IntroPhase.Hold
        }
        setContent {
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalIntroAnchor provides anchor) {
                    RemoterRoot(entrance.value) { entrance.value = true }
                }
                if (intro.value != IntroPhase.Off) {
                    RemoterTheme(dark = true) {
                        Intro(
                            play = intro.value == IntroPhase.Play,
                            onLeave = { entrance.value = true },
                            onDone = { intro.value = IntroPhase.Off },
                            // observed: the store can still be loading on the first frame
                            direct = store.state.collectAsState().value?.snapshot?.direct == true,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        hooks.apply(intent)
    }

    private fun reducedMotion() =
        SystemSettings.Global.getFloat(contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun exitSplash(p: SplashScreenViewProvider) {
        var removed = false
        fun remove() {
            if (!removed) {
                removed = true
                p.remove()
            }
        }
        try {
            val icon: View? = runCatching { p.iconView }.getOrNull()
            // let the dot land first or the whole thing looks cut off
            val remaining = max(0L, p.iconAnimationStartMillis + p.iconAnimationDurationMillis - SystemClock.uptimeMillis())
            val wait = min(remaining, MAX_WAIT_MS)
            val view = p.view
            view.postDelayed({
                try {
                    val held = intro.value == IntroPhase.Hold
                    val paired = store.state.value?.laptop != null
                    when {
                        reducedMotion() -> {
                            intro.value = IntroPhase.Off
                            view.animate().alpha(0f).setDuration(150).withEndAction(::remove).start()
                        }
                        // The overlay already shows this frame, so the splash can go at once.
                        held && paired -> {
                            intro.value = IntroPhase.Play
                            remove()
                        }
                        // onboarding has no route yet, keep the short rise
                        else -> {
                            intro.value = IntroPhase.Off
                            entrance.value = true
                            val rise = -16f * resources.displayMetrics.density
                            icon?.animate()?.translationY(rise)?.alpha(0f)?.setDuration(180)?.setInterpolator(PathInterpolator(0.7f, 0f, 0.84f, 0f))?.start()
                            view.animate().alpha(0f).setDuration(250).withEndAction(::remove).start()
                        }
                    }
                } catch (e: Throwable) {
                    remove()
                    throw e
                }
            }, wait)
        } catch (e: Throwable) {
            remove()
            throw e
        }
    }

    companion object {
        const val KEEP_CAP_MS = 800L
        const val MAX_WAIT_MS = 250L

        /** From the splash's release, well past MAX_WAIT_MS: by then a splash exit that was coming has come. */
        const val HOLD_CAP_MS = 1_000L
    }
}
