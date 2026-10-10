package me.river.remoter.core.testing

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.river.remoter.core.crypto.PairEvent
import me.river.remoter.core.crypto.Pairer
import me.river.remoter.core.crypto.SecurityLevel
import me.river.remoter.core.net.B64
import me.river.remoter.core.net.Pairing
import me.river.remoter.core.net.Reachability
import me.river.remoter.core.net.Weakness

/** Plays the laptop side of pairing: shows a code, then confirms after [confirmMs]. */
class FakePairer(var outcome: PairEvent? = null, var confirmMs: Long = 4_000, var weaknesses: List<Weakness> = emptyList()) : Pairer {
    override fun pair(link: Pairing.Link, deviceName: String): Flow<PairEvent> = flow {
        outcome?.let {
            emit(it)
            return@flow
        }
        delay(600)
        emit(PairEvent.Code("481207", "A1F309CE", weaknesses))
        delay(confirmMs)
        emit(PairEvent.Paired("r1v3r", "01K6B7Y3M4N5P6Q7R8S9T0V1W2", B64.encode(link.serverFp), SecurityLevel.StrongBox, SecurityLevel.Tee, weaknesses = weaknesses))
    }
}

class FakeReachability(var answers: Boolean = true) : Reachability {
    override suspend fun laptopAnswers() = answers
}
