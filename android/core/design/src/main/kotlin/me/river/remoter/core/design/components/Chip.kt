package me.river.remoter.core.design.components

import androidx.compose.foundation.background
import me.river.remoter.core.design.animatedTone
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.Press
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch

// looks 32 dp tall, hit area is the full 48
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    Box(
        modifier
            .heightIn(min = Touch.min)
            .clickable(null, pressIndication(Press.Button), role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 32.dp)
                .clip(Shapes.pill)
                .background(animatedTone(if (selected) c.cta else c.surface, "chip fill"))
                // Unselected chips also sit on surface cards, where fill alone disappears.
                .border(1.dp, animatedTone(if (selected) c.cta else c.line, "chip edge"), Shapes.pill)
                .padding(horizontal = Space.s8 + Space.s4, vertical = Space.s4 + 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = Remoter.type.label, color = animatedTone(if (selected) c.onCta else c.text, "chip text"))
        }
    }
}
