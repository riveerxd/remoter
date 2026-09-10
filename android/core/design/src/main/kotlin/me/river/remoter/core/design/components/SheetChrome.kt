package me.river.remoter.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Space

@Composable
fun DragHandle(modifier: Modifier = Modifier) {
    // The home sheet makes its handle tappable (expand, collapse), so it needs a name.
    // 48 dp to touch, 4 dp to see.
    Box(modifier.fillMaxWidth().heightIn(min = me.river.remoter.core.design.Touch.min).semantics { contentDescription = "Drag handle" }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Remoter.colors.line))
    }
}
