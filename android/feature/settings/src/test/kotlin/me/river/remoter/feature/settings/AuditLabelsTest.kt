package me.river.remoter.feature.settings

import me.river.remoter.core.design.components.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Test

class AuditLabelsTest {
    @Test
    fun known_actions_read_as_verbs() {
        assertEquals("Started a session", auditAction("spawn", "ok"))
        assertEquals("Showed terminal output", auditAction("view_token", "ok"))
        assertEquals("Locked itself", auditAction("lock", "auto"))
    }

    @Test
    fun unknown_names_read_as_words() {
        assertEquals("Some new thing", auditAction("some_new_thing", "ok"))
        assertEquals("Spawn failed" to StatusTone.Danger, auditResult("spawn", "spawn_failed"))
        assertEquals("Error 502" to StatusTone.Danger, auditResult("spawn", "http_502"))
    }

    @Test
    fun lock_reason_not_failure() {
        assertEquals("From phone" to StatusTone.Muted, auditResult("lock", "phone"))
        assertEquals("OK" to StatusTone.Muted, auditResult("spawn", "ok"))
        assertEquals("Bad signature" to StatusTone.Danger, auditResult("spawn", "sig_invalid"))
    }

    @Test
    fun signal_reads_as_stop() {
        assertEquals("Stopped a process", auditAction("signal", "ok"))
        assertEquals("firefox · 2210 · SIGTERM", auditPath("signal", "firefox 2210 term"))
        assertEquals("4242 · SIGKILL", auditPath("signal", "4242 kill"))
        assertEquals("~/Projects/remoter", auditPath("spawn", "Projects/remoter"))
    }
}
