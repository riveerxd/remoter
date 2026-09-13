package me.river.remoter.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The store decodes strictly and a state that fails comes back empty, which is unpaired. A field
 * added to the saved prefs must therefore read state saved before it existed, and a field the app
 * stopped using must stay readable too.
 */
class PrefsCompatTest {
    @Test
    fun state_from_before_theme_still_decodes() {
        val saved = """{"laptop":{"hostname":"r1v3r","serverFp":"fp","deviceId":"dev","pairedAtMs":1,"sigLevel":"StrongBox","tlsLevel":"TEE","lastAttestMs":null},"prefs":{"showHidden":false,"haptics":true,"appLock":true,"lockTimeout":"OneMinute","terminalSp":12.0}}"""
        val s = RemoterJson.decodeFromString(LocalState.serializer(), saved)
        assertEquals("r1v3r", s.laptop?.hostname)
        assertEquals(ThemePref.System, s.prefs.theme)
    }

    @Test
    fun the_theme_round_trips() {
        val s = LocalState(prefs = Prefs(theme = ThemePref.Dark))
        val back = RemoterJson.decodeFromString(LocalState.serializer(), RemoterJson.encodeToString(LocalState.serializer(), s))
        assertEquals(ThemePref.Dark, back.prefs.theme)
    }
}
