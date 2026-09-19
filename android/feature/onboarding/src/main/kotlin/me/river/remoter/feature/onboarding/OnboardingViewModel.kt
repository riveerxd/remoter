package me.river.remoter.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.river.remoter.core.crypto.PairEvent
import me.river.remoter.core.crypto.Pairer
import me.river.remoter.core.net.Clock
import me.river.remoter.core.net.LocalStore
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.net.Pairing
import me.river.remoter.core.net.Reachability
import me.river.remoter.core.net.VpnLink
import me.river.remoter.core.net.VpnNetworks
import javax.inject.Inject

enum class HardStop { Expired, ServerKeyMismatch, NoStrongBox }

/** Why Pair again appears, in plain words on its one screen. */
@kotlinx.serialization.Serializable
enum class PairAgainReason { KeyInvalidated, Revoked, Unpaired }

sealed interface OnboardingStep {
    /** Step 1. [tunnel] is our VPN network present, [laptop] the TCP connect answered. */
    data class Connect(val tunnel: Boolean, val laptop: Boolean) : OnboardingStep
    data class Scan(val pasting: Boolean = false, val pasteInvalid: Boolean = false, val rejected: Boolean = false, val unreachable: Boolean = false) : OnboardingStep
    data object Pairing : OnboardingStep
    data class Confirm(val code: String, val bootKey: String) : OnboardingStep
    data object Done : OnboardingStep
    data class Stop(val why: HardStop) : OnboardingStep
    data class PairAgain(val reason: PairAgainReason) : OnboardingStep
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val vpn: VpnNetworks,
    private val reach: Reachability,
    private val pairer: Pairer,
    private val store: LocalStore,
    private val clock: Clock,
) : ViewModel() {
    private val _step = MutableStateFlow<OnboardingStep>(OnboardingStep.Connect(false, false))
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()
    private val _paired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val paired: SharedFlow<Unit> = _paired
    private var watch: Job? = null
    private var pairing: Job? = null

    fun begin(pairAgain: PairAgainReason?) {
        if (pairAgain != null) {
            _step.value = OnboardingStep.PairAgain(pairAgain)
            return
        }
        watchConnect()
    }

    /** The step checks itself: our VPN network shows up, then a TCP connect to the laptop succeeds. */
    private fun watchConnect() {
        watch?.cancel()
        watch = viewModelScope.launch {
            vpn.link.collect { v ->
                val tunnel = v is VpnLink.Present
                _step.value = OnboardingStep.Connect(tunnel, false)
                if (!tunnel) return@collect
                while (true) {
                    if (reach.laptopAnswers()) {
                        _step.value = OnboardingStep.Connect(true, true)
                        delay(600)
                        _step.value = OnboardingStep.Scan()
                        watch?.cancel()
                        return@collect
                    }
                    delay(2_000)
                }
            }
        }
    }

    fun pairAgain() {
        _step.value = OnboardingStep.Scan()
    }

    fun paste() = _step.update { (it as? OnboardingStep.Scan)?.copy(pasting = true) ?: it }

    /** Back from paste to the camera. Onboarding is the root, so without this system back leaves the app. */
    fun scanInstead() = _step.update { (it as? OnboardingStep.Scan)?.copy(pasting = false, pasteInvalid = false) ?: it }

    /** The "not a full link" hint is about the text that was submitted, not the text being typed now. */
    fun linkEdited() = _step.update { s -> (s as? OnboardingStep.Scan)?.takeIf { it.pasteInvalid }?.copy(pasteInvalid = false) ?: s }

    fun submitLink(text: String) {
        val link = Pairing.Link.parse(text.trim())
        if (link == null) {
            _step.update { (it as? OnboardingStep.Scan)?.copy(pasteInvalid = true) ?: it }
            return
        }
        pair(link)
    }

    /** One attempt. A key mismatch or an expired code is a full stop, never a retry. */
    fun pair(link: Pairing.Link) {
        if (pairing?.isActive == true) return
        if (link.expiresUnix * 1000 < clock.nowMs()) {
            _step.value = OnboardingStep.Stop(HardStop.Expired)
            return
        }
        _step.value = OnboardingStep.Pairing
        pairing = viewModelScope.launch {
            pairer.pair(link, android.os.Build.MODEL ?: "Phone").collect { e ->
                when (e) {
                    is PairEvent.Code -> _step.value = OnboardingStep.Confirm(e.code, e.bootKey)
                    is PairEvent.Paired -> {
                        store.update {
                            it.copy(laptop = PairedLaptop(e.hostname, e.serverFp, e.deviceId, clock.nowMs(), e.sig.name, e.tls.name, clock.nowMs(), e.port))
                        }
                        _step.value = OnboardingStep.Done
                        _paired.tryEmit(Unit)
                    }
                    PairEvent.Expired -> _step.value = OnboardingStep.Stop(HardStop.Expired)
                    PairEvent.ServerKeyMismatch -> _step.value = OnboardingStep.Stop(HardStop.ServerKeyMismatch)
                    PairEvent.NoStrongBox -> _step.value = OnboardingStep.Stop(HardStop.NoStrongBox)
                    PairEvent.Rejected -> _step.value = OnboardingStep.Scan(rejected = true)
                    PairEvent.Unreachable -> _step.value = OnboardingStep.Scan(unreachable = true)
                }
            }
        }
    }

    fun scanAgain() {
        _step.value = OnboardingStep.Scan()
    }

    /**
     * The other stops never retry on their own, but after a network switch the
     * user has to be able to go again without clearing app data. Back to step 1,
     * so the tunnel and the laptop get checked afresh.
     */
    fun startOver() {
        pairing?.cancel()
        watchConnect()
    }
}

/** 1 of 3, 2 of 3, 3 of 3. It starts at a third: installing the app was step zero. */
fun OnboardingStep.progress(): Float = when (this) {
    is OnboardingStep.Connect -> 1f / 3
    is OnboardingStep.Scan, OnboardingStep.Pairing -> 2f / 3
    is OnboardingStep.Confirm, OnboardingStep.Done -> 1f
    else -> 0f
}
