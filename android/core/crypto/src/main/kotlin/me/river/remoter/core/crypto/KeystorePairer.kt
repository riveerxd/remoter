package me.river.remoter.core.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import me.river.remoter.core.net.AttestRequest
import me.river.remoter.core.net.B64
import me.river.remoter.core.net.OkHttpRemoterApi
import me.river.remoter.core.net.PairOutcome
import me.river.remoter.core.net.PairRequest
import me.river.remoter.core.net.Pairing
import me.river.remoter.core.net.PairingClient

/**
 * Phone side of pairing. The code and MAC are computed here from the secret
 * in the link, so the code shows while the one POST waits for the laptop to
 * have it typed. Leaf SPKIs come from each chain's own leaf, never a separate
 * field that could disagree with it.
 */
class KeystorePairer(private val keys: Keys, private val client: PairingClient) : Pairer {
    override fun pair(link: Pairing.Link, deviceName: String): Flow<PairEvent> = flow {
        try {
            keys.generatePair(link.challenge)
        } catch (e: NoStrongBoxException) {
            emit(PairEvent.NoStrongBox)
            return@flow
        }
        val t = Pairing.transcript(link.serverFp, keys.leafSpki(TLS_ALIAS), keys.leafSpki(SIG_ALIAS), deviceName)
        val boot = Attestation.verifiedBootKey(keys.chain(SIG_ALIAS).first())
        emit(PairEvent.Code(Pairing.confirmationCode(link.secret, t), boot?.let { B64.hex(it).take(8).uppercase() } ?: "unknown"))
        val req = PairRequest(deviceName, keys.chainB64(TLS_ALIAS), keys.chainB64(SIG_ALIAS), B64.encode(Pairing.mac(link.secret, t)))
        when (val r = client.post(link, req)) {
            is PairOutcome.Ok -> emit(
                PairEvent.Paired(r.response.hostname, r.response.deviceId, B64.encode(link.serverFp), keys.level(SIG_ALIAS), keys.level(TLS_ALIAS), link.port),
            )
            PairOutcome.KeyMismatch -> { keys.wipe(); emit(PairEvent.ServerKeyMismatch) }
            PairOutcome.Expired -> { keys.wipe(); emit(PairEvent.Expired) }
            PairOutcome.Rejected -> { keys.wipe(); emit(PairEvent.Rejected) }
            PairOutcome.Unreachable -> { keys.wipe(); emit(PairEvent.Unreachable) }
        }
    }.flowOn(Dispatchers.IO)
}

/** Daily re-attestation: keeps `fresh_until` ahead so mutations never hit `reattest_required`. */
fun interface Attester {
    suspend fun refresh(): Long?
}

class KeystoreAttester(private val keys: Keys, private val api: OkHttpRemoterApi) : Attester {
    override suspend fun refresh(): Long? = runCatching {
        val c = api.attestChallenge()
        val chain = keys.attestOnce(B64.decode(c.challenge) ?: return null)
        api.attest(AttestRequest(chain)).freshUntil
    }.getOrNull()
}
