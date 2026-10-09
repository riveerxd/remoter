package me.river.remoter.feature.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import me.river.remoter.core.design.SheetSpring
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.Press
import me.river.remoter.core.design.SwapText
import me.river.remoter.core.design.AnimatedItems
import me.river.remoter.core.design.Appear
import me.river.remoter.core.design.AppearAnywhere
import me.river.remoter.core.design.FadeSwap
import me.river.remoter.core.design.FadeInPlace
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.layout.layout
import me.river.remoter.core.design.components.ClaudeChip
import me.river.remoter.core.design.components.SearchPill
import me.river.remoter.core.net.StuckReason
import me.river.remoter.feature.session.reasonText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.river.remoter.core.design.Dur
import me.river.remoter.core.design.EaseIn
import me.river.remoter.core.design.EaseOut
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.Mark
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.components.DragHandle
import me.river.remoter.core.design.components.FolderRow
import me.river.remoter.core.design.components.FolderRowModel
import me.river.remoter.core.design.components.Hop
import me.river.remoter.core.design.components.PrimaryButton
import me.river.remoter.core.design.components.ProgressLine
import me.river.remoter.core.design.components.RoundIconButton
import me.river.remoter.core.design.components.RouteMap
import me.river.remoter.core.design.components.introAnchor
import me.river.remoter.core.design.components.RouteNode
import me.river.remoter.core.design.components.SecondaryButton
import me.river.remoter.core.design.components.Skeleton
import me.river.remoter.core.design.components.SkeletonRow
import me.river.remoter.core.design.components.StatusLabel
import me.river.remoter.core.design.components.StatusTone
import me.river.remoter.core.design.rememberAfter
import me.river.remoter.core.design.rememberHaptics
import me.river.remoter.core.design.shake
import me.river.remoter.core.design.sharedContainer
import me.river.remoter.core.design.staggerIn
import me.river.remoter.core.design.tnum
import me.river.remoter.core.net.Link
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.SessionSummary
import me.river.remoter.core.net.displayPath
import me.river.remoter.core.net.trimmedPath
import me.river.remoter.feature.session.clockTime
import me.river.remoter.feature.session.statusWord
import me.river.remoter.feature.session.uptime
import me.river.remoter.feature.session.WorktreeLine
import me.river.remoter.feature.session.spokenDuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

data class HomeCallbacks(
    val onSettings: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onBrowseHome: () -> Unit = {},
    val onFolder: (FolderItem) -> Unit = {},
    val onFolderMenu: (FolderItem, pinned: Boolean) -> Unit = { _, _ -> },
    val onSession: (SessionSummary) -> Unit = {},
    val onClearSession: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenWireGuard: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onMapDetails: () -> Unit = {},
    val onTogglePin: (FolderItem, pinned: Boolean) -> Unit = { _, _ -> },
    val onReorder: () -> Unit = {},
    val onNew: () -> Unit = {},
    val onOpenClaude: (SessionSummary) -> Unit = {},
    val onLoad: () -> Unit = {},
)

private val nodes = persistentListOf(
    RouteNode(Glyphs.phone, "This phone"),
    RouteNode(Glyphs.relay, "Relay"),
    RouteNode(Glyphs.laptop, "Laptop"),
)

private val directNodes = persistentListOf(
    RouteNode(Glyphs.phone, "This phone"),
    RouteNode(Glyphs.laptop, "Laptop"),
)

