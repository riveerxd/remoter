package me.river.remoter.feature.browser

import me.river.remoter.core.net.DenyReason
import me.river.remoter.core.net.ListResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// only an older laptop still says untrusted
class UntrustedStartTest {
    private fun ui(spawnAllowed: Boolean, deny: DenyReason?) =
        BrowserUi("Documents", ListResponse("Documents", false, trusted = false, spawnAllowed = spawnAllowed, denyReason = deny, entries = emptyList(), truncated = false, partial = false), loading = false)

    @Test
    fun untrusted_does_not_block() {
        assertNull(ui(false, DenyReason.Untrusted).blocked)
    }

    @Test
    fun real_blocks_stay() {
        assertEquals(Blocked.Denied, ui(false, DenyReason.Denied).blocked)
        assertEquals(Blocked.Home, ui(false, DenyReason.Home).blocked)
    }
}
