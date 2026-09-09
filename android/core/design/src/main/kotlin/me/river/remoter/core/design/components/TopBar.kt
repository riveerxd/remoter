package me.river.remoter.core.design.components

import androidx.compose.foundation.background
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.Press
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch

@Composable
fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(Touch.min).clickable(null, pressIndication(Press.Icon), role = Role.Button, onClickLabel = "Back", onClick = onBack).clip(Shapes.pill),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).clip(Shapes.pill).background(Remoter.colors.surface), contentAlignment = Alignment.Center) {
            Icon(Glyphs.back, contentDescription = "Back", tint = Remoter.colors.text, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun TopBar(onBack: () -> Unit, title: String? = null, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Touch.row).padding(start = Space.s8, end = Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onBack)
        Spacer(Modifier.width(Space.s8))
        if (title != null) {
            Text(
                title,
                style = Remoter.type.title,
                color = Remoter.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        trailing()
    }
}
