package me.river.remoter.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.shots.clippedText
import me.river.remoter.core.design.shots.unlabelledClickables
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.collections.immutable.persistentListOf
import me.river.remoter.core.net.AuditEntry
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop

private val laptop = PairedLaptop("r1v3r", "oaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaE", "dev", 1_790_600_000_000, "StrongBox", "TEE", 1_790_610_000_000)
private val screens: Map<String, @Composable () -> Unit> = mapOf(
    "settings" to { SettingsContent(SettingsUi(LocalState(laptop = laptop)), SettingsCallbacks()) },
    "audit" to { AuditContent(AuditUi(persistentListOf(AuditDay("Today", persistentListOf(AuditEntry(1_790_619_000_000, "dev", "spawn", "Projects/remoter", "ok", "r1")))), loading = false, end = true), {}, {}) },
    "audit_error" to { AuditContent(AuditUi(loading = false, error = me.river.remoter.core.net.AppError.LaptopDown), {}, {}, "r1v3r") },
    "audit_more_failed" to { AuditContent(AuditUi(persistentListOf(AuditDay("Today", persistentListOf(AuditEntry(1_790_619_000_000, "dev", "spawn", "Projects/remoter", "sig_invalid", "r1")))), loading = false, end = false, error = me.river.remoter.core.net.AppError.LaptopDown), {}, {}) },
    "settings_locked" to { SettingsContent(SettingsUi(LocalState(laptop = laptop), locked = true), SettingsCallbacks()) },
    "audit_empty" to { AuditContent(AuditUi(loading = false, end = true), {}, {}) },
)

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsA11yTest(private val name: String) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = screens.keys.map { arrayOf<Any>(it) }
    }

    @get:Rule val compose = createComposeRule()

    @Test
    fun labelled_and_unclipped_at_200_percent() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) { RemoterTheme(dark = true, reducedMotion = true) { screens.getValue(name)() } }
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), compose.unlabelledClickables())
        assertEquals(emptyList<String>(), compose.clippedText())
    }
}
