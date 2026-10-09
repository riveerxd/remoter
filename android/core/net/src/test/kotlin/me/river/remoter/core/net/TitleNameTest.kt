package me.river.remoter.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleNameTest {
    @Test
    fun titles_become_valid_names() {
        val cases = mapOf(
            "Fix: the banner's overlap" to "Fix the banner s overlap",
            "remoter" to "remoter",
            "  -- leading junk" to "leading junk",
            "émoji 🚀 time" to "moji time",
            "a".repeat(60) to "a".repeat(48),
            "!!!" to "session",
            "x" + " ".repeat(10) + "y" to "x y",
            "release-1.2_final" to "release-1.2_final",
        )
        cases.forEach { (title, name) ->
            assertEquals(title, name, Names.sessionNameFromTitle(title))
            assertTrue(title, Names.isValidSessionName(Names.sessionNameFromTitle(title)))
        }
    }

    @Test
    fun cut_leaves_no_trailing_space() {
        val n = Names.sessionNameFromTitle("a".repeat(47) + " bcd")
        assertEquals("a".repeat(47), n)
    }
}
