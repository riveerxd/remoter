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
import me.river.remoter.core.net.Weakness
import javax.inject.Inject

enum class HardStop { Expired, ServerKeyMismatch }

@kotlinx.serialization.Serializable
enum class PairAgainReason { KeyInvalidated, Revoked, Unpaired }

sealed interface OnboardingStep {
    // tunnel: our VPN network is up. laptop: a TCP connect answered
    data class Connect(val tunnel: Boolean, val laptop: Boolean) : OnboardingStep
    data class Scan(val pasting: Boolean = false, val pasteInvalid: Boolean = false, val rejected: Boolean = false, val unreachable: Boolean = false) : OnboardingStep
    data object Pairing : OnboardingStep
    data class Confirm(val code: String, val bootKey: String, val weaknesses: List<Weakness> = emptyList()) : OnboardingStep
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

    // onboarding is the root, so without this system back leaves the app
    fun scanInstead() = _step.update { (it as? OnboardingStep.Scan)?.copy(pasting = false, pasteInvalid = false) ?: it }

    // the hint is about the submitted text, not what's being typed now
    fun linkEdited() = _step.update { s -> (s as? OnboardingStep.Scan)?.takeIf { it.pasteInvalid }?.copy(pasteInvalid = false) ?: s }

    fun submitLink(text: String) {
        val link = Pairing.Link.parse(text.trim())
        if (link == null) {
            _step.update { (it as? OnboardingStep.Scan)?.copy(pasteInvalid = true) ?: it }
            return
        }
        pair(link)
    }

    // a key mismatch or an expired code is a full stop, never a retry
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
                    is PairEvent.Code -> _step.value = OnboardingStep.Confirm(e.code, e.bootKey, e.weaknesses)
                    is PairEvent.Paired -> {
                        store.update {
                            it.copy(laptop = PairedLaptop(e.hostname, e.serverFp, e.deviceId, clock.nowMs(), e.sig.name, e.tls.name, clock.nowMs(), e.port, e.weaknesses))
                        }
                        _step.value = OnboardingStep.Done
                        _paired.tryEmit(Unit)
                    }
                    PairEvent.Expired -> _step.value = OnboardingStep.Stop(HardStop.Expired)
                    PairEvent.ServerKeyMismatch -> _step.value = OnboardingStep.Stop(HardStop.ServerKeyMismatch)
                    PairEvent.Rejected -> _step.value = OnboardingStep.Scan(rejected = true)
                    PairEvent.Unreachable -> _step.value = OnboardingStep.Scan(unreachable = true)
                }
            }
        }
    }

    fun scanAgain() {
        _step.value = OnboardingStep.Scan()
    }

    // after a network switch you have to be able to go again without clearing app data
    fun startOver() {
        pairing?.cancel()
        watchConnect()
    }
}

// starts at a third: installing the app was step zero
fun OnboardingStep.progress(): Float = when (this) {
    is OnboardingStep.Connect -> 1f / 3
    is OnboardingStep.Scan, OnboardingStep.Pairing -> 2f / 3
    is OnboardingStep.Confirm, OnboardingStep.Done -> 1f
    else -> 0f
}
