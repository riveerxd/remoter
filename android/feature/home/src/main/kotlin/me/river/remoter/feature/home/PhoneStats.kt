package me.river.remoter.feature.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

enum class Uplink { WiFi, Mobile, Ethernet }

@Immutable
data class PhoneStats(val batteryPct: Int? = null, val charging: Boolean = false, val uplink: Uplink? = null)

// "Wi-Fi · 84%", leaving out what it doesn't know
internal fun PhoneStats.line(): String? = listOfNotNull(
    when (uplink) {
        Uplink.WiFi -> "Wi-Fi"
        Uplink.Mobile -> "Mobile data"
        Uplink.Ethernet -> "Ethernet"
        null -> null
    },
    batteryPct?.let { if (charging) "$it% charging" else "$it%" },
).joinToString(" · ").ifEmpty { null }

// with WireGuard on the default network is the VPN; since Android 12 its
// capabilities carry the transports underneath, which is what we show
@Composable
fun rememberPhoneStats(): PhoneStats {
    val context = LocalContext.current.applicationContext
    var stats by remember { mutableStateOf(PhoneStats()) }
    DisposableEffect(context) {
        fun battery(i: Intent?) {
            i ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            stats = stats.copy(
                batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else null,
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            )
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = battery(i)
        }
        battery(context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                stats = stats.copy(uplink = uplinkOf(caps))
            }
            override fun onLost(network: Network) {
                stats = stats.copy(uplink = null)
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
    }
    return stats
}

internal fun uplinkOf(caps: NetworkCapabilities): Uplink? = when {
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Uplink.WiFi
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Uplink.Mobile
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Uplink.Ethernet
    else -> null
}
