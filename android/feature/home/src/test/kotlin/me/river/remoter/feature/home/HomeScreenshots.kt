package me.river.remoter.feature.home

import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.river.remoter.core.design.components.RemoterSheet
import me.river.remoter.core.design.shots.Variant
import me.river.remoter.core.design.shots.shot
import me.river.remoter.core.net.ClaudeLink
import me.river.remoter.core.net.Link
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.SessionSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val now = 1_790_620_000_000
private fun s(id: String, name: String, st: SessionState, code: Int? = null) =
    SessionSummary(
        id, name, "Projects/$name", null, now - 5_000_000, st, null, code,
        claude = if (st == SessionState.Ready) ClaudeLink("session_01Hq7cXv2mTnR4bWkYe9pLsA", "https://claude.ai/code/session_01Hq7cXv2mTnR4bWkYe9pLsA", null) else null,
    )

private val base = HomeUi(
    hostname = "r1v3r", link = Link.Up(38), battery = 64, onAc = true, loaded = true, nowMs = now,
    pinned = persistentListOf(FolderItem("Projects/remoter", "remoter", true)),
    recent = persistentListOf(FolderItem("Projects/api", "api", true), FolderItem("Projects/site", "site", false)),
)

internal val load = me.river.remoter.core.net.Resources(
    cpuPct = 23.4, cores = 16, memTotal = 33_324_118_016, memAvailable = 19_843_563_520,
    swapTotal = 17_179_865_088, swapFree = 17_179_865_088, diskTotal = 999_142_281_216, diskFree = 412_418_985_984,
)

internal val homeStates = mapOf(
    "up_no_sessions" to base,
    "up_sessions" to base.copy(
        battery = 12, onAc = false,
        sessions = persistentListOf(s("rc-a", "remoter", SessionState.Ready), s("rc-b", "api", SessionState.Starting), s("rc-c", "site", SessionState.Exited, 1)),
    ),
    "up_ending" to base.copy(sessions = persistentListOf(s("rc-a", "remoter", SessionState.Ready)), ending = setOf("rc-a")),
    "reconnecting" to base.copy(link = Link.Reconnecting),
    "vpn_off" to base.copy(link = Link.VpnOff),
    "laptop_down" to base.copy(link = Link.LaptopDown(now - 600_000)),
    "first_run" to base.copy(
        pinned = persistentListOf(), recent = persistentListOf(),
        suggestions = persistentListOf(FolderItem("Projects/remoter", "remoter", true), FolderItem("Projects/api", "api", true)),
    ),
    "loading" to HomeUi(hostname = "r1v3r", loaded = false),
    // First launches that never got an answer: status and a way out, never skeletons.
    "cold_laptop_down" to HomeUi(hostname = "r1v3r", link = Link.LaptopDown(null), nowMs = now, stillDown = 1),
    "cold_vpn_off" to HomeUi(hostname = "r1v3r", link = Link.VpnOff, nowMs = now, pinned = base.pinned),
    "cold_load_failed" to HomeUi(hostname = "r1v3r", link = Link.Up(38), nowMs = now, loadFailed = true),
    "up_exited" to base.copy(sessions = persistentListOf(s("rc-a", "remoter", SessionState.Ready), s("rc-c", "site", SessionState.Exited, 1))),
    "up_many_pins" to base.copy(pinned = (base.recent + base.pinned).toImmutableList()),
    "up_worktree" to base.copy(
        sessions = persistentListOf(s("rc-a", "remoter", SessionState.Ready), s("rc-b", "remoter", SessionState.Ready).copy(worktree = "bright-otter-3f2a")),
    ),
    "direct_up" to base.copy(direct = true),
    "up_load" to base.copy(resources = load),
    "up_sessions_load" to base.copy(
        resources = load.copy(cpuPct = 91.0, memAvailable = 2_000_000_000),
        sessions = persistentListOf(s("rc-a", "remoter", SessionState.Ready), s("rc-b", "api", SessionState.Starting)),
    ),
    "laptop_down_load" to base.copy(link = Link.LaptopDown(now - 600_000), resources = load),
    "direct_reconnecting" to base.copy(direct = true, link = Link.Reconnecting),
    "direct_vpn_off" to base.copy(direct = true, link = Link.VpnOff),
    "direct_laptop_down" to base.copy(direct = true, link = Link.LaptopDown(now - 600_000)),
    "direct_loading" to HomeUi(hostname = "r1v3r", loaded = false, direct = true),
)

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeScreenshots(private val v: Variant) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = Variant.params()
    }

    @get:Rule val compose = createComposeRule()

    private fun one(k: String) = compose.shot("home_$k", v) {
        // Reconnecting only shows after 300 ms; the harness clock gets there on idle.
        HomeContent(homeStates.getValue(k), HomeCallbacks(), entrance = false)
    }

    @Test fun up_no_sessions() = one("up_no_sessions")
    @Test fun up_sessions() = one("up_sessions")
    @Test fun up_ending() = one("up_ending")
    @Test fun reconnecting() = one("reconnecting")
    @Test fun vpn_off() = one("vpn_off")
    @Test fun laptop_down() = one("laptop_down")
    @Test fun first_run() = one("first_run")
    @Test fun loading() = one("loading")
    @Test fun cold_laptop_down() = one("cold_laptop_down")
    @Test fun cold_vpn_off() = one("cold_vpn_off")
    @Test fun cold_load_failed() = one("cold_load_failed")
    @Test fun up_exited() = one("up_exited")
    @Test fun up_many_pins() = one("up_many_pins")
    @Test fun up_worktree() = one("up_worktree")

    @Test fun new_session() = compose.shot("home_new_session", v) {
        HomeContent(homeStates.getValue("up_sessions"), HomeCallbacks(), entrance = false)
        RemoterSheet(visible = true, onDismiss = {}) { NewSession(homeStates.getValue("up_sessions"), HomeCallbacks()) }
    }

    @Test fun new_session_first_run() = compose.shot("home_new_session_first_run", v) {
        val ui = homeStates.getValue("first_run")
        HomeContent(ui, HomeCallbacks(), entrance = false)
        RemoterSheet(visible = true, onDismiss = {}) { NewSession(ui, HomeCallbacks()) }
    }
    @Test fun up_load() = one("up_load")
    @Test fun up_sessions_load() = one("up_sessions_load")
    @Test fun direct_up() = one("direct_up")
    @Test fun direct_reconnecting() = one("direct_reconnecting")
    @Test fun direct_vpn_off() = one("direct_vpn_off")
    @Test fun direct_laptop_down() = one("direct_laptop_down")
    @Test fun direct_loading() = one("direct_loading")
}
