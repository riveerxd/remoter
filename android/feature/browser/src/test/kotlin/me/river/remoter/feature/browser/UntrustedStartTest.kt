package me.river.remoter.feature.browser

import me.river.remoter.core.net.DenyReason
import me.river.remoter.core.net.ListResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Start used to stop at "run this on the laptop"; trusting now happens in the start sheet, from the phone. */
class UntrustedStartTest {
    private fun ui(spawnAllowed: Boolean, deny: DenyReason?) =
        BrowserUi("Documents", ListResponse("Documents", false, trusted = false, spawnAllowed = spawnAllowed, denyReason = deny, entries = emptyList(), truncated = false, partial = false), loading = false)

    @Test
    fun an_untrusted_folder_no_longer_blocks_start() {
        assertNull(ui(false, DenyReason.Untrusted).blocked)
    }

    @Test
    fun the_real_blocks_stay() {
        assertEquals(Blocked.Denied, ui(false, DenyReason.Denied).blocked)
        assertEquals(Blocked.Home, ui(false, DenyReason.Home).blocked)
    }
}
