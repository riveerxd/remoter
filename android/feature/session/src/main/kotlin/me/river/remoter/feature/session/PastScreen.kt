package me.river.remoter.feature.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import me.river.remoter.core.design.FadeInPlace
import me.river.remoter.core.design.FadeSwap
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.ListFadeIn
import me.river.remoter.core.design.ListFadeOut
import me.river.remoter.core.design.ListPlacement
import me.river.remoter.core.design.Press
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.animatedAlpha
import me.river.remoter.core.design.arrive
import me.river.remoter.core.design.components.BackButton
import me.river.remoter.core.design.components.Chip
import me.river.remoter.core.design.components.ClaudeColors
import me.river.remoter.core.design.components.ClaudeCrab
import me.river.remoter.core.design.components.CrabSize
import me.river.remoter.core.design.components.PrimaryButton
import me.river.remoter.core.design.components.ProgressLine
import me.river.remoter.core.design.components.QuietButton
import me.river.remoter.core.design.components.SecondaryButton
import me.river.remoter.core.design.components.Skeleton
import me.river.remoter.core.design.components.SkeletonRow
import me.river.remoter.core.design.components.StatusDot
import me.river.remoter.core.design.components.StatusTone
import me.river.remoter.core.design.leave
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.pressable
import me.river.remoter.core.design.tnum
import me.river.remoter.core.net.PastHit
import me.river.remoter.core.net.PastLine
import me.river.remoter.core.net.PastScope
import me.river.remoter.core.net.Speaker
import me.river.remoter.core.net.displayPath
import me.river.remoter.core.net.folderName
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PastNav(
    val onBack: () -> Unit,
    /** The folder to start in is [PastHit.path], which can be another project. */
    val onResume: (hit: PastHit, handoff: Boolean) -> Unit,
    val onOpenWireGuard: () -> Unit = {},
)

data class PastCallbacks(
    val onBack: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onScope: (PastScope) -> Unit = {},
    val onAsk: () -> Unit = {},
    val onClearAsk: () -> Unit = {},
    val onRetryBrowse: () -> Unit = {},
    val onRetryFind: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onSelect: (PastHit) -> Unit = {},
    val onResume: (PastHit, Boolean) -> Unit = { _, _ -> },
    val onOpenWireGuard: () -> Unit = {},
)

@Composable
fun PastScreen(vm: PastViewModel, nav: PastNav) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    PastContent(
        ui,
        PastCallbacks(
            onBack = nav.onBack,
            onQuery = vm::setQuery,
            onScope = vm::setScope,
            onAsk = vm::ask,
            onClearAsk = vm::clearAsk,
            onRetryBrowse = vm::retryBrowse,
            onRetryFind = vm::retryFind,
            onMore = vm::loadMore,
            onSelect = vm::select,
            onResume = nav.onResume,
            onOpenWireGuard = nav.onOpenWireGuard,
        ),
    )
}

@Composable
fun PastContent(ui: PastUi, cb: PastCallbacks, list: LazyListState = rememberLazyListState()) {
    val c = Remoter.colors
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        Header(ui, cb)
        SearchField(ui, cb)
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter - Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
            Chip("This project", ui.scope == PastScope.Project, { cb.onScope(PastScope.Project) })
            Chip("All projects", ui.scope == PastScope.All, { cb.onScope(PastScope.All) })
        }
        val busy = ui.asking is Asking.Running || (ui.query.isNotBlank() && ui.matches == Matches.Searching) ||
            (ui.query.isBlank() && ui.browse == Browse.Loading)
        Box(Modifier.height(2.dp).fillMaxWidth()) { FadeInPlace(busy) { ProgressLine() } }
        Box(Modifier.weight(1f)) {
            val mode = when {
                ui.asking != null -> "ask"
                ui.query.isNotBlank() -> "find"
                else -> "browse"
            }
            FadeSwap(mode) { m ->
                when (m) {
                    "ask" -> AskView(ui, cb)
                    "find" -> FindView(ui, cb)
                    else -> BrowseView(ui, cb, list)
                }
            }
        }
        val pick = ui.selected
        AnimatedVisibility(
            pick != null,
            enter = slideInVertically(arrive()) { it } + fadeIn(arrive()),
            exit = slideOutVertically(leave()) { it } + fadeOut(leave()),
        ) {
            // keeps the last pick drawn while it slides away
            val shown = remember { mutableHolder<PastHit>() }
            if (pick != null) shown.value = pick
            shown.value?.let { PickBar(it, ui, cb) }
        }
    }
}