// [entrance] plays the stagger once, after the splash
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(ui: HomeUi, cb: HomeCallbacks, entrance: Boolean) {
    val c = Remoter.colors
    val sheet = rememberBottomSheetScaffoldState(rememberStandardBottomSheetState(SheetValue.PartiallyExpanded, skipHiddenState = true))
    val scope = rememberCoroutineScope()
    val expanded = sheet.bottomSheetState.targetValue == SheetValue.Expanded
    // back lowers a raised sheet first, only leaves home from the peek
    BackHandler(enabled = expanded) { scope.launch { sheet.bottomSheetState.partialExpand() } }
    val phone = rememberPhoneStats()
    var newPx by remember { mutableIntStateOf(0) }
    // measured unsqueezed so it can't feed back. without it, at large fonts + New rides up over Connected
    var mapNaturalPx by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        // full height, under the sheet too: stopping at the map left a bare band that looked broken
        DotGrid()
        MapGlow(ui.link is Link.Up)
        val newDp = with(LocalDensity.current) { newPx.toDp() } + Space.s16
        val mapNaturalDp = with(LocalDensity.current) { mapNaturalPx.toDp() }
        val mapHeight = (maxHeight - PeekHeight - newDp).coerceAtLeast(mapNaturalDp)
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // a full height sheet slides its handle under the status bar, where it can't be grabbed
        val contentMax = maxHeight - statusTop - Touch.min
        // + New fades as the sheet rises and the header's plus takes over
        val density = LocalDensity.current
        val layoutPx = with(density) { maxHeight.toPx() }
        val peekTopPx = layoutPx - with(density) { PeekHeight.toPx() }
        val fadePx = with(density) { NewFade.toPx() }
        val gapPx = with(density) { Space.s16.toPx() }
        val sheetTop = { runCatching { sheet.bottomSheetState.requireOffset() }.getOrDefault(peekTopPx) }
        val visibility = { ((sheetTop() - (peekTopPx - fadePx)) / fadePx).coerceIn(0f, 1f) }
        val toggle: () -> Unit = {
            scope.launch { if (expanded) sheet.bottomSheetState.partialExpand() else sheet.bottomSheetState.expand() }
        }
        BottomSheetScaffold(
            scaffoldState = sheet,
            sheetPeekHeight = PeekHeight,
            sheetShape = Shapes.sheet,
            sheetContainerColor = c.surfaceRaised,
            sheetShadowElevation = if (c.isDark) 0.dp else 8.dp,
            // Material's handle slot wraps it in a second clickable on the same spot, which the
            // accessibility checks flag and TalkBack reads twice
            sheetDragHandle = null,
            containerColor = Color.Transparent,
            sheetContent = {
                DragHandle(
                    Modifier.align(Alignment.CenterHorizontally).clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "Collapse" else "Expand",
                        onClick = toggle,
                    ),
                )
                Box(Modifier.heightIn(max = contentMax).staggerIn(1, entrance)) {
                    SheetBody(ui, cb, entrance, atPeek = sheet.bottomSheetState.currentValue == SheetValue.PartiallyExpanded && !expanded, plusAlpha = { 1f - visibility() })
                }
            },
        ) {
            Box(Modifier.fillMaxWidth().height(mapHeight).staggerIn(0, entrance)) {
                MapArea(ui, cb, phone) { mapNaturalPx = it }
            }
        }
        if (visibility() > 0f) {
            NewButton(
                cb.onNew,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = Space.gutter)
                    .onSizeChanged { newPx = it.height }
                    .offset { IntOffset(0, (sheetTop() - gapPx - newPx).roundToInt()) }
                    .graphicsLayer { alpha = visibility() }
                    .staggerIn(2, entrance),
            )
        }
    }
}

private val PeekHeight = 440.dp
private val NewFade = 120.dp

