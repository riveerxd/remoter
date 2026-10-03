package me.river.remoter.feature.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** Seen on the emulator: a two-day-old session read "48:27:00" on its banner. */
class UptimeTest {
    @Test
    fun hours_tick_and_days_read_as_days() {
        assertEquals("0:00:05", uptime(5_000))
        assertEquals("23:59:59", uptime(86_399_000))
        assertEquals("2d 0h", uptime((48 * 3600 + 27 * 60) * 1000L))
        assertEquals("0:00:00", uptime(-1))
    }
}
