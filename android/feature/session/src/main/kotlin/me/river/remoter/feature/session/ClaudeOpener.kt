package me.river.remoter.feature.session

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import me.river.remoter.core.net.ClaudeLink

// URLs go to the Claude app alone: any browser resolves an https link and would hide the fallbacks
object ClaudeOpener {
    const val PACKAGE = "com.anthropic.claude"

    enum class Try { SessionUrl, EnvironmentUrl, Launcher, Store }

    // if the environment link turns out to land inside the session more reliably, swap the first two
    val ORDER = listOf(Try.SessionUrl, Try.EnvironmentUrl, Try.Launcher, Try.Store)

    fun candidate(t: Try, link: ClaudeLink?, pm: PackageManager): Intent? = when (t) {
        Try.SessionUrl -> link?.sessionUrl?.let(::viewInClaude)
        Try.EnvironmentUrl -> link?.environmentUrl?.let(::viewInClaude)
        Try.Launcher -> pm.getLaunchIntentForPackage(PACKAGE)
        Try.Store -> Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun viewInClaude(url: String): Intent? {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host != "claude.ai") return null
        return Intent(Intent.ACTION_VIEW, uri).setPackage(PACKAGE)
    }

    fun resolve(pm: PackageManager, link: ClaudeLink?, order: List<Try> = ORDER): Pair<Try, Intent>? {
        for (t in order) {
            val i = candidate(t, link, pm) ?: continue
            if (t == Try.Launcher || pm.resolveActivity(i, 0) != null) return t to i
        }
        return null
    }

    fun open(context: Context, link: ClaudeLink?): Boolean {
        val (_, intent) = resolve(context.packageManager, link) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
