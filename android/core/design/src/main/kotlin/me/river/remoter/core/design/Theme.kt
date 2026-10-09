package me.river.remoter.core.design

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

object Remoter {
    val colors: RemoterColors
        @Composable @ReadOnlyComposable get() = LocalRemoterColors.current
    val type: RemoterType
        @Composable @ReadOnlyComposable get() = LocalRemoterType.current
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

// no dynamic color: status colours must mean the same thing every day
@Composable
fun RemoterTheme(
    dark: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean? = null,
    animate: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemReduced = rememberSystemReducedMotion()
    val target = if (dark) DarkColors else LightColors
    val colors = if (animate && !(reducedMotion ?: systemReduced)) blended(target) else target
    val m3 = if (dark) {
        darkColorScheme(
            primary = colors.cta, onPrimary = colors.onCta, background = colors.bg, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text, surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.textMuted, outline = colors.line, error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.cta, onPrimary = colors.onCta, background = colors.bg, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text, surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.textMuted, outline = colors.line, error = colors.danger,
        )
    }
    CompositionLocalProvider(
        LocalRemoterColors provides colors,
        LocalRemoterType provides RemoterType(),
        LocalReducedMotion provides (reducedMotion ?: systemReduced),
    ) {
        MaterialTheme(colorScheme = m3) {
            // no ripple anywhere, everything gets the press scale instead
            CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides pressIndication(Press.Card), content = content)
        }
    }
}

// isDark flips at once: it picks things like the light mode focus ring, which can't be halfway
@Composable
private fun blended(target: RemoterColors): RemoterColors {
    @Composable
    fun a(c: androidx.compose.ui.graphics.Color) = androidx.compose.animation.animateColorAsState(c, androidx.compose.animation.core.tween(Dur.base, easing = EaseOut), label = "theme").value
    return RemoterColors(
        isDark = target.isDark,
        bg = a(target.bg), surface = a(target.surface), surfaceRaised = a(target.surfaceRaised), line = a(target.line),
        text = a(target.text), textMuted = a(target.textMuted), cta = a(target.cta), onCta = a(target.onCta),
        volt = a(target.volt), onVolt = a(target.onVolt), ok = a(target.ok), warn = a(target.warn), danger = a(target.danger),
        terminal = a(target.terminal), onTerminal = a(target.onTerminal),
    )
}

@Composable
private fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember { mutableStateOf(read()) }
    // "Remove animations" can be flipped while we're open
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = read()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
