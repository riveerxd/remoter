package me.river.remoter.core.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.PressSpring
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.animatedAlpha
import me.river.remoter.core.design.SwapText
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.Press
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.tnum

// never disabled: a blocked action stays tappable and the caller says why on tap.
// loading keeps the label's space so the button doesn't resize under the spinner
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    numeric: Boolean = false,
) {
    PillButton(text, onClick, modifier, Remoter.colors.cta, Remoter.colors.onCta, loading, numeric)
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, loading: Boolean = false) {
    PillButton(text, onClick, modifier, Remoter.colors.surface, Remoter.colors.text, loading = loading, numeric = false)
}

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    container: Color,
    content: Color,
    loading: Boolean,
    numeric: Boolean,
) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = Touch.primaryButton)
            // Before the background, so the whole pill sinks under the finger, not just its label.
            .clickable(null, pressIndication(Press.Button), role = Role.Button, onClick = onClick)
            .clip(Shapes.pill)
            .background(container)
            .padding(horizontal = Space.s24, vertical = Space.s16),
        contentAlignment = Alignment.Center,
    ) {
        val style = Remoter.type.bodyStrong.let { if (numeric) it.tnum() else it }
        val labelAlpha = animatedAlpha(!loading, "label")
        SwapText(text, style, content, Modifier.alpha(labelAlpha))
        if (loading) {
            if (Remoter.reducedMotion) {
                // Reduced motion stops every loop, so the spinner holds still as a partial ring.
                CircularProgressIndicator({ 0.3f }, Modifier.size(20.dp), color = content, strokeWidth = 2.dp, trackColor = Color.Transparent)
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), color = content, strokeWidth = 2.dp, trackColor = Color.Transparent)
            }
        }
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    Box(
        modifier
            .heightIn(min = Touch.min)
            .clickable(null, pressIndication(Press.Button), role = Role.Button, onClick = onClick)
            .clip(Shapes.pill)
            .padding(horizontal = Space.s16),
        contentAlignment = Alignment.Center,
    ) {
        SwapText(text, Remoter.type.bodyStrong, if (danger) Remoter.colors.danger else Remoter.colors.text)
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(Touch.min)
            .clickable(null, pressIndication(Press.Icon), role = Role.Button, onClick = onClick)
            .clip(Shapes.pill)
            .background(Remoter.colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = Remoter.colors.text, modifier = Modifier.size(22.dp))
    }
}
