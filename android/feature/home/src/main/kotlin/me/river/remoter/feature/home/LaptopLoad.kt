package me.river.remoter.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.Dur
import me.river.remoter.core.design.EaseOut
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.Press
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.tnum
import me.river.remoter.core.net.Resources
import java.util.Locale
import kotlin.math.roundToInt

// binary units, the way htop counts
internal fun bytes(n: Long): String {
    val gb = n / 1_073_741_824.0
    val mb = n / 1_048_576.0
    return when {
        gb >= 100 -> "${gb.roundToInt()} GB"
        gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1 -> "${mb.roundToInt()} MB"
        else -> "${(n / 1024.0).roundToInt()} KB"
    }
}

internal fun pct(part: Long, whole: Long): Int = if (whole <= 0) 0 else (part * 100 / whole).toInt().coerceIn(0, 100)

internal data class Gauge(val label: String, val pct: Int, val detail: String, val spoken: String) {
    val short get() = "$label $pct%"
}

internal fun Resources.gauges(): List<Gauge> {
    val cpu = cpuPct.roundToInt().coerceIn(0, 100)
    val mem = pct(memUsed, memTotal)
    val disk = pct(diskUsed, diskTotal)
    return listOf(
        Gauge("CPU", cpu, if (cores == 1) "1 core" else "$cores cores", "CPU $cpu percent busy"),
        Gauge("Memory", mem, "of ${bytes(memTotal)}", "Memory $mem percent used, ${bytes(memUsed)} of ${bytes(memTotal)}"),
        Gauge("Disk", disk, "${bytes(diskFree)} free", "Disk $disk percent used, ${bytes(diskFree)} free"),
    )
}

@Composable
private fun toneOf(p: Int): Color = when {
    p >= 95 -> Remoter.colors.danger
    p >= 85 -> Remoter.colors.warn
    else -> Remoter.colors.volt
}

@Composable
internal fun Meter(g: Gauge, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    val t = Remoter.type
    val fill by animateFloatAsState(g.pct / 100f, tween(Dur.base, easing = EaseOut), label = "meter")
    Column(modifier.clearAndSetSemantics { contentDescription = g.spoken }) {
        Text(g.label, style = t.label, color = c.textMuted, maxLines = 1)
        Text("${g.pct}%", style = t.title.tnum(), color = c.text, maxLines = 1)
        Spacer(Modifier.height(Space.s4))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(Shapes.pill).background(c.line)) {
            Box(Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).height(4.dp).clip(Shapes.pill).background(toneOf(g.pct)))
        }
        Spacer(Modifier.height(Space.s4))
        Text(g.detail, style = t.label.tnum(), color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// three meters don't fit side by side at big font sizes
@Composable
internal fun isLargeFont() = LocalDensity.current.fontScale > 1.3f

// no detail line so the card fits above + New on a normal phone
@Composable
internal fun CompactMeter(g: Gauge, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    val t = Remoter.type
    val fill by animateFloatAsState(g.pct / 100f, tween(Dur.base, easing = EaseOut), label = "meter")
    Column(modifier.clearAndSetSemantics { contentDescription = g.spoken }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(g.label, style = t.label, color = c.textMuted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(Space.s4))
            Text("${g.pct}%", style = t.bodyStrong.tnum(), color = c.text, maxLines = 1)
        }
        Spacer(Modifier.height(Space.s4))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(Shapes.pill).background(c.line)) {
            Box(Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).height(4.dp).clip(Shapes.pill).background(toneOf(g.pct)))
        }
    }
}

@Composable
internal fun Gauges(r: Resources, modifier: Modifier = Modifier, compact: Boolean = false) {
    val g = r.gauges()
    if (isLargeFont()) {
        Text(
            g.joinToString(" · ") { it.short },
            style = Remoter.type.bodyStrong.tnum(), color = Remoter.colors.text,
            modifier = modifier.clearAndSetSemantics { contentDescription = g.joinToString(", ") { it.spoken } },
        )
        return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.s16)) {
        g.forEach { if (compact) CompactMeter(it, Modifier.weight(1f)) else Meter(it, Modifier.weight(1f)) }
    }
}

@Composable
internal fun LoadCard(r: Resources, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    Row(
        modifier
            .fillMaxWidth()
            .clickable(null, pressIndication(Press.Card), role = Role.Button, onClickLabel = "Show processes", onClick = onOpen)
            .clip(Shapes.card)
            .background(c.surface.copy(alpha = if (c.isDark) 0.86f else 0.94f))
            .semantics(mergeDescendants = true) {}
            .padding(start = Space.cardPadding, top = Space.s8 + Space.s4, bottom = Space.s8 + Space.s4, end = Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Gauges(r, Modifier.weight(1f), compact = true)
        Icon(Glyphs.chevron, contentDescription = null, tint = c.textMuted, modifier = Modifier.padding(start = Space.s4).size(20.dp))
    }
}