@Composable
private fun MapArea(ui: HomeUi, cb: HomeCallbacks, phone: PhoneStats, onNaturalHeight: (Int) -> Unit) {
    val c = Remoter.colors
    val t = Remoter.type
    // monitor starts out Reconnecting; wait 300 ms or every cold start flashes it
    var shown by remember { mutableStateOf<Link?>(ui.link.takeIf { it != Link.Reconnecting }) }
    val reconnectingLong = rememberAfter(ui.link == Link.Reconnecting, 300)
    LaunchedEffect(ui.link, reconnectingLong) {
        if (ui.link != Link.Reconnecting || reconnectingLong) shown = ui.link
    }
    var topPx by remember { mutableIntStateOf(0) }
    var middlePx by remember { mutableIntStateOf(0) }
    LaunchedEffect(topPx, middlePx) { onNaturalHeight(topPx + middlePx) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).onSizeChanged { topPx = it.height }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s8), verticalAlignment = Alignment.CenterVertically) {
                Text(ui.hostname, style = t.title, color = c.text, modifier = Modifier.weight(1f))
                RoundIconButton(Glyphs.settings, "Settings", cb.onSettings)
            }
            FadeInPlace(ui.refreshing && rememberAfter(ui.refreshing, 1_000)) { ProgressLine() }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            // unbounded so it measures what it needs, not what it's given
            Column(Modifier.fillMaxWidth().wrapContentHeight(unbounded = true).onSizeChanged { middlePx = it.height }) {
                // gating on the folder load left first runs with WireGuard off stuck on skeletons
                FadeSwap(!ui.loaded && shown == null) { pending -> Column(Modifier.fillMaxWidth()) {
                if (pending) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Space.s24), horizontalArrangement = Arrangement.SpaceBetween) {
                        repeat(if (ui.direct) 2 else 3) { Skeleton(64.dp, 64.dp, shape = Shapes.pill) }
                    }
                } else {
                    val hops = when (shown) {
                        null -> listOf(Hop.Idle, Hop.Idle)
                        is Link.Up -> listOf(Hop.Live, Hop.Live)
                        Link.Reconnecting -> listOf(Hop.Pulse, Hop.Pulse)
                        Link.VpnOff -> listOf(Hop.Broken, Hop.Idle)
                        is Link.LaptopDown -> listOf(Hop.Live, Hop.Broken)
                    }.let { if (ui.direct) listOf(if (shown is Link.LaptopDown) Hop.Broken else it[0]) else it }.toImmutableList()
                    RouteMap(
                        if (ui.direct) directNodes else nodes, hops,
                        Modifier
                            .padding(horizontal = Space.s24)
                            .clickable(role = Role.Button, onClickLabel = "Connection details", onClick = cb.onMapDetails)
                            // the splash's nodes glide onto this row
                            .introAnchor(),
                        nodeSize = 64.dp, lineWidth = 8.dp,
                    )
                    Spacer(Modifier.height(Space.s8))
                    NodeStats(shown, ui, phone, Modifier.padding(horizontal = Space.s16))
                }
                } }
                Spacer(Modifier.height(Space.s16))
                // stale numbers would look current, so they go with the link. no room at big fonts,
                // the details sheet has them then
                val load = ui.resources.takeIf { shown is Link.Up && !isLargeFont() }
                // the floor keeps the map still between states. with the load card it would only
                // push + New onto the card
                Box(Modifier.fillMaxWidth().heightIn(min = if (load != null) 0.dp else 96.dp).padding(horizontal = Space.gutter)) {
                    Crossfade(shown, animationSpec = tween(Dur.base, easing = EaseOut), label = "status") { l -> if (l != null) StatusLine(l, ui, cb) }
                }
                Appear(load != null) {
                    load?.let { LoadCard(it, cb.onLoad, Modifier.padding(horizontal = Space.gutter)) }
                }
            }
        }
    }
}

