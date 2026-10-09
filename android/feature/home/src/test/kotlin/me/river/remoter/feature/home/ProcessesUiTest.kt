package me.river.remoter.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.collections.immutable.toImmutableList
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.Proc
import me.river.remoter.core.net.ProcSession
import me.river.remoter.core.net.Signal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun proc(pid: Int, name: String, cpu: Double, rss: Long, killable: Boolean = true, session: String? = null, user: String = "river") =
    Proc(pid, 1, 1_000L + pid, user, name, "/usr/bin/$name --flag", cpu, rss, killable, session?.let { ProcSession("rc-$it", it) })

internal val procList = listOf(
    proc(4242, "claude", 104.5, 512_000_000, session = "remoter"),
    proc(3125, "rustc", 96.3, 1_400_000_000),
    proc(2210, "firefox", 18.2, 2_400_000_000),
    proc(4252, "claude", 3.1, 380_000_000, session = "api"),
    proc(1730, "Hyprland", 4.8, 240_000_000),
    proc(2050, "java", 2.2, 3_200_000_000),
    proc(811, "sshd", 0.0, 9_000_000, killable = false, user = "root"),
    proc(1, "systemd", 0.0, 14_000_000, killable = false, user = "root"),
)

internal val procsUi = ProcsUi(host = "r1v3r", resources = load, procs = procList.sortedFor(ProcSort.Cpu).toImmutableList(), loaded = true)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ProcessesUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rows_say_their_numbers_and_their_session() {
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { ProcessesContent(procsUi, ProcsCallbacks()) } }
        compose.onNodeWithContentDescription("claude, CPU 104.5%, memory 488 MB, remoter session remoter").assertIsDisplayed()
        compose.onNodeWithContentDescription("rustc, CPU 96.3%, memory 1.3 GB").assertIsDisplayed()
    }

    @Test
    fun a_row_opens_its_sheet_and_the_buttons_signal() {
        var ui by mutableStateOf(procsUi)
        val sent = mutableListOf<Pair<Int, Signal>>()
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) {
                ProcessesContent(ui, ProcsCallbacks(onSelect = { ui = ui.copy(selected = it) }, onSignal = { p, s -> sent += p.pid to s }))
            }
        }
        compose.onNodeWithContentDescription("firefox", substring = true).performClick()
        compose.onNodeWithText("Quit (SIGTERM)").performClick()
        compose.onNodeWithText("Kill (SIGKILL)").performClick()
        assertEquals(listOf(2210 to Signal.Term, 2210 to Signal.Kill), sent)
    }

    @Test
    fun someone_elses_process_has_no_buttons() {
        val sshd = procList.first { it.name == "sshd" }
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { ProcessesContent(procsUi.copy(selected = sshd), ProcsCallbacks()) } }
        compose.onNodeWithText("it runs as root", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Quit (SIGTERM)").assertDoesNotExist()
        compose.onNodeWithText("Kill (SIGKILL)").assertDoesNotExist()
    }

    @Test
    fun sorting_is_a_tap() {
        var sorted: ProcSort? = null
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { ProcessesContent(procsUi, ProcsCallbacks(onSort = { sorted = it })) } }
        compose.onNode(androidx.compose.ui.test.hasText("Memory") and androidx.compose.ui.test.hasClickAction()).performClick()
        assertEquals(ProcSort.Memory, sorted)
    }

    @Test
    fun units() {
        assertEquals("488 MB", bytes(512_000_000))
        assertEquals("2.2 GB", bytes(2_400_000_000))
        assertEquals("931 GB", bytes(999_142_281_216))
        assertEquals("104.5%", cpuText(104.5))
        assertEquals(40, pct(33_324_118_016 - 19_843_563_520, 33_324_118_016))
        assertEquals(0, pct(5, 0))
    }
}
