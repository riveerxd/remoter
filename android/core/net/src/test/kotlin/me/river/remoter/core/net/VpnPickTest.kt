package me.river.remoter.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class VpnPickTest {
    private fun a(s: String) = InetAddress.getByName(s)

    @Test
    fun picks_phone_address() {
        val nets = mapOf("samsung" to listOf(a("10.8.0.2")), "rmt" to listOf(a("10.66.66.2")), "other" to listOf(a("fd00::2")))
        assertEquals("rmt", pickOurs(nets))
    }

    @Test
    fun another_vpn_alone_is_not_ours() {
        assertNull(pickOurs(mapOf("corp" to listOf(a("10.66.66.20")), "ipv6" to listOf(a("::ffff:10.66.66.3")))))
    }

    @Test
    fun no_vpn_at_all() {
        assertNull(pickOurs(emptyMap<String, List<InetAddress>>()))
    }
}