private class Holder<T>(var value: T?)
private fun <T> mutableHolder() = Holder<T>(null)

@Composable
private fun Header(ui: PastUi, cb: PastCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    Row(Modifier.fillMaxWidth().padding(start = Space.s8, end = Space.gutter, top = Space.s8), verticalAlignment = Alignment.CenterVertically) {
        BackButton(cb.onBack)
        Spacer(Modifier.width(Space.s8))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            Text("Sessions", style = t.title, color = c.text)
            Text(displayPath(ui.path), style = t.label, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SearchField(ui: PastUi, cb: PastCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s8)
            .heightIn(min = Touch.searchPill)
            .clip(Shapes.pill)
            .background(c.surface)
            .padding(start = Space.s16, end = Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyphs.search, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.s8))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            FadeInPlace(ui.query.isEmpty()) { Text(if (ui.canAsk) "Search, or ask what you did" else "Search your sessions", style = t.body, color = c.textMuted, maxLines = 1) }
            BasicTextField(
                ui.query, cb.onQuery,
                singleLine = true,
                textStyle = t.body.copy(color = c.text),
                cursorBrush = SolidColor(c.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    if (ui.canAsk) cb.onAsk()
                }),
                modifier = Modifier.fillMaxWidth().testTag("past-query").semantics {
                    contentDescription = if (ui.canAsk) "Search sessions or ask a question" else "Search sessions"
                },
            )
        }
        AnimatedVisibility(ui.query.isNotEmpty(), enter = fadeIn(arrive()) + scaleIn(arrive(), initialScale = 0.6f), exit = fadeOut(leave()) + scaleOut(leave(), targetScale = 0.6f)) {
            Box(
                Modifier
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

@Composable
private fun BrowseView(ui: PastUi, cb: PastCallbacks, state: LazyListState) {
    when (val b = ui.browse) {
        Browse.Loading -> Column(Modifier.fillMaxSize().semantics { contentDescription = "Loading sessions" }) { repeat(7) { SkeletonRow() } }
        is Browse.Failed -> ErrorContent(
            b.error, ui.hostname,
            ErrorActions(onRetry = cb.onRetryBrowse, onOpenWireGuard = cb.onOpenWireGuard, onDismiss = cb.onBack),
            Modifier.verticalScroll(rememberScrollState()).padding(Space.s24),
        )
        is Browse.Loaded -> if (b.hits.isEmpty()) {
            Empty(
                if (ui.scope == PastScope.Project) "No sessions here yet" else "No sessions on ${ui.hostname} yet",
                if (ui.scope == PastScope.Project) "Sessions started in ${ui.folder} show up here, from the phone or the laptop." else "Sessions show up here once claude has run in a folder.",
            ) {
                if (ui.scope == PastScope.Project) QuietButton("Look in all projects", { cb.onScope(PastScope.All) })
            }
        } else {
            val groups = remember(b.hits, ui.nowMs, ui.zone) { pastGroups(b.hits, ui.nowMs, ui.zone) }
            NearEnd(state, b.hits.size, cb.onMore)
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("past-list")) {
                groups.forEach { g ->
                    item(key = "g:" + g.label) { DayHeader(g.label, Modifier.animateItem(fadeInSpec = ListFadeIn, placementSpec = ListPlacement, fadeOutSpec = ListFadeOut)) }
                    items(g.hits, key = { "h:" + it.conversation.id }) { h ->
                        HitRow(h, ui, clock(h.conversation.updated, ui), cb, Modifier.animateItem(fadeInSpec = ListFadeIn, placementSpec = ListPlacement, fadeOutSpec = ListFadeOut))
                    }
                }
                when {
                    b.loadingMore -> item(key = "more") { SkeletonRow() }
                    b.moreFailed -> item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(Space.s16), contentAlignment = Alignment.Center) { QuietButton("Couldn't load older ones, retry", cb.onMore) }
                    }
                }
                item { Spacer(Modifier.height(Space.s24)) }
            }
        }
    }
}

