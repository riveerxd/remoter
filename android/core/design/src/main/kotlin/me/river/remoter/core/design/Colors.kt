package me.river.remoter.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class RemoterColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val line: Color,
    val text: Color,
    val textMuted: Color,
    val cta: Color,
    val onCta: Color,
    val volt: Color,
    val onVolt: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val terminal: Color,
    val onTerminal: Color,
) {
    // light volt on white is 1.34:1, never text or a lone ring
    val focusRing: Color get() = if (isDark) volt else text
    val focusInner: Color? get() = if (isDark) null else volt

    // must hold 3:1, ContrastTest checks it
    val iconInactive: Color get() = textMuted
}

val DarkColors = RemoterColors(
    isDark = true,
    bg = DarkTokens.bg, surface = DarkTokens.surface, surfaceRaised = DarkTokens.surfaceRaised,
    line = DarkTokens.line, text = DarkTokens.text, textMuted = DarkTokens.textMuted,
    cta = DarkTokens.cta, onCta = DarkTokens.onCta, volt = DarkTokens.volt, onVolt = DarkTokens.onVolt,
    ok = DarkTokens.ok, warn = DarkTokens.warn, danger = DarkTokens.danger,
    terminal = DarkTokens.terminal, onTerminal = DarkTokens.onTerminal,
)

val LightColors = RemoterColors(
    isDark = false,
    bg = LightTokens.bg, surface = LightTokens.surface, surfaceRaised = LightTokens.surfaceRaised,
    line = LightTokens.line, text = LightTokens.text, textMuted = LightTokens.textMuted,
    cta = LightTokens.cta, onCta = LightTokens.onCta, volt = LightTokens.volt, onVolt = LightTokens.onVolt,
    ok = LightTokens.ok, warn = LightTokens.warn, danger = LightTokens.danger,
    terminal = LightTokens.terminal, onTerminal = LightTokens.onTerminal,
)

val LocalRemoterColors = staticCompositionLocalOf { DarkColors }
