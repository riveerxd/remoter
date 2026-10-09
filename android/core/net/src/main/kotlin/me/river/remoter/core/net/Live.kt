package me.river.remoter.core.net

import kotlinx.serialization.Serializable

/** The data of a `sessions` event on `/v1/live`. No cap: it never changes while the laptop runs. */
@Serializable
data class LiveSessions(val sessions: List<SessionSummary>)

/** One snapshot from `/v1/live`. Each is complete, so a reconnect just starts over. */
sealed interface LiveEvent {
    data class Sessions(val sessions: List<SessionSummary>) : LiveEvent
    data class Health(val health: me.river.remoter.core.net.Health) : LiveEvent
    data class Resources(val resources: me.river.remoter.core.net.Resources) : LiveEvent

    companion object {
        /** `null` for an event name this build doesn't know, so a newer laptop can add some. */
        fun parse(name: String, data: String): LiveEvent? = when (name) {
            "sessions" -> Sessions(RemoterJson.decodeFromString<LiveSessions>(data).sessions)
            "health" -> Health(RemoterJson.decodeFromString<me.river.remoter.core.net.Health>(data))
            "resources" -> Resources(RemoterJson.decodeFromString<me.river.remoter.core.net.Resources>(data))
            else -> null
        }
    }
}