// asks for the next page a few rows before the end, so scrolling doesn't stop at a spinner
@Composable
private fun NearEnd(state: LazyListState, count: Int, onMore: () -> Unit) {
    val near by remember(state) {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - NEAR_END
        }
    }
    LaunchedEffect(state, count) {
        snapshotFlow { near }.distinctUntilChanged().filter { it }.collect { onMore() }
    }
}

private const val NEAR_END = 6

@Composable
private fun FindView(ui: PastUi, cb: PastCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    LazyColumn(Modifier.fillMaxSize().testTag("past-find")) {
        if (ui.canAsk) item(key = "ask") { AskRow(ui, cb) }
        when (val m = ui.matches) {
            null, Matches.Searching -> item(key = "searching") {
                Column(Modifier.semantics { contentDescription = "Searching sessions" }) { repeat(3) { SkeletonRow() } }
            }
            is Matches.Failed -> item(key = "failed") {
                Column(Modifier.fillMaxWidth().padding(Space.s24), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                    Text("Couldn't search ${ui.hostname}", style = t.title, color = c.text)
                    Text(m.error.copy(ui.hostname).title, style = t.body, color = c.textMuted)
                    SecondaryButton("Retry", cb.onRetryFind)
                }
            }
            is Matches.Found -> if (m.hits.isEmpty()) {
                item(key = "none") {
                    Column(Modifier.fillMaxWidth().padding(Space.s24), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                        Text("Nothing matches “${ui.query.trim()}”", style = t.title, color = c.text)
                        if (ui.canAsk) Text("Ask Claude instead. It goes by what you remember, not the exact words.", style = t.body, color = c.textMuted)
                        if (ui.scope == PastScope.Project) QuietButton("Look in all projects", { cb.onScope(PastScope.All) })
                    }
                }
            } else {
                item(key = "count") {
                    val n = m.hits.size
                    val label = if (m.more) "$n sessions shown, more mention it" else if (n == 1) "1 session mentions it" else "$n sessions mention it"
                    Text(label, style = t.label, color = c.textMuted, modifier = Modifier.padding(start = Space.gutter, top = Space.s16, bottom = Space.s8))
                }
                items(m.hits, key = { "m:" + it.conversation.id }) { h ->
                    MatchRow(h, ui, cb, Modifier.animateItem(fadeInSpec = ListFadeIn, placementSpec = ListPlacement, fadeOutSpec = ListFadeOut))
                }
            }
        }
        item { Spacer(Modifier.height(Space.s24)) }
    }
}

