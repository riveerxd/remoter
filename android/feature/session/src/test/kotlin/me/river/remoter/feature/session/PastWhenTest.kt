package me.river.remoter.feature.session

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PastWhenTest {
    private val prague = ZoneId.of("Europe/Prague")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(prague).toInstant().toEpochMilli()
    private val now = at(2026, 10, 7, 9, 30)
    private fun w(ms: Long) = pastWhen(ms, now, prague)

    @Test
    fun today_and_yesterday() {
        assertEquals("today 00:05", w(at(2026, 10, 7, 0, 5)))
        assertEquals("yesterday 23:50", w(at(2026, 10, 6, 23, 50)))
        assertEquals("yesterday 08:00", w(at(2026, 10, 6, 8, 0)))
    }

    @Test
    fun weekday_then_date() {
        assertEquals("Thursday 18:40", w(at(2026, 10, 1, 18, 40)))
        assertEquals("30 Sep", w(at(2026, 9, 30, 18, 40)))
        assertEquals("3 Oct 2025", w(at(2025, 10, 3, 12, 0)))
    }

    @Test
    fun clock_ahead_reads_today() {
        assertEquals("today 09:31", w(now + 60_000))
    }

    @Test
    fun zone_decides_day() {
        val ms = at(2026, 10, 6, 23, 30)
        assertEquals("yesterday 23:30", pastWhen(ms, now, prague))
        assertEquals("today 01:30", pastWhen(ms, now, ZoneId.of("Asia/Dubai")))
    }
}
