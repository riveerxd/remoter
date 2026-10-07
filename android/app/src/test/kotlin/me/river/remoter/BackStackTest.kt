package me.river.remoter

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The monkey sweep crashed the app with "NavDisplay backstack cannot be empty": a second tap on a
 * back arrow landed on the screen still fading out and popped Home as well.
 */
class BackStackTest {
    @Test
    fun back_arrow_pops_only_its_own_screen() {
        val back = NavBackStack<NavKey>(Home, Browser("Projects"))
        back.popFrom(Browser("Projects"))
        assertEquals(listOf<NavKey>(Home), back.toList())
        back.popFrom(Browser("Projects"))
        assertEquals("a second tap from the leaving screen changes nothing", listOf<NavKey>(Home), back.toList())
        back.popFrom(Home)
        assertEquals("the stack never goes empty", listOf<NavKey>(Home), back.toList())
        back.add(Settings)
        back.add(Audit)
        back.popFrom(Settings)
        assertEquals("only the screen on top can go back", listOf(Home, Settings, Audit), back.toList())
    }
}