@Composable
private fun AskRow(ui: PastUi, cb: PastCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s8)
            .pressable(interaction, Press.Card)
            .clip(Shapes.card)
            .background(c.surface)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Ask", onClick = cb.onAsk)
            .heightIn(min = Touch.row)
            .padding(horizontal = Space.s16, vertical = Space.s8)
            .testTag("past-ask"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(Shapes.pill).background(c.bg), contentAlignment = Alignment.Center) {
            Icon(ClaudeCrab, null, tint = ClaudeColors.crab, modifier = Modifier.size(CrabSize * (21f / 27f)))
        }
        Spacer(Modifier.width(Space.s16))
        Column(Modifier.weight(1f)) {
            Text("Ask Claude", style = t.bodyStrong, color = c.text)
            Text(
                if (ui.scope == PastScope.Project) "Reads ${ui.folder}'s sessions, then everything on ${ui.hostname}" else "Reads every session on ${ui.hostname}",
                style = t.label, color = c.textMuted,
            )
        }
        Icon(Glyphs.chevron, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AskView(ui: PastUi, cb: PastCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    val a = ui.asking ?: return
    LazyColumn(Modifier.fillMaxSize().testTag("past-answer")) {
        item(key = "steps") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                a.steps.forEach { StepRow(it) }
            }
        }
        when (a) {
            is Asking.Running -> item(key = "wait") {
                Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                    Skeleton(240.dp, 14.dp)
                    Skeleton(null, 14.dp)
                    Skeleton(180.dp, 14.dp)
                    Spacer(Modifier.height(Space.s8))
                    Text("Takes a few seconds, more when it looks everywhere.", style = t.label, color = c.textMuted)
                }
            }
            is Asking.Failed -> item(key = "failed") {
                Column(Modifier.fillMaxWidth().padding(Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                    Text("Couldn't get an answer", style = t.title, color = c.text)
                    Text("${ui.hostname} didn't finish reading. The keyword matches still work.", style = t.body, color = c.textMuted)
                    SecondaryButton("Retry", cb.onAsk)
                }
            }
            is Asking.Answered -> {
                val f = a.found
                if (f != null) {
                    item(key = "found") { AnswerCard(f.hit, f.answer, f.quote, ui, cb) }
                } else {
                    item(key = "none") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                            Text("Claude didn't find it", style = t.title, color = c.text)
                            Text("Try it in other words, or look through the list.", style = t.body, color = c.textMuted)
                        }
                    }
                }
                if (a.also.isNotEmpty()) {
                    item(key = "also") {
                        Text(if (f != null) "Also came up in" else "Closest by keywords", style = t.label, color = c.textMuted, modifier = Modifier.padding(start = Space.gutter, top = Space.s24, bottom = Space.s4))
                    }
                    items(a.also, key = { "a:" + it.conversation.id }) { h -> HitRow(h, ui, pastWhen(h.conversation.updated, ui.nowMs, ui.zone), cb) }
                }
            }
        }
        if (a !is Asking.Running) {
            item(key = "back") {
                Box(Modifier.fillMaxWidth().padding(Space.s16), contentAlignment = Alignment.Center) { QuietButton("Back to keyword matches", cb.onClearAsk) }
            }
        }
        item { Spacer(Modifier.height(Space.s24)) }
    }
}

@Composable
private fun StepRow(s: AskStep) {
    val c = Remoter.colors
    val t = Remoter.type
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 1.dp).size(22.dp), contentAlignment = Alignment.Center) {
            when (s.state) {
                StepState.Reading -> Box(Modifier.size(22.dp).clip(Shapes.pill).border(2.dp, c.focusRing, Shapes.pill))
                StepState.Missed -> Box(Modifier.size(22.dp).clip(Shapes.pill).background(c.line), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(10.dp).height(2.dp).background(c.textMuted))
                }
                StepState.Found -> Box(Modifier.size(22.dp).clip(Shapes.pill).background(c.volt), contentAlignment = Alignment.Center) {
                    Icon(Glyphs.check, null, tint = c.onVolt, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.width(Space.s16))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = t.bodyStrong, color = c.text)
            Text(s.detail, style = t.label, color = c.textMuted)
        }
    }
}

