package me.river.remoter.core.net

import okhttp3.HttpUrl

/**
 * Every URL the app calls, built with OkHttp so the signed target is the one
 * that goes over the wire.
 */
object Paths {
    private val base: HttpUrl = HttpUrl.Builder().scheme("https").host(LAPTOP_ADDR).port(8443).build()

    fun url(vararg segments: String, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder().addPathSegment("v1").apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    val mkdir get() = url("fs", "mkdir")
    val sessions get() = url("sessions")
    fun session(id: String) = url("sessions", id)
    fun events(id: String) = url("sessions", id, "events")
    val live get() = url("live")
    val viewToken get() = url("view-token")
    fun history(path: String) = url("fs", "history", query = listOf("path" to path))
    val deviceSelf get() = url("devices", "self")
}

/** "~/Projects/remoter" for display. Paths from the laptop are relative to home. */
fun displayPath(rel: String): String = if (rel.isEmpty()) "~" else "~/$rel"

/** "…/Projects/remoter", trimmed from the left so the folder name always shows. */
fun trimmedPath(rel: String, keep: Int = 2): String {
    val parts = rel.split('/').filter { it.isNotEmpty() }
    return if (parts.size <= keep) displayPath(rel) else "…/" + parts.takeLast(keep).joinToString("/")
}

fun folderName(rel: String): String = rel.substringAfterLast('/').ifEmpty { "~" }
