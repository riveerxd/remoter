package me.river.remoter.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address

const val PHONE_ADDR = "10.66.66.2"

// any other VPN (Samsung Secure Wi-Fi included) is ignored. every socket comes from
// network's factory, so with ours gone nothing is sent at all
class AndroidVpnNetworks(context: Context) : VpnNetworks {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow<VpnLink>(VpnLink.Absent)
    override val link: StateFlow<VpnLink> = state

    @Volatile var network: Network? = null
        private set

    private val candidates = mutableMapOf<Network, LinkProperties?>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) = update(n, cm.getLinkProperties(n))
        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) = update(n, lp)
        override fun onLost(n: Network) {
            synchronized(candidates) { candidates.remove(n) }
            publish()
        }
    }

    private fun update(n: Network, lp: LinkProperties?) {
        synchronized(candidates) { candidates[n] = lp }
        publish()
    }

    private fun publish() {
        val ours = synchronized(candidates) {
            pickOurs(candidates.mapValues { (_, lp) -> lp?.linkAddresses?.map { it.address }.orEmpty() })
        }
        network = ours
        state.value = ours?.let { VpnLink.Present(it.networkHandle) } ?: VpnLink.Absent
    }

    init {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(req, callback)
    }
}

fun <K> pickOurs(candidates: Map<K, List<java.net.InetAddress>>): K? =
    candidates.entries.firstOrNull { (_, addrs) -> addrs.any { it is Inet4Address && it.hostAddress == PHONE_ADDR } }?.key
