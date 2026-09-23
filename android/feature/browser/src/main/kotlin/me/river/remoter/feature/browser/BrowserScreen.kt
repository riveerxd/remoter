package me.river.remoter.feature.browser

import androidx.compose.animation.AnimatedVisibility
import me.river.remoter.core.design.components.RemoterSwitch
import me.river.remoter.core.design.ListPlacement
import me.river.remoter.core.design.ListFadeOut
import me.river.remoter.core.design.ListFadeIn
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.Press
import me.river.remoter.core.design.animatedAlpha
import me.river.remoter.core.design.animatedTone
import me.river.remoter.core.design.SwapText
import me.river.remoter.core.design.FadeSwap
import me.river.remoter.core.design.FadeInPlace
import me.river.remoter.core.design.leave
import me.river.remoter.core.design.arrive
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.stateDescription
import me.river.remoter.core.design.Dur
import me.river.remoter.core.design.EaseIn
import me.river.remoter.core.design.EaseOut
import me.river.remoter.core.design.components.StatusDot
import me.river.remoter.core.design.components.StatusTone
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.components.Chip
import me.river.remoter.core.design.components.FolderRow
import me.river.remoter.core.design.components.FolderRowModel
import me.river.remoter.core.design.components.PrimaryButton
import me.river.remoter.core.design.components.ProgressLine
import me.river.remoter.core.design.components.QuietButton
import me.river.remoter.core.design.components.BackButton
import me.river.remoter.core.design.components.RowNote
import me.river.remoter.core.design.components.SecondaryButton
import me.river.remoter.core.design.components.SkeletonRow
import me.river.remoter.core.design.rememberAfter
import me.river.remoter.core.design.rememberHeld
import me.river.remoter.core.design.sharedContainer
import me.river.remoter.core.design.shake
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.DenyReason
import me.river.remoter.core.net.FsEntry
import me.river.remoter.core.net.SearchHit
import me.river.remoter.core.net.SymlinkKind
import me.river.remoter.core.net.displayPath
import me.river.remoter.feature.session.CommandBlock
import me.river.remoter.feature.session.ErrorActions
import me.river.remoter.feature.session.ErrorContent
import me.river.remoter.feature.session.copy

data class BrowserCallbacks(
    val onBack: () -> Unit = {},
    val onCrumb: (String) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onEntryMenu: (FsEntry) -> Unit = {},
    val onSymlink: (FsEntry) -> Unit = {},
    val onPin: (String) -> Unit = {},
    val onNewFolder: (String) -> Unit = {},
    val onNewName: (String) -> Unit = {},
    val onToggleGit: () -> Unit = {},
    val onSubmitNew: () -> Unit = {},
    val onCancelNew: () -> Unit = {},
    val onStartHere: () -> Unit = {},
    val onBlocked: (Blocked) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRetrySearch: () -> Unit = {},
    val onOpenWireGuard: () -> Unit = {},
)

@Composable
fun BrowserContent(ui: BrowserUi, cb: BrowserCallbacks, focusSearch: Boolean, list: LazyListState = rememberLazyListState()) {
    val c = Remoter.colors
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        TopBar(ui, cb, focusSearch)
        // Old list stays while loading. Progress line past 150 ms, skeletons past 1 s (most listings
        // land well under that), held 300 ms so they don't flicker.
        val slow = rememberAfter(ui.loading, 150)
        val skeleton = rememberHeld(rememberAfter(ui.loading && ui.list == null, 1_000), 300)
        Box(Modifier.height(2.dp).fillMaxWidth()) { FadeInPlace(slow) { ProgressLine() } }
        StaleBanner(ui, cb)
        Box(Modifier.weight(1f)) {
            // The problem side carries its own error so it can still draw while fading out of a
            // state that has none.
            val shown: Pair<String, me.river.remoter.core.net.AppError?> = when {
                skeleton -> "skeleton" to null
                ui.list == null && ui.error != null -> "problem" to ui.error
                ui.list == null -> "none" to null
                else -> "list" to null
            }
            FadeSwap(shown, key = { it.first }) { (kind, error) ->
                when (kind) {
                    "skeleton" -> Column(Modifier.fillMaxSize().semantics { contentDescription = "Loading folders" }) { repeat(6) { SkeletonRow() } }
                    "problem" -> error?.let { Problem(it, ui.hostname, cb) }
                    "list" -> Listing(ui, cb, list)
                    else -> Box(Modifier.fillMaxSize())
                }
            }
        }
        BottomBar(ui, cb)
    }
}

