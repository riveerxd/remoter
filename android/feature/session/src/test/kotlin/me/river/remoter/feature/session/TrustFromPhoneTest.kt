package me.river.remoter.feature.session

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.compose.ui.test.junit4.createComposeRule

/** An untrusted folder away from the laptop used to get only a laptop command as advice. */
@RunWith(RobolectricTestRunner::class)
class TrustFromPhoneTest {
    @get:Rule val compose = createComposeRule()
    private val clock = me.river.remoter.core.net.Clock.System

    private fun rig(fail: ErrorCode? = null, block: (FixtureBackend, FakeSigner, StartViewModel) -> Unit) {
        val api = FixtureBackend(phaseStepMs = 0, failWith = fail)
        val signer = FakeSigner(clock, fingerMs = 0)
        val store = MemoryStore(LocalState(laptop = PairedLaptop("r1v3r", "fp", "dev", 0, "StrongBox", "TEE", null)))
        val vm = StartViewModel(api, signer, clock, ConnectionMonitor(api, FakeVpnNetworks(), clock), store, SessionsHub(api, FakeSigner(clock), clock))
        try { block(api, signer, vm) } finally { vm.viewModelScope.cancel() }
    }

    private fun FixtureBackend.lastSpawn() = RemoterJson.decodeFromString(SpawnRequest.serializer(), spawnCalls.last().body.decodeToString())

    @Test
    fun known_untrusted_trusts_under_one_fingerprint() = rig { api, _, vm ->
        vm.open(StartTarget("Documents/notes", "notes", false, untrusted = true))
        vm.start()
        compose.waitUntil(3_000) { api.spawnCalls.isNotEmpty() }
        assertTrue(api.lastSpawn().trust)
    }

    @Test
    fun an_untrusted_refusal_offers_trust_and_sends_it() = rig(ErrorCode.UntrustedFolder) { api, _, vm ->
        vm.open(StartTarget("Projects/x", "x", false))
        vm.start()
        compose.waitUntil(3_000) { vm.ui.value.state is StartState.NotAccepted }
        assertEquals(AppError.Untrusted, (vm.ui.value.state as StartState.NotAccepted).error)
        assertFalse(api.lastSpawn().trust)
        api.failWith = null
        vm.trustAndStart()
        compose.waitUntil(3_000) { api.spawnCalls.size == 2 }
        assertTrue(api.lastSpawn().trust)
    }

    @Test
    fun plain_start_leaves_trust_out() {
        val json = RemoterJson.encodeToString(SpawnRequest.serializer(), SpawnRequest("Projects/x", "x", SpawnMode.SameDir))
        assertFalse(json, "trust" in json)
        val trusted = RemoterJson.encodeToString(SpawnRequest.serializer(), SpawnRequest("Projects/x", "x", SpawnMode.SameDir, trust = true))
        assertTrue(trusted, "\"trust\":true" in trusted)
    }
}
