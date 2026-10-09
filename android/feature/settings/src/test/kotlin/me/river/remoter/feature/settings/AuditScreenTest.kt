package me.river.remoter.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.collections.immutable.persistentListOf
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.AuditEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// load failures used to look like an empty log, or a skeleton that never stopped
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AuditScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun failed_first_load_shows_error_with_retry() {
        var retries = 0
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) {
                AuditContent(AuditUi(loading = false, error = AppError.LaptopDown), {}, { retries++ }, "r1v3r")
            }
        }
        compose.onNodeWithText("Nothing yet", substring = true).assertDoesNotExist()
        compose.onNodeWithText("r1v3r is asleep or offline").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun failed_next_page_offers_retry() {
        var more = 0
        val day = AuditDay("Today", persistentListOf(AuditEntry(1_790_619_000_000, "dev", "spawn", "Projects/remoter", "ok", "r1")))
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) {
                AuditContent(AuditUi(persistentListOf(day), loading = false, end = false, error = AppError.LaptopDown), {}, { more++ })
            }
        }
        compose.onNodeWithText("Couldn't load more").assertExists()
        compose.onNodeWithText("Started a session").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals("skeleton fired", 1, more)
    }
}