@Composable
private fun TopBar(ui: BrowserUi, cb: BrowserCallbacks, focusSearch: Boolean) {
    val c = Remoter.colors
    val t = Remoter.type
    val focus = remember { FocusRequester() }
    // Once per entry: coming back to this folder must not pop the keyboard up again.
    var focusedOnce by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(focusSearch) {
        if (focusSearch && !focusedOnce) {
            focusedOnce = true
            runCatching { focus.requestFocus() }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.s8, vertical = Space.s8), verticalAlignment = Alignment.CenterVertically) {
        BackButton(cb.onBack)
        Spacer(Modifier.width(Space.s8))
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = Touch.searchPill)
                .sharedContainer("search")
                .clip(Shapes.pill)
                .background(c.surface)
                .padding(horizontal = Space.s16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyphs.search, null, tint = c.text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.s8))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                // Leaves layout once faded: at 200% it wraps to two lines, and held at alpha 0 it kept the
                // pill tall under a typed query.
                FadeInPlace(ui.query.isEmpty()) { Text("Folder name or path", style = t.body, color = c.textMuted) }
                BasicTextField(
                    ui.query, cb.onQuery,
                    singleLine = true,
                    textStyle = t.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search folders" },
                )
            }
            AnimatedVisibility(ui.query.isNotEmpty(), enter = fadeIn(arrive()) + scaleIn(arrive(), initialScale = 0.6f), exit = fadeOut(leave()) + scaleOut(leave(), targetScale = 0.6f)) {
                // Pulled into the pill's end padding so the 48 dp target doesn't push the text in.
                Box(
                    Modifier
                        .offset(x = Space.s8)
                        .size(Touch.min)
                        .clickable(null, pressIndication(Press.Icon), role = Role.Button) { cb.onQuery("") }
                        .clip(Shapes.pill)
                        .semantics { contentDescription = "Clear search" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyphs.close, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    Breadcrumbs(ui.path, cb.onCrumb)
}

@Composable
private fun Breadcrumbs(path: String, onCrumb: (String) -> Unit) {
    val parts = path.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip("~", parts.isEmpty(), { onCrumb("") })
        parts.forEachIndexed { i, p ->
            Text(" › ", style = Remoter.type.label, color = Remoter.colors.textMuted)
            Chip(p, i == parts.lastIndex, { onCrumb(parts.take(i + 1).joinToString("/")) })
        }
    }
}

@Composable
private fun Listing(ui: BrowserUi, cb: BrowserCallbacks, state: LazyListState) {
    val c = Remoter.colors
    val t = Remoter.type
    val entries = ui.visible
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        item(key = "new") { NewFolderRow(ui, cb, Modifier.animateItem(fadeInSpec = ListFadeIn, placementSpec = ListPlacement, fadeOutSpec = ListFadeOut)) }
        items(entries, key = { "e:" + it.name }) { e ->
            val child = if (ui.path.isEmpty()) e.name else "${ui.path}/${e.name}"
            FolderRow(
                e.toModel(child in ui.pinned),
                onClick = {
                    when {
                        e.unsupported -> {}
                        e.symlink == SymlinkKind.Absolute -> cb.onSymlink(e)
                        else -> cb.onOpen(child)
                    }
                },
                onLongClick = { if (!e.unsupported) cb.onEntryMenu(e) },
                onPin = if (e.unsupported) null else ({ cb.onPin(child) }),
                modifier = Modifier.animateItem(fadeInSpec = ListFadeIn, placementSpec = ListPlacement, fadeOutSpec = ListFadeOut),
            )
        }
        if (entries.isEmpty() && ui.query.isBlank() && !ui.newFolder.editing) {
            item(key = "empty") {
                // New folder is the row above and Start is the button below, so no buttons here.
                Column(Modifier.animateItem(fadeInSpec = ListFadeIn, placementSpec = ListPlacement, fadeOutSpec = ListFadeOut).fillMaxWidth().padding(Space.s32), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                    Text("No folders in ${ui.name}", style = t.title, color = c.text)
                    Text("Create one, or start a session right here.", style = t.body, color = c.textMuted)
                }
            }
        }
        if (ui.searchError != null && ui.query.isNotBlank()) {
            item(key = "search-error") {
                Column(Modifier.animateItem(fadeInSpec = ListFadeIn, placementSpec = ListPlacement, fadeOutSpec = ListFadeOut).fillMaxWidth().padding(Space.s24), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                    Text("Couldn't search ${ui.hostname}", style = t.title, color = c.text)
                    ui.searchError.copy(ui.hostname).title.let { Text(it, style = t.body, color = c.textMuted) }
                    SecondaryButton("Retry", cb.onRetrySearch)
                }
            }
        }
        if (entries.isEmpty() && ui.query.isNotBlank() && ui.deeper.isEmpty() && !ui.searching && ui.searchError == null) {
            item(key = "none") {
                val q = ui.query.trim()
                Column(Modifier.animateItem(fadeInSpec = ListFadeIn, placementSpec = ListPlacement, fadeOutSpec = ListFadeOut).fillMaxWidth().padding(Space.s24), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                    Text("Nothing called '$q' here", style = t.title, color = c.text)
                    SecondaryButton("Create folder '$q' here", { cb.onNewFolder(q) })
                }
            }
        }
        if (ui.deeper.isNotEmpty()) {
            item(key = "deeper") {
                Text("Deeper matches", style = t.label, color = c.textMuted, modifier = Modifier.animateItem(fadeInSpec = ListFadeIn, placementSpec = ListPlacement, fadeOutSpec = ListFadeOut).padding(start = Space.gutter, top = Space.s16, bottom = Space.s4))
            }
            items(ui.deeper, key = { "d:" + it.path }) { h -> DeeperRow(h, ui.path, cb, Modifier.animateItem(fadeInSpec = ListFadeIn, placementSpec = ListPlacement, fadeOutSpec = ListFadeOut)) }
        }
        item { Spacer(Modifier.height(Space.s24)) }
    }
}

