package me.river.remoter.feature.session

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatedAgoTest {
    @Test
    fun seconds_then_minutes_then_hours() {
        assertEquals("Live", updatedAgo(0))
        assertEquals("Live", updatedAgo(2))
        assertEquals("Updated 3 s ago", updatedAgo(3))
        assertEquals("Updated 59 s ago", updatedAgo(59))
        assertEquals("Updated 1 min ago", updatedAgo(60))
        assertEquals("Updated 59 min ago", updatedAgo(3599))
        assertEquals("Updated 46 h ago", updatedAgo(166472))
    }
}
