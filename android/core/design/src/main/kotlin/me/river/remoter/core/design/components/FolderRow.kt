package me.river.remoter.core.design.components

import androidx.compose.foundation.ExperimentalFoundationApi
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.Press
import me.river.remoter.core.design.animatedTone
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.tnum

enum class RowNote { Denied, AbsoluteSymlink, Unsupported }

@Immutable
data class FolderRowModel(
    val name: String,
    /** Muted line under the name, for pinned and recent rows. Replaces the badges. */
    val path: String? = null,
    val isGit: Boolean = false,
    val hasClaudeMd: Boolean = false,
    val folderCount: Int? = null,
    val running: Int = 0,
    val note: RowNote? = null,
    /** `null` hides the pin button, as on home rows that trail a chevron. */
    val pinned: Boolean? = null,
)

/**
 * The 64 dp row used everywhere. It grows with font scale instead of cutting
 * the name. No swipe actions: One UI takes back from both edges.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderRow(
    model: FolderRowModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onPin: (() -> Unit)? = null,
) {
    val c = Remoter.colors
    val t = Remoter.type
    val unsupported = model.note == RowNote.Unsupported
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Touch.row)
            .then(
                if (unsupported) {
                    Modifier
                } else {
                    Modifier.combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
                },
            )
            .alpha(if (unsupported) 0.6f else 1f)
            .padding(horizontal = Space.gutter, vertical = Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(Shapes.pill).background(c.surface),
            contentAlignment = Alignment.Center,
        ) {
            val glyph = when {
                model.note == RowNote.AbsoluteSymlink -> Glyphs.link
                model.isGit -> Glyphs.git
                else -> Glyphs.folder
            }
            Icon(glyph, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(Space.s16))
        Column(Modifier.weight(1f)) {
            // The laptop sends an unshowable byte as U+FFFD, which draws as a box with a question
            // mark in it on some fonts and nothing on others. A plain muted "?" says the same.
            val name = if (unsupported) model.name.replace('\uFFFD', '?') else model.name
            Text(name, style = t.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (model.path != null) {
                // At large font sizes the path and the running badge would squeeze each other down to
                // "...s/remoter", so the badge takes its own line and the path keeps the row's width.
                if (LocalDensity.current.fontScale > 1.3f) {
                    Text(model.path, style = t.label.tnum(), color = c.textMuted, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    if (model.running > 0) StatusLabel("${model.running} running", StatusTone.Live)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.path, style = t.label.tnum(), color = c.textMuted, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f, fill = false))
                        // A folder with a live session says so on home too, not only in the browser.
                        if (model.running > 0) {
                            Spacer(Modifier.width(Space.s8))
                            StatusLabel("${model.running} running", StatusTone.Live)
                        }
                    }
                }
            } else {
                Subtitle(model)
            }
        }
        if (onPin != null && model.pinned != null) {
            Box(
                Modifier
                    .size(Touch.min)
                    .combinedClickable(null, pressIndication(Press.Icon), role = Role.Button, onClick = onPin)
                    .clip(Shapes.pill)
                    .semantics {
                        contentDescription = "Pin ${model.name}"
                        stateDescription = if (model.pinned) "Pinned" else "Not pinned"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Glyphs.pin,
                    contentDescription = null,
                    tint = animatedTone(if (model.pinned) c.text else c.iconInactive, "pin"),
                    modifier = Modifier.size(20.dp),
                )
            }
        } else if (!unsupported) {
            Icon(Glyphs.chevron, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Subtitle(model: FolderRowModel) {
    val c = Remoter.colors
    val t = Remoter.type
    when (model.note) {
        RowNote.Denied -> NoteLine(Glyphs.lock, "Sessions can't start here")
        RowNote.AbsoluteSymlink -> NoteLine(null, "Opens by its real path")
        RowNote.Unsupported -> NoteLine(null, "Name has characters remoter won't touch")
        null -> {
            val badges = buildList {
                if (model.isGit) add("git")
                if (model.hasClaudeMd) add("CLAUDE.md")
                model.folderCount?.let { add(if (it == 1) "1 folder" else "$it folders") }
            }
            if (badges.isEmpty() && model.running == 0) return
            // wraps at 200%, the row just gets taller
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Space.s8),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (badges.isNotEmpty()) {
                    Text(badges.joinToString(" · "), style = t.label.tnum(), color = c.textMuted)
                }
                if (model.running > 0) {
                    StatusLabel("${model.running} running", StatusTone.Live)
                }
            }
        }
    }
}

@Composable
private fun NoteLine(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Remoter.colors.textMuted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Space.s4))
        }
        Text(text, style = Remoter.type.label, color = Remoter.colors.textMuted)
    }
}