// edge columns hug the screen edges, the middle one centres on the relay, where the nodes sit
@Composable
private fun NodeStats(l: Link?, ui: HomeUi, phone: PhoneStats, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    val tunnel: Pair<String, Color>? = when (l) {
        is Link.Up -> "${l.latencyMs} ms round trip" to c.textMuted
        Link.VpnOff -> "Tunnel off" to c.danger
        Link.Reconnecting -> "Handshaking" to c.warn
        is Link.LaptopDown -> "Tunnel on" to c.textMuted
        null -> null
    }
    val laptop: List<Pair<String, Color>> = when (l) {
        is Link.Up -> listOfNotNull(
            ui.battery?.let { b ->
                val onBattery = ui.onAc == false
                // sessions die when the laptop sleeps
                (if (onBattery) "On battery · $b%" else "Plugged in · $b%") to (if (onBattery && b < 20) c.warn else c.textMuted)
            },
            (if (ui.account?.locked == true) "Locked" to c.warn else null),
            ui.visibleSessions().count { it.isAlive() }
                .takeIf { it > 0 }?.let { (if (it == 1) "1 session" else "$it sessions") to c.textMuted },
        )
        is Link.LaptopDown -> listOf("Offline" to c.danger)
        else -> emptyList()
    }
    // at big fonts the facts push the status under the banners. the details sheet has them too
    val large = isLargeFont()
    val phoneFacts = if (large) emptyList() else listOfNotNull(phone.line()?.let { it to c.textMuted })
    Box(modifier.fillMaxWidth()) {
        NodeLabel("This phone", phoneFacts, Alignment.Start, Modifier.align(Alignment.TopStart))
        // with no relay the tunnel's facts sit under the middle of the one line
        NodeLabel(if (ui.direct) "Direct" else "Relay", if (large) emptyList() else listOfNotNull(tunnel), Alignment.CenterHorizontally, Modifier.align(Alignment.TopCenter))
        NodeLabel(ui.hostname, if (large) emptyList() else laptop, Alignment.End, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun NodeLabel(name: String, facts: List<Pair<String, Color>>, align: Alignment.Horizontal, modifier: Modifier) {
    val t = Remoter.type
    val textAlign = when (align) {
        Alignment.Start -> TextAlign.Start
        Alignment.End -> TextAlign.End
        else -> TextAlign.Center
    }
    Column(modifier.widthIn(max = 116.dp), horizontalAlignment = align) {
        Text(name, style = t.label.copy(fontWeight = FontWeight.SemiBold), color = Remoter.colors.text, textAlign = textAlign)
        facts.forEach { (f, col) -> Text(f, style = t.label.tnum(), color = col, textAlign = textAlign) }
    }
}

@Composable
private fun StatusLine(l: Link, ui: HomeUi, cb: HomeCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        when (l) {
            is Link.Up -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusLabel("Connected", StatusTone.Live)
                    DetailsLink(cb.onMapDetails)
                }
            }
            Link.Reconnecting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusLabel("Reconnecting…", StatusTone.Warn, pulsing = true)
                    DetailsLink(cb.onMapDetails)
                }
            }
            Link.VpnOff -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusLabel("WireGuard is off", StatusTone.Danger)
                    DetailsLink(cb.onMapDetails)
                }
                SecondaryButton("Turn on WireGuard", cb.onOpenWireGuard, Modifier.widthIn(min = 200.dp).fillMaxWidth(0.7f))
            }
            is Link.LaptopDown -> {
                val seen = (l.lastSeenMs ?: ui.lastSeenMs)?.let { " · last seen ${clockTime(it)}" } ?: ""
                val still = ui.stillDown > 0 && !ui.retrying
                SwapText(
                    when {
                        still -> "Still no answer from ${ui.hostname}$seen"
                        ui.direct -> "${ui.hostname} is asleep or away from home$seen"
                        else -> "${ui.hostname} is asleep or offline$seen"
                    },
                    t.label.tnum(), c.text, textAlign = TextAlign.Center,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // the label stays under the spinner for TalkBack
                    SecondaryButton(if (ui.retrying) "Checking…" else "Retry", cb.onRetry, Modifier.width(160.dp).shake(ui.stillDown), loading = ui.retrying)
                    DetailsLink(cb.onMapDetails)
                }
            }
        }
    }
}

// a tappable map alone looked like decoration
@Composable
private fun DetailsLink(onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = Touch.min)
            .clip(Shapes.pill)
            .clickable(role = Role.Button, onClickLabel = "Connection details", onClick = onClick)
            .padding(start = Space.s16, end = Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Details", style = Remoter.type.label, color = Remoter.colors.textMuted)
        Icon(Glyphs.chevron, contentDescription = null, tint = Remoter.colors.textMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MapGlow(live: Boolean) {
    val alpha by animateFloatAsState(if (live) 1f else 0f, tween(Dur.screen, easing = EaseOut), label = "glow")
    if (alpha == 0f) return
    val volt = Remoter.colors.volt
    val strength = if (Remoter.colors.isDark) 0.10f else 0.16f
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height * 0.24f)
        drawRect(
            Brush.radialGradient(
                listOf(volt.copy(alpha = strength * alpha), Color.Transparent),
                center = center,
                radius = size.width * 0.75f,
            ),
        )
    }
}