@Composable
private fun AnswerCard(hit: PastHit, answer: String, quote: String?, ui: PastUi, cb: PastCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    val conv = hit.conversation
    val elsewhere = hit.path != ui.path
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter)
            .clip(Shapes.card)
            // raised, so the Hand off pill (surface) still shows on it
            .background(c.surfaceRaised)
            .border(1.dp, c.line, Shapes.card)
            .padding(Space.cardPadding)
            .testTag("past-found"),
        verticalArrangement = Arrangement.spacedBy(Space.s16),
    ) {
        Text(answer, style = t.body, color = c.text)
        if (!quote.isNullOrBlank()) {
            val bar = c.focusRing
            Text(
                "“$quote”",
                style = t.label,
                color = c.textMuted,
                modifier = Modifier
                    .drawBehind { drawRect(bar, size = Size(2.dp.toPx(), size.height)) }
                    .padding(start = Space.s16 - Space.s4),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (elsewhere) {
                    FolderTag(folderName(hit.path))
                    Spacer(Modifier.width(Space.s8))
                }
                Text(listOfNotNull(pastWhen(conv.updated, ui.nowMs, ui.zone), conv.branch).joinToString(" · "), style = t.label.tnum(), color = c.textMuted, maxLines = 1)
            }
            Text(conv.title, style = t.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        ResumeButtons(hit, ui, cb)
        if (elsewhere) Text("Starts in ${displayPath(hit.path)}, the same way a new session does.", style = t.label, color = c.textMuted)
    }
}

@Composable
private fun ResumeButtons(hit: PastHit, ui: PastUi, cb: PastCallbacks) {
    val open = hit.conversation.open
    Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
            // both weighted: the pills fill their width, so an unweighted one takes the whole row
            if (open) {
                PrimaryButton("Hand off", { cb.onResume(hit, true) }, Modifier.weight(1f))
            } else {
                PrimaryButton("Resume", { cb.onResume(hit, false) }, Modifier.weight(1f))
                SecondaryButton("Hand off", { cb.onResume(hit, true) }, Modifier.weight(1f))
            }
        }
        if (open) Text("Open on ${ui.hostname}, so hand off only", style = Remoter.type.label, color = Remoter.colors.textMuted)
    }
}

@Composable
private fun PickBar(hit: PastHit, ui: PastUi, cb: PastCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bg)
            .drawBehind { drawRect(c.line, size = Size(size.width, 1.dp.toPx())) }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = Space.gutter, vertical = Space.s16)
            .testTag("past-pick"),
        verticalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        Text(hit.conversation.title, style = t.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hit.path != ui.path) Text("In ${displayPath(hit.path)}", style = t.label, color = c.textMuted, maxLines = 1)
        ResumeButtons(hit, ui, cb)
    }
}

@Composable
private fun DayHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = Remoter.type.label,
        color = Remoter.colors.textMuted,
        modifier = modifier.fillMaxWidth().padding(start = Space.gutter, top = Space.s16, bottom = Space.s4).semantics { heading() },
    )
}

@Composable
private fun FolderTag(name: String) {
    val c = Remoter.colors
    Text(
        name,
        style = Remoter.type.label,
        color = c.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(Shapes.pill).background(c.bg).border(1.dp, c.line, Shapes.pill).padding(horizontal = Space.s8, vertical = 2.dp),
    )
}

@Composable
private fun HitRow(hit: PastHit, ui: PastUi, time: String, cb: PastCallbacks, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    val t = Remoter.type
    val conv = hit.conversation
    val picked = ui.selected?.conversation?.id == conv.id
    val elsewhere = hit.path != ui.path
    val sub = hit.lines.firstOrNull()?.text ?: conv.lastPrompt
    val interaction = remember { MutableInteractionSource() }
    // a fill would flip in light mode, where the raised surface is brighter, so it's a bar
    val bar = animatedAlpha(picked, "pick bar")
    val accent = c.focusRing
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind { if (bar > 0f) drawRect(accent.copy(alpha = bar), size = Size(4.dp.toPx(), size.height)) }
            .pressable(interaction, Press.Card)
            .clickable(interaction, indication = null, role = Role.RadioButton) { cb.onSelect(hit) }
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("Session ${conv.title}, $time")
                    if (elsewhere) append(", in ${folderName(hit.path)}")
                    if (conv.open) append(", open on ${ui.hostname} now")
                }
                selected = picked
                stateDescription = if (picked) "Selected" else "Not selected"
            }
            .heightIn(min = Touch.row)
            .padding(horizontal = Space.gutter, vertical = Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conv.open) {
                    StatusDot(StatusTone.Live)
                    Spacer(Modifier.width(Space.s8))
                }
                Text(conv.title, style = t.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (elsewhere) {
                    FolderTag(folderName(hit.path))
                    Spacer(Modifier.width(Space.s8))
                }
                if (sub != null) Text(sub, style = t.label, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(Space.s16))
        Text(time, style = t.label.tnum(), color = c.textMuted, maxLines = 1)
    }
}

