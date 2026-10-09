package me.river.remoter.feature.settings

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.net.Prefs
import me.river.remoter.core.net.ThemePref
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// both looks existed but the app only ever followed the phone
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ThemePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun picking_dark_sets_pref() {
        var prefs = Prefs()
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) {
                SettingsContent(SettingsUi(me.river.remoter.core.net.LocalState(prefs = prefs)), SettingsCallbacks(onPrefs = { f -> prefs = f(prefs) }))
            }
        }
        compose.onNodeWithText("System").assertIsSelected()
        compose.onNodeWithText("Dark").performClick()
        assertEquals(ThemePref.Dark, prefs.theme)
    }
}