@Composable
private fun DotGrid() {
    val dot = Remoter.colors.line
    Canvas(Modifier.fillMaxSize()) {
        val step = 24.dp.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                drawCircle(dot, 1.2.dp.toPx(), Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

private fun HomeUi.visibleSessions() = sessions.filter { it.id !in hidden }

// still has a window on the laptop
private fun SessionSummary.isAlive() = state != SessionState.Exited && state != SessionState.Gone

@Composable
private fun NewButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    Row(
        modifier
            .heightIn(min = Touch.primaryButton)
            .clickable(null, pressIndication(Press.Button), role = Role.Button, onClick = onClick)
            // light mode: the map is white too, only the shadow lifts it off
            .shadow(if (c.isDark) 0.dp else 10.dp, Shapes.pill)
            .clip(Shapes.pill)
            .background(c.cta)
            .semantics(mergeDescendants = true) { contentDescription = "New session" }
            .padding(start = Space.s16, end = Space.s24),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyphs.plus, contentDescription = null, tint = c.onCta, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Space.s8))
        Text("New", style = Remoter.type.bodyStrong, color = c.onCta)
    }
}

// exited ones swipe away sideways, the sheet already owns vertical drags
@Composable
private fun SessionCard(s: SessionSummary, ui: HomeUi, cb: HomeCallbacks, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    val t = Remoter.type
    val ending = s.id in ui.ending
    val (word, tone) = s.statusWord(ending)
    val exited = s.state == SessionState.Exited
    // the wire has no exit time, only the start
    val spoken = if (exited) {
        "Session ${s.name}, ${word.lowercase()}, started at ${clockTime(s.started)}"
    } else {
        "Session ${s.name}, ${word.lowercase()}, running ${spokenDuration(ui.nowMs - s.started)}"
    } + (s.worktree?.let { ", in worktree $it" } ?: "")
    val drag = remember(s.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val threshold = with(LocalDensity.current) { ClearThreshold.toPx() }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s4)
            .graphicsLayer {
                translationX = drag.value
                alpha = 1f - (abs(drag.value) / (threshold * 2)).coerceIn(0f, 0.9f)
            }
            .sharedContainer("session-${s.id}")
            .clickable(null, pressIndication(Press.Card), role = Role.Button, onClick = { cb.onSession(s) })
            .clip(Shapes.card)
            .background(c.surface)
            .then(
                if (exited) {
                    Modifier.pointerInput(s.id) {
                        var past = false
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (abs(drag.value) > threshold) {
                                        drag.animateTo(sign(drag.value) * size.width, tween(Dur.exit, easing = EaseIn))
                                        cb.onClearSession(s.id)
                                    } else {
                                        drag.animateTo(0f, SheetSpring)
                                    }
                                }
                                past = false
                            },
                            onDragCancel = { scope.launch { drag.animateTo(0f, SheetSpring) } },
                        ) { change, d ->
                            change.consume()
                            scope.launch { drag.snapTo(drag.value + d) }
                            val now = abs(drag.value + d) > threshold
                            if (now && !past) haptics.threshold()
                            past = now
                        }
                    }
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = spoken
                if (exited) customActions = listOf(CustomAccessibilityAction("Clear") { cb.onClearSession(s.id); true })
            }
            .animateContentSize(tween(Dur.base, easing = EaseOut))
            .padding(Space.cardPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, style = t.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // crossfade in place so nothing jumps when End lands
                    FadeSwap(ending) { isEnding ->
                        if (isEnding) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // reduced motion freezes the loop, so a steady partial ring
                                if (Remoter.reducedMotion) {
                                    CircularProgressIndicator({ 0.3f }, Modifier.size(14.dp), color = c.textMuted, strokeWidth = 2.dp, trackColor = Color.Transparent)
                                } else {
                                    CircularProgressIndicator(Modifier.size(14.dp), color = c.textMuted, strokeWidth = 2.dp, trackColor = Color.Transparent)
                                }
                                Spacer(Modifier.width(Space.s8))
                                Text("Ending\u2026", style = t.label, color = c.text)
                            }
                        } else {
                            StatusLabel(word, tone, pulsing = s.state == SessionState.Starting)
                        }
                    }
                    Text(
                        " · " + if (exited) "started ${clockTime(s.started)}" else uptime(ui.nowMs - s.started),
                        style = t.label.tnum(), color = c.textMuted, maxLines = 1,
                    )
                }
                Text(trimmedPath(s.path), style = t.label, color = c.textMuted, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                Appear(s.worktree != null) { s.worktree?.let { WorktreeLine(it) } }
            }
            val claude = s.claude
            when {
                ending -> {}
                exited -> Box(
                    // swiping clears it too, but nobody can see a gesture
                    Modifier
                        .size(Touch.min)
                        .offset(x = Space.s8)
                        .clickable(null, pressIndication(Press.Icon), role = Role.Button, onClick = { cb.onClearSession(s.id) })
                        .clip(Shapes.pill),
                    contentAlignment = Alignment.Center,
                ) { Icon(Glyphs.close, contentDescription = "Clear ${s.name}", tint = c.textMuted, modifier = Modifier.size(20.dp)) }
                s.state == SessionState.Ready && claude != null -> {
                    Spacer(Modifier.width(Space.s8))
                    ClaudeChip("Open", { cb.onOpenClaude(s) }, Modifier.semantics { contentDescription = "Open ${s.name} in Claude" })
                }
            }
        }
        if (s.state == SessionState.Stuck && !ending) {
            Text(
                reasonText(s.reason),
                style = t.body, color = if (s.reason == StuckReason.Timeout) c.warn else c.danger,
                modifier = Modifier.padding(top = Space.s8),
            )
        }
    }
}

