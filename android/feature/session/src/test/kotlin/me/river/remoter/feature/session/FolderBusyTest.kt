package me.river.remoter.feature.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.components.RemoterSheet
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.ConnectionMonitor
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.net.RemoterJson
import me.river.remoter.core.net.SpawnMode
import me.river.remoter.core.net.SpawnRequest
import me.river.remoter.core.testing.FakeSigner
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// a second session in a busy folder used to get a bare "exited with code 1":
// claude allows one Remote Control per folder
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class FolderBusyTest {
    @get:Rule val compose = createComposeRule()

    private val clock = me.river.remoter.core.net.Clock.System
    private val git = StartTarget("Projects/remoter", "remoter", true)

    private fun rig(block: (FixtureBackend, StartViewModel) -> Unit) {
        val api = FixtureBackend(phaseStepMs = 0, failWith = ErrorCode.FolderBusy)
        val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
        val vm = StartViewModel(api, FakeSigner(clock, fingerMs = 0), clock, ConnectionMonitor(api, FakeVpnNetworks(), clock), store, SessionsHub(api, FakeSigner(clock), clock))
        try { block(api, vm) } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun busy_folder_refused_with_blocking_session() = rig { _, vm ->
        vm.open(git)
        vm.start()
        compose.waitUntil(3_000) { vm.ui.value.state is StartState.NotAccepted }
        val e = (vm.ui.value.state as StartState.NotAccepted).error
        assertTrue("$e", e is AppError.FolderBusy && e.sessions.size == 1)
    }

    @Test
    fun worktree_retry_sends_same_start() = rig { api, vm ->
        vm.open(git)
        vm.start()
        compose.waitUntil(3_000) { vm.ui.value.state is StartState.NotAccepted }
        api.failWith = null
        vm.startInWorktree()
        compose.waitUntil(3_000) { api.spawnCalls.size == 2 }
        val sent = RemoterJson.decodeFromString(SpawnRequest.serializer(), api.spawnCalls.last().body.decodeToString())
        assertEquals(SpawnMode.Worktree, sent.mode)
        assertEquals("Projects/remoter", sent.path)
    }

    @Test
    fun sheet_offers_open_and_worktree_for_git() {
        var opened: String? = null
        var worktree = 0
        val busy = FixtureBackend().error(ErrorCode.FolderBusy).body.let { AppError.FolderBusy(it.sessions.orEmpty()) }
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) {
                Box(Modifier.fillMaxSize()) {
                    RemoterSheet(true, {}) {
                        StartContent(
                            StartUi(open = true, target = git, form = StartForm(name = "remoter"), state = StartState.NotAccepted(busy, null), hostname = "r1v3r"),
                            StartCallbacks(errors = ErrorActions(onOpenInClaude = { opened = it.id }), onUseWorktree = { worktree++ }),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("A session is already running in this folder").assertIsDisplayed()
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Start in a new worktree").performClick()
        assertEquals(busy.sessions.first().id, opened)
        assertEquals(1, worktree)
    }
}
