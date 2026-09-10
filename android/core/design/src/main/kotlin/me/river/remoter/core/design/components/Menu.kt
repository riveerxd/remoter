package me.river.remoter.core.design.components

import androidx.compose.foundation.background
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.Press
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Space

/** Glyph column plus the gap after it: dividers start here so they line up with the labels. */
private val MenuInset = 20.dp + Space.s16

@Composable
fun ColumnScope.MenuHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }) {
        Text(title, style = Remoter.type.title, color = Remoter.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, style = Remoter.type.label, color = Remoter.colors.textMuted, maxLines = 1, overflow = TextOverflow.StartEllipsis) }
    }
    Spacer(Modifier.height(Space.s16))
}

@Composable
fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit, divider: Boolean = true, danger: Boolean = false) {
    val c = Remoter.colors
    val tint = if (danger) c.danger else c.text
    Column(Modifier.fillMaxWidth()) {
        if (divider) Box(Modifier.fillMaxWidth().padding(start = MenuInset).height(1.dp).background(c.line))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(null, pressIndication(Press.Card), role = Role.Button, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.s16))
            Text(label, style = Remoter.type.body, color = tint)
        }
    }
}
