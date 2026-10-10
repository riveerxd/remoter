package me.river.remoter.feature.settings

import me.river.remoter.core.net.Weakness
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyLevelLabelTest {
    @Test
    fun enum_names_read_as_words() {
        assertEquals("Hardware (TEE)", keyLevelLabel("Tee"))
        assertEquals("Hardware (TEE)", keyLevelLabel("TEE"))
        assertEquals("Security chip (StrongBox)", keyLevelLabel("StrongBox"))
        assertEquals("None", keyLevelLabel(null))
    }

    @Test
    fun weaknesses_read_as_one_line() {
        assertEquals("Bootloader unlocked, unsigned software", notSecureLabel(listOf(Weakness.BootloaderUnlocked, Weakness.BootNotVerified)))
        assertEquals("No security chip", notSecureLabel(listOf(Weakness.NoStrongBox)))
    }
}
