package me.river.remoter.core.net

import kotlinx.coroutines.flow.StateFlow

/** Wall time for signatures and display, uptime for timeouts and hysteresis. */
interface Clock {
    fun nowMs(): Long
    fun uptimeMs(): Long

    object System : Clock {
        override fun nowMs() = java.lang.System.currentTimeMillis()
        override fun uptimeMs() = java.lang.System.nanoTime() / 1_000_000
    }
}

sealed interface VpnLink {
    /** No VPN network, or one without 10.66.66.2: another VPN or Samsung Secure Wi-Fi. */
    data object Absent : VpnLink

    /** Ours. [token] identifies the network so a change of network is visible. */
    data class Present(val token: Long) : VpnLink
}

interface VpnNetworks {
    val link: StateFlow<VpnLink>
}

/** Onboarding step 1: a bare TCP connect to the laptop over our VPN network. No HTTP before pairing. */
interface Reachability {
    suspend fun laptopAnswers(): Boolean
}
