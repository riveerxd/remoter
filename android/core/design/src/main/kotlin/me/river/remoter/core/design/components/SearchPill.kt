package me.river.remoter.core.design.components

import androidx.compose.foundation.background
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.Press
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch

/** "Where to?" for folders. It sits in the sheet, so it is in the thumb zone. */
@Composable
fun SearchPill(onClick: () -> Unit, modifier: Modifier = Modifier, placeholder: String = "Folder name or path") {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Touch.searchPill)
            .clickable(null, pressIndication(Press.Button), role = Role.Button, onClickLabel = "Search folders", onClick = onClick)
            .clip(Shapes.pill)
            .background(Remoter.colors.surface)
            .padding(horizontal = Space.s16, vertical = Space.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyphs.search, contentDescription = null, tint = Remoter.colors.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Space.s8 + Space.s4))
        Text(placeholder, style = Remoter.type.body, color = Remoter.colors.textMuted)
    }
}
