package me.river.remoter.feature.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.DenyReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BrowserUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun blocked_start_explains() {
        var blocked: Blocked? = null
        var started = false
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) {
                BrowserContent(browserStates.getValue("denied_here"), BrowserCallbacks(onBlocked = { blocked = it }, onStartHere = { started = true }), false)
            }
        }
        compose.onNodeWithText("Start in .ssh").assertIsDisplayed().performClick()
        assertEquals(Blocked.Denied, blocked)
        assertEquals(false, started)
    }

    @Test
    fun blocked_copy_says_why() {
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { androidx.compose.foundation.layout.Column { BlockedCopy(Blocked.Denied, "r1v3r", ".ssh") } } }
        compose.onNodeWithText("Sessions can't start in .ssh").assertIsDisplayed()
        compose.onNodeWithText("Pick a project folder instead", substring = true).assertIsDisplayed()
    }

    @Test
    fun empty_folder_explains() {
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { BrowserContent(browserStates.getValue("empty"), BrowserCallbacks(), false) } }
        compose.onNodeWithText("No folders in remoter").assertIsDisplayed()
        compose.onNodeWithText("Create one, or start a session right here.").assertIsDisplayed()
        compose.onNodeWithText("Start here").assertDoesNotExist()
        compose.onAllNodesWithText("New folder").assertCountEquals(1)
    }

    @Test
    fun no_results_offers_create() {
        var made: String? = null
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { BrowserContent(browserStates.getValue("search_none"), BrowserCallbacks(onNewFolder = { made = it }), false) } }
        compose.onNodeWithText("Nothing called 'foo' here").assertIsDisplayed()
        compose.onNodeWithText("Create folder 'foo' here").performClick()
        assertEquals("foo", made)
    }

    @Test
    fun breadcrumb_names_level() {
        var crumb: String? = null
        val ui = browserStates.getValue("listing").copy(path = "Projects/clients/acme")
        compose.setContent { RemoterTheme(dark = true, reducedMotion = true) { BrowserContent(ui, BrowserCallbacks(onCrumb = { crumb = it }), false) } }
        compose.onNodeWithText("Projects").performClick()
        assertEquals("Projects", crumb)
        compose.onNodeWithText("~").performClick()
        assertEquals("", crumb)
    }

    @Test
    fun natural_order() {
        assertEquals(listOf("v1", "v2", "v10", "V11"), listOf("v10", "V11", "v2", "v1").sortedWith(NaturalOrder))
    }
}