private val ClearThreshold = 96.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetBody(ui: HomeUi, cb: HomeCallbacks, entrance: Boolean, atPeek: Boolean, plusAlpha: () -> Float) {
    val c = Remoter.colors
    val t = Remoter.type
    val haptics = rememberHaptics()
    val pull = rememberPullToRefreshState()
    LaunchedEffect(pull) {
        var past = false
        snapshotFlow { pull.distanceFraction }.collect { f ->
            if (f >= 1f && !past) haptics.threshold()
            past = f >= 1f
        }
    }
    val list = ui.visibleSessions()
    // peek only: on a raised sheet the same drag has to bring the sheet down, and a refresh
    // gesture on top of it once made an expanded sheet impossible to close
    Box(
        Modifier.fillMaxSize().pullToRefresh(
            isRefreshing = ui.refreshing,
            state = pull,
            enabled = atPeek,
            onRefresh = cb.onRefresh,
        ),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.s8).staggerIn(2, entrance), verticalAlignment = Alignment.CenterVertically) {
                Text("Sessions", style = t.headline, color = c.text)
                val alive = list.count { it.isAlive() }
                AppearAnywhere(alive > 0) {
                    Text(
                        "$alive",
                        style = t.label.tnum(),
                        color = c.text,
                        modifier = Modifier
                            .padding(start = Space.s8)
                            .clip(Shapes.pill)
                            .background(c.surface)
                            .padding(horizontal = Space.s8, vertical = Space.s4)
                            .semantics { contentDescription = if (alive == 1) "1 running" else "$alive running" },
                    )
                }
                Spacer(Modifier.weight(1f))
                // takes over from + New once the sheet hides it
                Box(Modifier.size(Touch.min).graphicsLayer { alpha = plusAlpha() }) {
                    if (!atPeek) RoundIconButton(Glyphs.plus, "New session", cb.onNew)
                }
            }
            Spacer(Modifier.height(Space.s8))
            FadeSwap(list.isEmpty()) { empty ->
                Column(Modifier.fillMaxWidth()) {
                    if (empty && !ui.loaded) {
                        SessionsPending(ui, cb)
                    } else if (empty) {
                        NothingRunning(ui, cb)
                    } else {
                        AnimatedItems(list.mapIndexed { n, it -> n to it }, key = { it.second.id }) { (n, s) ->
                            SessionCard(s, ui, cb, Modifier.staggerIn(3 + n, entrance))
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.s24))
        }
        PullToRefreshDefaults.Indicator(
            state = pull,
            isRefreshing = ui.refreshing,
            modifier = Modifier.align(Alignment.TopCenter),
            containerColor = c.surface,
            color = c.text,
        )
    }
}

