package me.river.remoter.core.net

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class LockTimeout(val ms: Long) { Immediately(0), OneMinute(60_000), FiveMinutes(300_000) }

@Serializable
enum class ThemePref { System, Light, Dark }

@Serializable
data class Prefs(
    val showHidden: Boolean = false,
    val haptics: Boolean = true,
    // App lock is gone. Both stay so a phone that saved them still decodes:
    // the store is read strictly, and a state that fails to decode comes back empty, unpaired.
    val appLock: Boolean = true,
    val lockTimeout: LockTimeout = LockTimeout.OneMinute,
    val terminalSp: Float = 12f,
    val theme: ThemePref = ThemePref.System,
)

@Serializable
data class PairedLaptop(
    val hostname: String,
    val serverFp: String,
    val deviceId: String,
    val pairedAtMs: Long,
    val sigLevel: String,
    val tlsLevel: String,
    val lastAttestMs: Long?,
    /** From the pairing link: 8443 in production, 9443 for the staging instance the e2e suite uses. */
    val port: Int = 8443,
)

/** The last home screen, so a cold start shows real content instead of a skeleton. Never terminal output. */
@Serializable
data class HomeSnapshot(
    val hostname: String,
    val recent: List<RecentEntry>,
    val sessions: List<SessionSummary>,
    val battery: Int?,
    val onAc: Boolean?,
    val takenAtMs: Long,
)

@Serializable
data class LocalState(
    val prefs: Prefs = Prefs(),
    val pinned: List<String> = emptyList(),
    val laptop: PairedLaptop? = null,
    val snapshot: HomeSnapshot? = null,
    // still written on background; nothing has read it since app lock went
    val backgroundedAtMs: Long? = null,
    val startTimesMs: List<Int> = emptyList(),
)

interface LocalStore {
    /** `null` until the first read finishes. The splash waits on this, capped at 800 ms. */
    val state: StateFlow<LocalState?>
    suspend fun update(f: (LocalState) -> LocalState)
}
