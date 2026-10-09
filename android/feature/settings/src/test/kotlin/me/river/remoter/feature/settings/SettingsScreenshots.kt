package me.river.remoter.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.collections.immutable.persistentListOf
import me.river.remoter.core.design.shots.Variant
import me.river.remoter.core.design.shots.shot
import me.river.remoter.core.net.AuditEntry
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import org.junit.Rule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// fixed so "Paired" and "Last checked" read the same on every run
private const val NOW = 1_790_696_400_000L
private val UTC = java.time.ZoneId.of("UTC")

private val laptop = PairedLaptop("r1v3r", "oaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaE", "dev", 1_790_600_000_000, "StrongBox", "TEE", 1_790_610_000_000)

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenshots(private val v: Variant) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = Variant.params()
    }

    @get:Rule val compose = createComposeRule()

    @Test fun settings() = compose.shot("settings", v) { SettingsContent(SettingsUi(LocalState(laptop = laptop)), SettingsCallbacks(), nowMs = NOW, zone = UTC) }
    @Test fun settings_bottom() {
        compose.setContent {
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, v.fontScale)) {
                me.river.remoter.core.design.RemoterTheme(dark = v.dark, reducedMotion = true) { SettingsContent(SettingsUi(LocalState(laptop = laptop)), SettingsCallbacks(), nowMs = NOW, zone = UTC) }
            }
        }
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Unpair this phone"))
        compose.onRoot().captureRoboImage("src/test/snapshots/settings_bottom_${v.suffix}.png")
    }

    @Test fun settings_locked() = compose.shot("settings_locked", v) { SettingsContent(SettingsUi(LocalState(laptop = laptop), locked = true), SettingsCallbacks(), nowMs = NOW, zone = UTC) }

    @Test fun audit() = compose.shot("audit", v) {
        val es = persistentListOf(
            AuditEntry(1_790_619_000_000, "dev", "spawn", "Projects/remoter", "ok", "r1"),
            AuditEntry(1_790_618_000_000, "dev", "mkdir", "Projects/new-thing", "ok", "r2"),
            AuditEntry(1_790_617_000_000, "dev", "spawn", "Projects/api", "sig_invalid", "r3"),
        )
        AuditContent(AuditUi(persistentListOf(AuditDay("Today", es)), loading = true, end = false), {}, {})
    }

    @Test fun audit_error() = compose.shot("audit_error", v) { AuditContent(AuditUi(loading = false, error = me.river.remoter.core.net.AppError.LaptopDown), {}, {}, "r1v3r") }

    @Test fun audit_empty() = compose.shot("audit_empty", v) { AuditContent(AuditUi(loading = false, end = true), {}, {}) }
}