@Composable
private fun DeeperRow(h: SearchHit, from: String, cb: BrowserCallbacks, modifier: Modifier = Modifier) {
    val rel = if (from.isNotEmpty() && h.path.startsWith("$from/")) h.path.removePrefix("$from/") else displayPath(h.path)
    FolderRow(FolderRowModel(h.name, path = rel, isGit = h.isGit), onClick = { cb.onOpen(h.path) }, modifier = modifier)
}

private fun FsEntry.toModel(pinned: Boolean): FolderRowModel = FolderRowModel(
    name = name,
    isGit = isGit,
    hasClaudeMd = hasClaudeMd,
    folderCount = fileCount,
    running = sessionCount,
    note = when {
        unsupported -> RowNote.Unsupported
        symlink == SymlinkKind.Absolute -> RowNote.AbsoluteSymlink
        denyReason == DenyReason.Denied || denyReason == DenyReason.Home -> RowNote.Denied
        denyReason == DenyReason.Untrusted -> RowNote.Untrusted
        else -> null
    },
    pinned = pinned,
)

/**
 * "+ New folder" turns into a field in place and stays 64 dp: the rules,
 * errors and buttons float just below it over the list, so nothing moves.
 */
@Composable
private fun NewFolderRow(ui: BrowserUi, cb: BrowserCallbacks, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    val t = Remoter.type
    val nf = ui.newFolder
    val focus = remember { FocusRequester() }
    val haptics = me.river.remoter.core.design.rememberHaptics()
    LaunchedEffect(nf.shake) { if (nf.shake > 0) haptics.reject() }
    // The button and the field crossfade over each other in the same 64 dp, so opening it moves
    // nothing; the rules card under it grows out of the row as it fades in.
    Box(modifier.fillMaxWidth().height(Touch.row).zIndex(1f).testTag("new-folder").shake(nf.shake)) {
        AnimatedVisibility(!nf.editing, enter = fadeIn(arrive()), exit = fadeOut(leave())) {
            Row(
                Modifier.fillMaxSize().clickable(role = Role.Button) { cb.onNewFolder("") }.padding(horizontal = Space.gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(Shapes.pill).background(c.surface), contentAlignment = Alignment.Center) {
                    Icon(Glyphs.plus, null, tint = c.text, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(Space.s16))
                Text("New folder", style = t.bodyStrong, color = c.text)
            }
        }
        AnimatedVisibility(nf.editing, enter = fadeIn(arrive()), exit = fadeOut(leave())) {
        Box(Modifier.fillMaxSize()) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Row(Modifier.fillMaxSize().padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(Shapes.pill).background(c.surface), contentAlignment = Alignment.Center) {
                Icon(Glyphs.folder, null, tint = c.text, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(Space.s16))
            BasicTextField(
                nf.name, cb.onNewName,
                singleLine = true,
                textStyle = t.bodyStrong.copy(color = c.text),
                cursorBrush = SolidColor(c.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { cb.onSubmitNew() }),
                modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "New folder name" },
            )
            // Label and switch are one control, so the switch is read with its name.
            Row(
                Modifier.heightIn(min = Touch.min).toggleable(nf.gitInit, role = Role.Switch) { cb.onToggleGit() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("git init", style = t.label, color = c.textMuted)
                Spacer(Modifier.width(Space.s8))
                RemoterSwitch(nf.gitInit)
            }
        }
        val err = nf.error
        Column(
            Modifier
                .fillMaxWidth()
                // Measured at its own height, not the row's 64 dp, and drawn below the row.
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .offset(y = Touch.row)
                .animateEnterExit(enter = scaleIn(arrive(), initialScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 0f)), exit = ExitTransition.None)
                .padding(horizontal = Space.gutter)
                .shadow(if (c.isDark) 0.dp else 8.dp, Shapes.card)
                .clip(Shapes.card)
                .background(c.surfaceRaised)
                // It floats over the rows below, and in dark mode a shadow doesn't show: without an
                // edge the rows seemed to bleed out from under it, like a layout glitch.
                .then(if (c.isDark) Modifier.border(1.dp, c.line, Shapes.card) else Modifier)
                .padding(start = Space.s16, end = Space.s8, top = Space.s8),
        ) {
            SwapText(
                if (err != null) err.copy(ui.hostname).title else "Letters, numbers, dots, dashes, underscores. No leading dash.",
                t.label,
                animatedTone(if (err != null) c.danger else c.textMuted, "rules"),
            )
            Row(Modifier.align(Alignment.End)) {
                QuietButton("Cancel", cb.onCancelNew)
                CreateButton(nf.submitting, cb.onSubmitNew)
            }
        }
        }
        }
    }
}

