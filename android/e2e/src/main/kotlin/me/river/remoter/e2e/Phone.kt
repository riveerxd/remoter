package me.river.remoter.e2e

import android.content.Intent
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue

const val APP = "me.river.remoter.e2e"

/**
 * Everything the suite knows about the laptop arrives as instrumentation
 * arguments from tools/run-device-e2e.sh. There is no channel back into the
 * app: the laptop side is set up by the script between runs.
 */
object Args {
    private val a: Bundle get() = InstrumentationRegistry.getArguments()
    val pairLink: String? get() = a.getString("pair_link")
    val host: String get() = a.getString("host") ?: "r1v3r"
    val folder: String get() = a.getString("folder") ?: "Projects"
    val runId: String get() = a.getString("run_id") ?: System.currentTimeMillis().toString(36)
    fun expect(): String? = a.getString("expect")
}

/** A line the script reads from `am instrument -r` output. Never secrets: the code shown on screen, nothing else. */
fun report(key: String, value: String) {
    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(key, value) })
}

class Phone {
    val d: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun launch() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val i = ctx.packageManager.getLaunchIntentForPackage(APP) ?: error("$APP is not installed")
        d.pressHome()
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull("app never came up", d.wait(Until.hasObject(By.pkg(APP).depth(0)), 10_000))
    }

    fun find(sel: BySelector, ms: Long = 10_000): UiObject2 =
        d.wait(Until.findObject(sel), ms) ?: throw AssertionError("not on screen after ${ms}ms: $sel\n${visible()}")

    fun text(t: String, ms: Long = 10_000) = find(By.text(t), ms)
    fun textHas(t: String, ms: Long = 10_000) = find(By.textContains(t), ms)
    fun has(t: String, ms: Long = 2_000): Boolean = d.wait(Until.hasObject(By.textContains(t)), ms) == true
    fun tap(t: String, ms: Long = 10_000) = text(t, ms).click()

    fun type(into: BySelector, value: String) {
        val f = find(into)
        f.click()
        f.text = value
    }

    fun visible(): String = d.findObjects(By.pkg(APP)).flatMap { o -> listOfNotNull(o.text, o.contentDescription) }.distinct().joinToString(" | ")

    fun isPaired(): Boolean = d.wait(Until.hasObject(By.desc("New session")), 6_000) == true
    fun tapNew() = find(By.desc("New session")).click()
    fun needPaired() = assumeTrue("not paired with the staging laptop; run the pairing step first", isPaired())
}
