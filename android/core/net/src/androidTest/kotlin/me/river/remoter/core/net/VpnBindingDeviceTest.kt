package me.river.remoter.core.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import javax.net.ssl.X509ExtendedKeyManager

/**
 * The VPN binding on a real ConnectivityManager. With no VPN holding
 * 10.66.66.2 nothing is sent: calls fail before a socket exists.
 */
@RunWith(AndroidJUnit4::class)
class VpnBindingDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun no_rmt_vpn_means_absent() {
        val v = AndroidVpnNetworks(ctx)
        Thread.sleep(500)
        assertEquals(VpnLink.Absent, v.link.value)
        assertEquals(null, v.network)
    }

    @Test
    fun without_our_network_calls_stop_before_any_socket() = runBlocking {
        var asked = 0
        val api = OkHttpRemoterApi({ asked++; null }, object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(k: Array<out String>?, i: Array<out java.security.Principal>?, s: java.net.Socket?) = null
            override fun getClientAliases(k: String?, i: Array<out java.security.Principal>?) = null
            override fun chooseServerAlias(k: String?, i: Array<out java.security.Principal>?, s: java.net.Socket?) = null
            override fun getServerAliases(k: String?, i: Array<out java.security.Principal>?) = null
            override fun getCertificateChain(a: String?) = null
            override fun getPrivateKey(a: String?) = null
        }, { null }, Clock.System)
        val e = runCatching { api.health() }.exceptionOrNull()
        assertTrue("got $e", e is VpnOffException)
        assertEquals(1, asked)
        assertFalse(TcpReachability({ null }, { 8443 }).laptopAnswers())
    }

    @Test
    fun a_fake_network_list_picks_only_ours() {
        val fake = mapOf(1L to listOf(InetAddress.getByName("10.8.0.2")), 2L to listOf(InetAddress.getByName("10.66.66.2")))
        assertEquals(2L, pickOurs(fake))
        assertEquals(null, pickOurs(mapOf(1L to listOf(InetAddress.getByName("192.168.1.9")))))
    }
}