@Composable
private fun ColumnScope.NothingRunning(ui: HomeUi, cb: HomeCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    if (ui.loaded && ui.pinned.isEmpty() && ui.recent.isEmpty()) {
        FirstRun(ui, cb)
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s8), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
        Text("Nothing running", style = t.title, color = c.text)
        Text("Start Claude Code on ${ui.hostname} again, or tap New for any folder.", style = t.body, color = c.textMuted)
    }
    val again = (ui.pinned + ui.recent.filter { r -> ui.pinned.none { it.path == r.path } }).take(QuickStarts)
    if (again.isNotEmpty()) {
        SectionLabel("Start again")
        AnimatedItems(again, key = { it.path }) { f -> HomeRow(f, pinned = ui.pinned.any { it.path == f.path }, cb, Modifier, ui) }
    }
}

// before the first answer an empty list means nothing yet, not nothing running
@Composable
private fun SessionsPending(ui: HomeUi, cb: HomeCallbacks) {
    val note = when {
        ui.link == Link.VpnOff -> "Your sessions show up once WireGuard is on."
        ui.link is Link.LaptopDown -> "Your sessions show up once ${ui.hostname} answers."
        ui.loadFailed -> "Couldn't load your sessions from ${ui.hostname}."
        else -> null
    }
    if (note == null) {
        repeat(2) { Skeleton(null, 88.dp, Modifier.padding(horizontal = Space.gutter, vertical = Space.s4), shape = Shapes.card) }
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s8),
        verticalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        Text(note, style = Remoter.type.body, color = Remoter.colors.textMuted)
        // the down states have their action on the map already
        if (ui.link is Link.Up && ui.loadFailed) SecondaryButton("Try again", cb.onRefresh)
    }
    // pins live on the phone, so they're there to start from even before the laptop answers
    if (ui.pinned.isNotEmpty()) {
        SectionLabel("Pinned")
        ui.pinned.forEach { f -> HomeRow(f, pinned = true, cb, Modifier, ui) }
    }
}

private const val QuickStarts = 4

@Composable
internal fun ColumnScope.NewSession(ui: HomeUi, cb: HomeCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    Text("New session", style = t.title, color = c.text)
    Text("Pick a folder on ${ui.hostname}", style = t.label, color = c.textMuted)
    Spacer(Modifier.height(Space.s16))
    SearchPill(cb.onSearch, Modifier.sharedContainer("search"))
    Spacer(Modifier.height(Space.s8))
    Column(Modifier.bleed(Space.gutter)) {
        FolderRow(FolderRowModel("Browse ~", path = "Every folder in your home", isGit = false), onClick = cb.onBrowseHome)
        // pins live on the phone, so they show even when the laptop never answered
        Appear(ui.pinned.isNotEmpty()) {
            SectionLabel("Pinned", if (ui.pinned.size > 1) ({ QuietLink("Reorder", cb.onReorder) }) else null)
        }
        AnimatedItems(ui.pinned, key = { it.path }) { f -> HomeRow(f, pinned = true, cb, Modifier, ui) }
        FadeSwap(ui.loaded) { loaded -> Column(Modifier.fillMaxWidth()) {
            if (loaded) {
                val recent = ui.recent.filter { r -> ui.pinned.none { it.path == r.path } }.take(5)
                Appear(recent.isNotEmpty()) { SectionLabel("Recent") }
                AnimatedItems(recent, key = { it.path }) { f -> HomeRow(f, pinned = false, cb, Modifier, ui) }
                if (ui.pinned.isEmpty() && recent.isEmpty() && ui.suggestions.isNotEmpty()) {
                    SectionLabel("Try one of these")
                    ui.suggestions.forEach { f -> HomeRow(f, pinned = null, cb, Modifier) }
                }
            } else {
                FoldersPending(ui, cb)
            }
        } }
    }
}

