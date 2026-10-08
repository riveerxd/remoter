package me.river.remoter.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import me.river.remoter.core.design.components.Intro
import me.river.remoter.core.design.components.IntroAnchor
import me.river.remoter.core.design.components.IntroTiming
import me.river.remoter.core.design.components.LocalIntroAnchor
import me.river.remoter.core.design.components.introAnchor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class IntroTest {
    @get:Rule val compose = createComposeRule()

    private var play by mutableStateOf(false)
    private var left = 0
    private var done = 0

    private fun show(reduced: Boolean, anchored: Boolean = false, direct: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = reduced) {
                val anchor = remember { IntroAnchor() }
                CompositionLocalProvider(LocalIntroAnchor provides anchor) {
                    Box(Modifier.fillMaxSize().background(Remoter.colors.surface)) {
                        // Stands in for home's route row, high on the screen like the real one.
                        if (anchored) {
                            Column(Modifier.fillMaxSize()) {
                                Spacer(Modifier.height(160.dp))
                                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(80.dp).introAnchor())
                            }
                        }
                        Intro(play, onLeave = { left++ }, onDone = { done++ }, direct = direct)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun holds_the_mark_until_told_to_play() {
        show(reduced = false)
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithTag("intro").assertExists()
        assertEquals(0, done)
    }

    @Test
    fun leaves_then_finishes_on_its_own_timeline() {
        show(reduced = false)
        play = true
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(IntroTiming.LEAVE - 100L)
        assertEquals("home must not start its entrance behind the opaque overlay", 0, left)
        compose.mainClock.advanceTimeBy(150)
        assertEquals(1, left)
        assertEquals(0, done)
        compose.mainClock.advanceTimeBy((IntroTiming.TOTAL - IntroTiming.LEAVE).toLong() + 100)
        assertEquals(1, done)
        assertEquals(1, left)
        assert(IntroTiming.TOTAL < 1_000) { "the whole beat stays under a second" }
    }

    @Test
    fun reduced_motion_draws_nothing_and_finishes_at_once() {
        show(reduced = true)
        compose.onNodeWithTag("intro").assertDoesNotExist()
        play = true
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, left)
        assertEquals(1, done)
    }

    /** Frames to look at by eye: the handoff, the pop, the draw, the pulse landing, the glide. */
    @Test
    fun frames() {
        show(reduced = false, anchored = true)
        compose.onRoot().captureRoboImage("src/test/snapshots/intro_0000.png")
        play = true
        compose.waitForIdle()
        var at = 0L
        for (ms in listOf(120L, 260L, 380L, 520L, 680L, 800L)) {
            compose.mainClock.advanceTimeBy(ms - at)
            at = ms
            compose.onRoot().captureRoboImage("src/test/snapshots/intro_%04d.png".format(ms))
        }
    }

    @Test
    fun direct_frames() {
        show(reduced = false, anchored = true, direct = true)
        play = true
        compose.waitForIdle()
        var at = 0L
        for (ms in listOf(380L, 520L, 680L)) {
            compose.mainClock.advanceTimeBy(ms - at)
            at = ms
            compose.onRoot().captureRoboImage("src/test/snapshots/intro_direct_%04d.png".format(ms))
        }
    }
}
