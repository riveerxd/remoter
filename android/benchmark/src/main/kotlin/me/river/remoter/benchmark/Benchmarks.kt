package me.river.remoter.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Benchmark build type. Only S25 numbers count, see check_thresholds.py. */
@RunWith(AndroidJUnit4::class)
class Benchmarks {
    @get:Rule val rule = MacrobenchmarkRule()

    private val mode = CompilationMode.Partial()

    @Test
    fun coldStartToHome() = rule.measureRepeated(PACKAGE, listOf(StartupTimingMetric()), compilationMode = mode, startupMode = StartupMode.COLD, iterations = 5) {
        pressHome()
        startHome()
    }

    @Test
    fun folderTransitions() = rule.measureRepeated(PACKAGE, listOf(FrameTimingMetric()), compilationMode = mode, startupMode = StartupMode.WARM, iterations = 5, setupBlock = { startHome() }) {
        intoFoldersAndBack()
    }

    @Test
    fun sheetDrag() = rule.measureRepeated(PACKAGE, listOf(FrameTimingMetric()), compilationMode = mode, startupMode = StartupMode.WARM, iterations = 5, setupBlock = { startHome() }) {
        dragStartSheet()
    }

    @Test
    fun homeScroll() = rule.measureRepeated(PACKAGE, listOf(FrameTimingMetric()), compilationMode = mode, startupMode = StartupMode.WARM, iterations = 5, setupBlock = { startHome() }) {
        scrollHome()
    }
}

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(PACKAGE) {
        pressHome()
        startHome()
        intoFoldersAndBack()
        dragStartSheet()
        scrollHome()
    }
}
