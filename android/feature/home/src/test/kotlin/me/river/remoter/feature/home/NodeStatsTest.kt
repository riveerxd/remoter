package me.river.remoter.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeStatsTest {
    @Test
    fun phone_line_skips_unknown_parts() {
        assertEquals("Wi-Fi · 84%", PhoneStats(84, charging = false, uplink = Uplink.WiFi).line())
        assertEquals("Mobile data · 40% charging", PhoneStats(40, charging = true, uplink = Uplink.Mobile).line())
        assertEquals("84%", PhoneStats(84).line())
        assertNull(PhoneStats().line())
    }

    @Test
    fun last_used_reads_short() {
        assertEquals("just now", agoShort(30_000))
        assertEquals("12 min ago", agoShort(12 * 60_000L))
        assertEquals("3 h ago", agoShort(3 * 3_600_000L + 59 * 60_000L))
        assertEquals("2 d ago", agoShort(2 * 86_400_000L + 5))
    }
}