// rows reach past the sheet's padding so their text lines up with the title
private fun Modifier.bleed(x: Dp) = layout { m, cons ->
    val px = x.roundToPx()
    val p = m.measure(cons.copy(minWidth = cons.minWidth + 2 * px, maxWidth = cons.maxWidth + 2 * px))
    layout(cons.maxWidth, p.height) { p.place(-px, 0) }
}

@Composable
private fun FoldersPending(ui: HomeUi, cb: HomeCallbacks) {
    val note = when {
        ui.link == Link.VpnOff -> "Your recent folders show up once WireGuard is on."
        ui.link is Link.LaptopDown -> "Your recent folders show up once ${ui.hostname} answers."
        ui.loadFailed -> "Couldn't load your folders from ${ui.hostname}."
        else -> null
    }
    if (note == null) {
        repeat(2) { SkeletonRow() }
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s8),
        verticalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        Text(note, style = Remoter.type.body, color = Remoter.colors.textMuted)
        // the down states have their action on the map already
        if (ui.link is Link.Up && ui.loadFailed) SecondaryButton("Try again", cb.onRefresh)
    }
}

@Composable
private fun SectionLabel(text: String, trailing: (@Composable () -> Unit)? = null) {
    if (trailing == null) {
        Text(text, style = Remoter.type.label, color = Remoter.colors.textMuted, modifier = Modifier.padding(start = Space.gutter, top = Space.s8, bottom = Space.s4))
        return
    }
    // the 48 dp link gives the row its height
    Row(Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.s8), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Remoter.type.label, color = Remoter.colors.textMuted, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun QuietLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.heightIn(min = Touch.min).clip(Shapes.pill).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = Space.s8),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Remoter.type.label, color = Remoter.colors.text) }
}

// pinned == null is a suggestion: no pin, just the chevron
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeRow(f: FolderItem, pinned: Boolean?, cb: HomeCallbacks, modifier: Modifier, ui: HomeUi? = null) {
    val running = ui?.visibleSessions()?.count { it.path == f.path && it.isAlive() } ?: 0
    val ago = f.lastUsedMs?.let { used -> ui?.nowMs?.takeIf { it > 0 }?.let { agoShort(it - used) } }
    FolderRow(
        FolderRowModel(f.name, path = listOfNotNull(displayPath(f.path), ago).joinToString(" · "), isGit = f.isGit, pinned = pinned, running = running),
        onClick = { cb.onFolder(f) },
        onLongClick = { cb.onFolderMenu(f, pinned == true) },
        modifier = modifier,
        // pin on every row like the browser, long press alone was too hidden
        onPin = if (pinned != null) ({ cb.onTogglePin(f, pinned) }) else null,
    )
}

@Composable
private fun FirstRun(ui: HomeUi, cb: HomeCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        Mark(size = 96.dp, chevronColor = c.line)
        Text("Nothing running yet", style = t.title, color = c.text)
        Text(
            "Start Claude Code in a folder on ${ui.hostname}. It shows up here while it runs.",
            style = t.body, color = c.textMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.s8))
        PrimaryButton("Browse ~", cb.onBrowseHome)
    }
    if (ui.suggestions.isNotEmpty()) {
        SectionLabel("Try one of these")
        ui.suggestions.forEach { f -> HomeRow(f, pinned = null, cb, Modifier) }
    }
}

internal fun agoShort(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / (60 * 24)} d ago"
    }
}
