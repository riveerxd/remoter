package me.river.remoter.feature.settings

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
}