@Composable
private fun MatchRow(hit: PastHit, ui: PastUi, cb: PastCallbacks, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    val t = Remoter.type
    val conv = hit.conversation
    val picked = ui.selected?.conversation?.id == conv.id
    val elsewhere = hit.path != ui.path
    val interaction = remember { MutableInteractionSource() }
    val accent = c.focusRing
    val bar = animatedAlpha(picked, "pick bar")
    val terms = ui.terms
    val whenLabel = pastWhen(conv.updated, ui.nowMs, ui.zone)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s4)
            .pressable(interaction, Press.Card)
            .clip(Shapes.card)
            .background(if (picked) c.surface else c.bg)
            .border(1.dp, c.line, Shapes.card)
            .drawBehind { if (bar > 0f) drawRect(accent.copy(alpha = bar), size = Size(4.dp.toPx(), size.height)) }
            .clickable(interaction, indication = null, role = Role.RadioButton) { cb.onSelect(hit) }
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("Session ${conv.title}, $whenLabel")
                    if (elsewhere) append(", in ${folderName(hit.path)}")
                    if (conv.open) append(", open on ${ui.hostname} now")
                    hit.lines.forEach { append(". ${if (it.who == Speaker.Me) "You" else "Claude"}: ${it.text}") }
                }
                selected = picked
                stateDescription = if (picked) "Selected" else "Not selected"
            }
            .padding(horizontal = Space.s16, vertical = Space.s16 - Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s4 + 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (conv.open) {
                StatusDot(StatusTone.Live)
                Spacer(Modifier.width(Space.s8))
            }
            Text(highlight(conv.title, terms), style = t.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.s8))
            Text(whenLabel, style = t.label.tnum(), color = c.textMuted, maxLines = 1)
        }
        if (elsewhere) FolderTag(folderName(hit.path))
        hit.lines.forEach { l -> Text(lineText(l, terms), style = t.label, color = c.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun lineText(l: PastLine, terms: List<String>): AnnotatedString {
    val who = if (l.who == Speaker.Me) "You" else "Claude"
    return buildAnnotatedString {
        append("$who: “")
        append(highlight(l.text, terms))
        append("”")
    }
}

// light volt on white can't carry text, so light mode marks matches with a volt wash instead
@Composable
private fun highlight(text: String, terms: List<String>): AnnotatedString {
    val c = Remoter.colors
    val style = if (c.isDark) SpanStyle(color = c.volt, fontWeight = FontWeight.SemiBold)
    else SpanStyle(color = c.text, fontWeight = FontWeight.SemiBold, background = c.volt.copy(alpha = 0.55f))
    return remember(text, terms, c.isDark) {
        val marks = BooleanArray(text.length)
        val lower = text.lowercase()
        terms.map { it.lowercase() }.filter { it.isNotEmpty() }.forEach { term ->
            var i = lower.indexOf(term)
            while (i >= 0) {
                for (k in i until minOf(i + term.length, marks.size)) marks[k] = true
                i = lower.indexOf(term, i + term.length)
            }
        }
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val on = marks[i]
                var j = i
                while (j < text.length && marks[j] == on) j++
                if (on) pushStyle(style)
                append(text.substring(i, j))
                if (on) pop()
                i = j
            }
        }
    }
}

@Composable
private fun Empty(title: String, body: String, action: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(Space.s24), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        Text(title, style = Remoter.type.title, color = Remoter.colors.text)
        Text(body, style = Remoter.type.body, color = Remoter.colors.textMuted)
        action()
    }
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

// the day is in the group header already
private fun clock(ms: Long, ui: PastUi): String = Instant.ofEpochMilli(ms).atZone(ui.zone).format(hhmm)
