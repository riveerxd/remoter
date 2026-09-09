package me.river.remoter.core.design.components

import androidx.compose.animation.core.RepeatMode
import me.river.remoter.core.design.SwapText
import me.river.remoter.core.design.animatedTone
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.tnum

enum class StatusTone { Live, Ready, Warn, Danger, Muted }

@Composable
private fun StatusTone.color(): Color = when (this) {
    StatusTone.Live -> Remoter.colors.volt
    StatusTone.Ready -> Remoter.colors.ok
    StatusTone.Warn -> Remoter.colors.warn
    StatusTone.Danger -> Remoter.colors.danger
    StatusTone.Muted -> Remoter.colors.textMuted
}

/**
 * Status is never color alone: the dot always has its word. A volt dot on
 * white would be 1.34:1, so in light mode it gets a `text` ring.
 */
@Composable
fun StatusDot(tone: StatusTone, modifier: Modifier = Modifier, pulsing: Boolean = false) {
    val alpha = if (pulsing && !Remoter.reducedMotion) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse").value
    } else {
        1f
    }
    val ring = !Remoter.colors.isDark && tone == StatusTone.Live
    Box(
        modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(Shapes.pill)
            .background(animatedTone(tone.color(), "dot"))
            .then(if (ring) Modifier.border(1.dp, Remoter.colors.text, Shapes.pill) else Modifier),
    )
}

@Composable
fun StatusLabel(word: String, tone: StatusTone, modifier: Modifier = Modifier, pulsing: Boolean = false) {
    Row(modifier.clearAndSetSemantics { contentDescription = word }, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(tone, pulsing = pulsing)
        Spacer(Modifier.width(Space.s8))
        // wrap, don't clip. "{host} is locked" lost its end with a long hostname at 200%
        SwapText(word, Remoter.type.label.tnum(), Remoter.colors.text, Modifier.weight(1f, fill = false))
    }
}

@Composable
fun StatusPill(word: String, tone: StatusTone, modifier: Modifier = Modifier, pulsing: Boolean = false) {
    StatusLabel(
        word,
        tone,
        modifier
            .clip(Shapes.pill)
            .background(Remoter.colors.surface)
            .padding(horizontal = Space.s8 + Space.s4, vertical = Space.s4 + 2.dp),
        pulsing,
    )
}
