package me.river.remoter.core.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

// WCAG: 4.5:1 for body text, 3:1 for icons and focus rings
class ContrastTest {
    private fun lin(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun over(fg: Color, bg: Color): Color = if (fg.alpha >= 1f) fg else Color(
        fg.red * fg.alpha + bg.red * (1 - fg.alpha),
        fg.green * fg.alpha + bg.green * (1 - fg.alpha),
        fg.blue * fg.alpha + bg.blue * (1 - fg.alpha),
    )

    private fun ratio(a0: Color, b: Color): Double {
        val a = over(a0, b)
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private val failures = mutableListOf<String>()

    private fun need(theme: String, fg: String, f: Color, bg: String, b: Color, min: Double) {
        val r = ratio(f, b)
        println("%-5s %-11s on %-9s %.2f:1 (needs %.1f)".format(theme, fg, bg, r, min))
        if (r < min) failures += "$theme $fg on $bg is %.2f:1, needs %.1f".format(r, min)
    }

    private fun checkTheme(name: String, c: RemoterColors) {
        val grounds = listOf("bg" to c.bg, "surface" to c.surface, "raised" to c.surfaceRaised)
        val body = buildList {
            add("text" to c.text)
            add("textMuted" to c.textMuted)
            add("ok" to c.ok)
            add("warn" to c.warn)
            add("danger" to c.danger)
            // in light mode volt is only ever a fill under onVolt
            if (c.isDark) add("volt" to c.volt)
        }
        for ((fn, f) in body) for ((bn, b) in grounds) need(name, fn, f, bn, b, 4.5)
        for ((bn, b) in grounds) need(name, "focusRing", c.focusRing, bn, b, 3.0)
        for ((bn, b) in grounds) need(name, "iconInactive", c.iconInactive, bn, b, 3.0)
        need(name, "onCta", c.onCta, "cta", c.cta, 4.5)
        need(name, "onVolt", c.onVolt, "volt", c.volt, 4.5)
        need(name, "onTerminal", c.onTerminal, "terminal", c.terminal, 4.5)
    }

    @Test
    fun every_pair_passes_in_both_themes() {
        checkTheme("dark", DarkColors)
        checkTheme("light", LightColors)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun light_volt_too_faint_for_text() {
        // why the light mode rule exists. if this ever passes 3:1, revisit
        assertTrue(ratio(LightColors.volt, LightColors.bg) < 3.0)
    }
}