@Composable
private fun BottomBar(ui: BrowserUi, cb: BrowserCallbacks) {
    Box(Modifier.fillMaxWidth().background(Remoter.colors.bg).windowInsetsPadding(WindowInsets.navigationBars).padding(Space.gutter)) {
        PrimaryButton(
            "Start in ${ui.name}",
            onClick = { ui.blocked?.let(cb.onBlocked) ?: cb.onStartHere() },
        )
    }
}

/**
 * QuietButton's look with a spinner after the fingerprint. The label stays in
 * place, invisible, so the button keeps its width and Cancel doesn't jump.
 */
@Composable
private fun CreateButton(loading: Boolean, onClick: () -> Unit) {
    val c = Remoter.colors
    Box(
        Modifier
            .heightIn(min = Touch.min)
            .clickable(null, pressIndication(Press.Button), role = Role.Button, onClick = onClick)
            .clip(Shapes.pill)
            .semantics { if (loading) stateDescription = "Creating" }
            .padding(horizontal = Space.s16),
        contentAlignment = Alignment.Center,
    ) {
        Text("Create", style = Remoter.type.bodyStrong, color = c.text, modifier = Modifier.alpha(if (loading) 0f else 1f))
        if (loading) {
            val m = Modifier.size(18.dp).testTag("create-spinner")
            if (Remoter.reducedMotion) {
                CircularProgressIndicator({ 0.3f }, m, color = c.text, strokeWidth = 2.dp, trackColor = Color.Transparent)
            } else {
                CircularProgressIndicator(m, color = c.text, strokeWidth = 2.dp, trackColor = Color.Transparent)
            }
        }
    }
}

