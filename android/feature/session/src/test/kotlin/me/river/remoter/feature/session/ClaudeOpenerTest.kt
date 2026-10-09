package me.river.remoter.feature.session

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import me.river.remoter.core.net.ClaudeLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ClaudeOpenerTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val pm = ctx.packageManager
    private val link = ClaudeLink(
        "session_01Hq7cXv2mTnR4bWkYe9pLsA",
        "https://claude.ai/code/session_01Hq7cXv2mTnR4bWkYe9pLsA",
        "https://claude.ai/code?environment=env_01Kd3fPzQw8nVb2sLxRt6uYm",
    )

    private fun handles(intent: Intent, pkg: String) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.Main" }
        }
        shadowOf(pm).addResolveInfoForIntent(intent, info)
    }

    private fun installClaudeLauncher() {
        val comp = ComponentName(ClaudeOpener.PACKAGE, "${ClaudeOpener.PACKAGE}.Main")
        shadowOf(pm).addActivityIfNotPresent(comp)
        shadowOf(pm).addIntentFilterForActivity(
            comp,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun view(t: ClaudeOpener.Try) = ClaudeOpener.candidate(t, link, pm)!!

    @Test
    fun session_url_first() {
        handles(view(ClaudeOpener.Try.SessionUrl), ClaudeOpener.PACKAGE)
        handles(view(ClaudeOpener.Try.EnvironmentUrl), ClaudeOpener.PACKAGE)
        val (t, i) = ClaudeOpener.resolve(pm, link)!!
        assertEquals(ClaudeOpener.Try.SessionUrl, t)
        assertEquals(link.sessionUrl, i.dataString)
        assertEquals(ClaudeOpener.PACKAGE, i.`package`)
    }

    @Test
    fun environment_url_next() {
        handles(view(ClaudeOpener.Try.EnvironmentUrl), ClaudeOpener.PACKAGE)
        assertEquals(ClaudeOpener.Try.EnvironmentUrl, ClaudeOpener.resolve(pm, link)!!.first)
    }

    @Test
    fun launcher_next() {
        installClaudeLauncher()
        assertEquals(ClaudeOpener.Try.Launcher, ClaudeOpener.resolve(pm, link)!!.first)
    }

    @Test
    fun store_last() {
        handles(ClaudeOpener.candidate(ClaudeOpener.Try.Store, link, pm)!!, "com.android.vending")
        assertEquals(ClaudeOpener.Try.Store, ClaudeOpener.resolve(pm, link)!!.first)
    }

    @Test
    fun nothing_resolves() {
        assertNull(ClaudeOpener.resolve(pm, link))
    }

    @Test
    fun order_swappable() {
        handles(view(ClaudeOpener.Try.SessionUrl), ClaudeOpener.PACKAGE)
        handles(view(ClaudeOpener.Try.EnvironmentUrl), ClaudeOpener.PACKAGE)
        val swapped = listOf(ClaudeOpener.Try.EnvironmentUrl, ClaudeOpener.Try.SessionUrl)
        assertEquals(ClaudeOpener.Try.EnvironmentUrl, ClaudeOpener.resolve(pm, link, swapped)!!.first)
    }

    @Test
    fun rejects_links_off_claude_ai() {
        val evil = link.copy(sessionUrl = "https://evil.example/code/x", environmentUrl = "javascript:alert(1)")
        assertNull(ClaudeOpener.candidate(ClaudeOpener.Try.SessionUrl, evil, pm))
        assertNull(ClaudeOpener.candidate(ClaudeOpener.Try.EnvironmentUrl, evil, pm))
    }

    @Test
    fun no_link_opens_app() {
        installClaudeLauncher()
        assertEquals(ClaudeOpener.Try.Launcher, ClaudeOpener.resolve(pm, null)!!.first)
    }
}
