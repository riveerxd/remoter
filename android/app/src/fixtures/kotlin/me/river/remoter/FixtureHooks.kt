package me.river.remoter

import android.content.Intent
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.testing.FakeVpnNetworks
import me.river.remoter.core.testing.FixtureBackend
import me.river.remoter.core.testing.SpawnScript

/**
 * Steers the fixture backend from `adb shell am start -e fixture_... `, so every
 * designed state can be recorded on the emulator. Exists only in debug and benchmark.
 */
object FixtureHooks {
    fun apply(intent: Intent?, backend: FixtureBackend, vpn: FakeVpnNetworks, store: me.river.remoter.core.net.LocalStore) {
        intent ?: return
        // Benchmarks start from a paired phone; pairing itself isn't what they measure.
        if (intent.hasExtra("fixture_paired")) kotlinx.coroutines.runBlocking {
            store.update {
                if (it.laptop != null) it else it.copy(
                    laptop = me.river.remoter.core.net.PairedLaptop("r1v3r", "oaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaE", "01K6B7Y3M4N5P6Q7R8S9T0V1W2", System.currentTimeMillis(), "StrongBox", "Tee", null),
                )
            }
        }
        intent.getStringExtra("fixture_spawn")?.let { v -> SpawnScript.entries.firstOrNull { it.name.equals(v, true) }?.let { backend.spawnScript = it } }
        when (intent.getStringExtra("fixture_link")) {
            "vpnoff" -> vpn.absent()
            "down" -> backend.unreachable = true
            "up" -> { vpn.ours(); backend.unreachable = false }
        }
        intent.getStringExtra("fixture_fail")?.let { v -> backend.failWith = ErrorCode.entries.firstOrNull { it.name.equals(v, true) } }
        if (intent.hasExtra("fixture_empty")) backend.emptyHome = true
    }
}
