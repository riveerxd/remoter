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
import me.river.remoter.core.net.Weakness

// leaf SPKIs come from each chain's own leaf, never a separate field that could disagree
class KeystorePairer(private val keys: Keys, private val client: PairingClient) : Pairer {
    override fun pair(link: Pairing.Link, deviceName: String): Flow<PairEvent> = flow {
        keys.generatePair(link.challenge)
        val t = Pairing.transcript(link.serverFp, keys.leafSpki(TLS_ALIAS), keys.leafSpki(SIG_ALIAS), deviceName)
        val rot = Attestation.rootOfTrust(keys.chain(SIG_ALIAS).first())
        val weak = weaknesses(keys.level(SIG_ALIAS), rot)
        emit(PairEvent.Code(Pairing.confirmationCode(link.secret, t), rot?.bootKey?.let { B64.hex(it).take(8).uppercase() } ?: "unknown", weak))
        val req = PairRequest(deviceName, keys.chainB64(TLS_ALIAS), keys.chainB64(SIG_ALIAS), B64.encode(Pairing.mac(link.secret, t)))
        when (val r = client.post(link, req)) {
            is PairOutcome.Ok -> emit(
                PairEvent.Paired(r.response.hostname, r.response.deviceId, B64.encode(link.serverFp), keys.level(SIG_ALIAS), keys.level(TLS_ALIAS), link.port, weak),
            )
            PairOutcome.KeyMismatch -> { keys.wipe(); emit(PairEvent.ServerKeyMismatch) }
            PairOutcome.Expired -> { keys.wipe(); emit(PairEvent.Expired) }
            PairOutcome.Rejected -> { keys.wipe(); emit(PairEvent.Rejected) }
            PairOutcome.Unreachable -> { keys.wipe(); emit(PairEvent.Unreachable) }
        }
    }.flowOn(Dispatchers.IO)
}

// what the laptop reads from the chain too
fun weaknesses(sig: SecurityLevel, rot: RootOfTrust?): List<Weakness> = buildList {
    if (sig != SecurityLevel.StrongBox) add(Weakness.NoStrongBox)
    if (rot == null || !rot.locked) add(Weakness.BootloaderUnlocked)
    if (rot == null || !rot.verified) add(Weakness.BootNotVerified)
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
