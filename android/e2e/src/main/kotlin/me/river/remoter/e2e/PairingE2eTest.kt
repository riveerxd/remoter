package me.river.remoter.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Onboarding with the pairing link pasted instead of scanned. The laptop side
 * (`remoterctl pair` on the staging config) reads the confirmation code from
 * /dev/tty on purpose, so a person types it; this test only shows it.
 */
@RunWith(AndroidJUnit4::class)
class PairingE2eTest {
    private val p = Phone()

    @Test
    fun pair_by_pasted_link() {
        val link = Args.pairLink
        assumeTrue("no pair_link argument", link != null)
        p.launch()
        // Step 1 ticks on its own once our VPN network is up and the laptop answers.
        p.text("Scan the code from your laptop", 30_000)
        p.tap("Paste the pairing link instead")
        p.type(By.clazz("android.widget.EditText"), link!!)
        p.tap("Pair")
        p.text("Type this on your laptop", 30_000)
        val code = p.find(By.text(Regex("\\d{3} \\d{3}").toPattern())).text.replace(" ", "")
        report("pair_code", code)
        // Five minutes: the pairing window, while someone types the code on the laptop.
        p.text("Start in…", 300_000)
    }
}
