package me.river.remoter.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.shots.Variant
import me.river.remoter.core.design.shots.clippedText
import me.river.remoter.core.design.shots.shot
import me.river.remoter.core.design.shots.unlabelledClickables
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ErrorCode
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal val procsStates = mapOf(
    "list" to procsUi,
    "by_memory" to procsUi.copy(sort = ProcSort.Memory, procs = procsUi.procs.sortedFor(ProcSort.Memory).toImmutableList()),
    "sheet" to procsUi.copy(selected = procList.first { it.name == "firefox" }),
    "sheet_session" to procsUi.copy(selected = procList.first()),
    "sheet_root" to procsUi.copy(selected = procList.first { it.name == "sshd" }),
    "sheet_refused" to procsUi.copy(selected = procList.first { it.name == "firefox" }, signalError = AppError.Denied(ErrorCode.ProcessDenied)),
    "stale" to procsUi.copy(error = AppError.Unreachable),
    "loading" to ProcsUi(host = "r1v3r"),
)

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ProcessesScreenshots(private val v: Variant) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = Variant.params()
    }

    @get:Rule val compose = createComposeRule()

    private fun one(k: String) = compose.shot("procs_$k", v) { ProcessesContent(procsStates.getValue(k), ProcsCallbacks()) }

    @Test fun list() = one("list")
    @Test fun by_memory() = one("by_memory")
    @Test fun sheet() = one("sheet")
    @Test fun sheet_session() = one("sheet_session")
    @Test fun sheet_root() = one("sheet_root")
    @Test fun sheet_refused() = one("sheet_refused")
    @Test fun stale() = one("stale")
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ProcessesA11yTest(private val name: String) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = procsStates.keys.map { arrayOf<Any>(it) }
    }

    @get:Rule val compose = createComposeRule()

    @Test
    fun labelled_and_unclipped_at_200_percent() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) {
                RemoterTheme(dark = true, reducedMotion = true) { ProcessesContent(procsStates.getValue(name), ProcsCallbacks()) }
            }
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), compose.unlabelledClickables())
        // command lines trim on purpose, the sheet shows them whole
        val cmds = procList.map { it.cmd }.toSet()
        assertEquals(emptyList<String>(), compose.clippedText { it in cmds })
    }
}