/** A reload failed with a list already up: keep it, but say it's the old one. */
@Composable
private fun StaleBanner(ui: BrowserUi, cb: BrowserCallbacks) {
    val c = Remoter.colors
    val err = ui.error
    // NotFound pops the screen, so a banner would only flash on the way out.
    val stale = ui.list != null && err != null && err != AppError.NotFound && !ui.loading
    AnimatedVisibility(
        stale,
        enter = expandVertically(tween(Dur.base, easing = EaseOut)) + fadeIn(tween(Dur.base, easing = EaseOut)),
        exit = shrinkVertically(tween(Dur.exit, easing = EaseIn)) + fadeOut(tween(Dur.exit, easing = EaseIn)),
    ) {
        val title = remember(err) { err?.copy(ui.hostname)?.title }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter, vertical = Space.s8)
                .clip(Shapes.card)
                .background(c.surface)
                .padding(start = Space.s16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(StatusTone.Warn)
            Spacer(Modifier.width(Space.s8))
            Column(Modifier.weight(1f).padding(vertical = Space.s8)) {
                Text("Showing the last list", style = Remoter.type.bodyStrong, color = c.text)
                title?.let { Text(it, style = Remoter.type.label, color = c.textMuted) }
            }
            QuietButton("Retry", cb.onRetry)
        }
    }
}

@Composable
private fun Problem(error: AppError, host: String, cb: BrowserCallbacks) {
    ErrorContent(
        error, host,
        ErrorActions(onRetry = cb.onRetry, onOpenWireGuard = cb.onOpenWireGuard, onDismiss = cb.onBack),
        Modifier.verticalScroll(rememberScrollState()).padding(Space.s24),
    )
}

/**
 * The small sheet a blocked Start opens: why, and what to do. [path] feeds the
 * trust command; [onOpenWireGuard] gives Offline a way out.
 */
@Composable
fun BlockedCopy(b: Blocked, host: String, folder: String, path: String? = null, onOpenWireGuard: (() -> Unit)? = null) {
    val (title, body) = when (b) {
        Blocked.Denied -> "Sessions can't start in $folder" to "remoter keeps sessions out of folders like .ssh and .config, so nothing starts there by mistake. Pick a project folder instead."
        Blocked.Home -> "Sessions can't start in ~ itself" to "Pick a folder inside it."
        Blocked.Untrusted -> "$host doesn't trust $folder yet" to "Run this on the laptop once and accept the trust prompt."
        Blocked.Offline -> "$host isn't reachable right now" to "Check WireGuard, or wake the laptop, then try again."
        Blocked.Unsupported -> "$folder has characters remoter won't touch" to "Rename it on the laptop to use it here."
    }
    Text(title, style = Remoter.type.title, color = Remoter.colors.text)
    Spacer(Modifier.height(Space.s8))
    Text(body, style = Remoter.type.body, color = Remoter.colors.textMuted)
    when {
        b == Blocked.Untrusted -> {
            Spacer(Modifier.height(Space.s16))
            CommandBlock(if (path != null) "cd ${shellPath(path)} && claude" else "claude")
        }
        b == Blocked.Offline && onOpenWireGuard != null -> {
            Spacer(Modifier.height(Space.s16))
            PrimaryButton("Turn on WireGuard", onOpenWireGuard)
        }
    }
}

/** `~/Projects/my app` as something a shell takes: ~ must stay outside quotes to expand. */
internal fun shellPath(path: String): String {
    if (path.isEmpty()) return "~"
    val safe = path.all { it.isLetterOrDigit() || it in "/._-" }
    return if (safe) "~/$path" else "~/'" + path.replace("'", "'\\''") + "'"
}
