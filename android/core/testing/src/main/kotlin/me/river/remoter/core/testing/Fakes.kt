package me.river.remoter.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.VpnLink
import me.river.remoter.core.net.VpnNetworks

class FakeClock(var now: Long = 1_790_611_106_000, var uptime: Long = 0) : Clock {
    override fun nowMs() = now
    override fun uptimeMs() = uptime

    fun advance(ms: Long) {
        now += ms
        uptime += ms
    }
}

class FakeVpnNetworks(initial: VpnLink = VpnLink.Present(1)) : VpnNetworks {
    private val state = MutableStateFlow(initial)
    override val link: StateFlow<VpnLink> = state

    fun ours(token: Long = 1) {
        state.value = VpnLink.Present(token)
    }

    /** No VPN at all, or someone else's: to the app both mean the same thing. */
    fun absent() {
        state.value = VpnLink.Absent
    }
}
