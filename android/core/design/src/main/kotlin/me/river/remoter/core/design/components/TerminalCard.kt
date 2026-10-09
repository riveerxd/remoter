package me.river.remoter.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.ImmutableList
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space

// lines never wrap: that breaks every table and box claude draws, so the block scrolls sideways
@Composable
fun TerminalCard(lines: ImmutableList<String>, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Shapes.technical)
            .background(Remoter.colors.terminal)
            .horizontalScroll(rememberScrollState())
            .padding(Space.cardPadding),
    ) {
        val style = Remoter.type.mono.copy(fontSize = fontSize, lineHeight = fontSize * 1.5f)
        lines.forEach { Text(it, style = style, color = Remoter.colors.onTerminal, softWrap = false, maxLines = 1) }
    }
}
