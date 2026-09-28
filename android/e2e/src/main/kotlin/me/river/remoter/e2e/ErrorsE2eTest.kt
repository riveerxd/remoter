package me.river.remoter.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The error sheets. tools/run-device-e2e.sh puts the laptop or the phone into
 * each state first and passes `-e expect <state>`, then runs only the matching test.
 */
@RunWith(AndroidJUnit4::class)
class ErrorsE2eTest {
    private val p = Phone()

    private fun startSomewhere() {
        for (part in Args.folder.split('/')) p.tap(part, 20_000)
        p.tap(Args.folder.substringAfterLast('/').let { "Start in $it" })
        p.tap("Start session")
    }

    @Test
    fun vpn_off() {
        assumeTrue(Args.expect() == "vpn_off")
        p.launch()
        if (p.isPaired()) {
            p.text("WireGuard is off", 30_000)
            p.text("Turn on WireGuard")
        } else {
            // Before pairing, the first onboarding step waits for the tunnel and never ticks without it.
            p.text("Connect WireGuard", 10_000)
            p.text("Open WireGuard")
            assertFalse("moved on without a tunnel: ${p.visible()}", p.has("Scan the code from your laptop", 8_000))
        }
    }

    @Test
    fun laptop_down() {
        assumeTrue(Args.expect() == "laptop_down")
        p.launch()
        p.needPaired()
        // the monitor notices within about 21 s
        p.textHas("asleep or offline", 25_000)
        p.text("Retry")
    }

    @Test
    fun locked() {
        assumeTrue(Args.expect() == "locked")
        p.launch()
        p.needPaired()
        p.tap("Folder name or path")
        p.d.pressBack()
        startSomewhere()
        p.textHas("${Args.host} is locked", 15_000)
    }

    @Test
    fun rate_limited() {
        assumeTrue(Args.expect() == "rate_limited")
        p.launch()
        p.needPaired()
        p.tap("Folder name or path")
        p.d.pressBack()
        for (part in Args.folder.split('/')) p.tap(part, 20_000)
        // Mutations are limited to 10 a minute per device; the 11th is refused.
        var limited = false
        for (i in 1..12) {
            p.tap("+ New folder")
            p.type(By.clazz("android.widget.EditText"), "e2e-rl-${Args.runId}-$i")
            p.tap("Create")
            if (p.has("Too many requests", 3_000) || p.has("Try again in", 500)) {
                limited = true
                break
            }
        }
        assertTrue("never rate limited: ${p.visible()}", limited)
    }
}
