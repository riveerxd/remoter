package me.river.remoter.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import me.river.remoter.core.design.StaggerMaxItems
import me.river.remoter.core.design.SheetSpring
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.Press
import me.river.remoter.core.design.SwapText
import me.river.remoter.core.design.AnimatedItems
import me.river.remoter.core.design.Appear
import me.river.remoter.core.design.FadeSwap
import me.river.remoter.core.design.FadeInPlace
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import me.river.remoter.core.design.components.SearchPill
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
import kotlin.math.roundToInt

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
)

private val nodes = persistentListOf(
    RouteNode(Glyphs.phone, "This phone"),
    RouteNode(Glyphs.relay, "Relay"),
    RouteNode(Glyphs.laptop, "Laptop"),
)

/**
 * Uber style: live map on top, a persistent sheet with search and folders, trip
 * banners floating over the map. [entrance] plays the stagger once, after the splash.
 */
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
    var bannersPx by remember { mutableIntStateOf(0) }
    // Map height when nothing squeezes it. Banners only get the room left above the sheet, or at
    // large fonts they ride up over "Connected". Measured unsqueezed so nothing feeds back.
    var mapNaturalPx by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        // The grid runs the full height, under the sheet too: stopping it halfway down the map
        // left a bare band above the sheet that read as a layout bug.
        DotGrid()
        MapGlow(ui.link is Link.Up)
        // Everything above the sheet's peek, less the banners that ride on the sheet's edge.
        val bannersDp = with(LocalDensity.current) { bannersPx.toDp() }
        val mapNaturalDp = with(LocalDensity.current) { mapNaturalPx.toDp() }
        val mapHeight = (maxHeight - PeekHeight - bannersDp).coerceAtLeast(mapNaturalDp)
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // A full-height sheet would slide its handle under the status bar, where it can't be
        // grabbed. Capping the content stops the sheet with its handle just below it.
        val contentMax = maxHeight - statusTop - Touch.min
        val toggle: () -> Unit = {
            scope.launch { if (expanded) sheet.bottomSheetState.partialExpand() else sheet.bottomSheetState.expand() }
        }
        BottomSheetScaffold(
            scaffoldState = sheet,
            sheetPeekHeight = PeekHeight,
            sheetShape = Shapes.sheet,
            sheetContainerColor = c.surfaceRaised,
            sheetShadowElevation = if (c.isDark) 0.dp else 8.dp,
            sheetDragHandle = {
                DragHandle(
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "Collapse" else "Expand",
                        onClick = toggle,
                    ),
                )
            },
            containerColor = Color.Transparent,
            sheetContent = {
                Box(Modifier.heightIn(max = contentMax).staggerIn(1, entrance)) {
                    SheetBody(ui, cb, entrance, atPeek = sheet.bottomSheetState.currentValue == SheetValue.PartiallyExpanded && !expanded)
                }
            },
        ) {
            Box(Modifier.fillMaxWidth().height(mapHeight).staggerIn(0, entrance)) {
                MapArea(ui, cb, phone) { mapNaturalPx = it }
            }
        }
        // Trip banners ride just above the sheet's top edge, like Uber's trip banner, and fade out
        // as the sheet rises over the map, so they are never drawn on top of it.
        val density = LocalDensity.current
        val layoutPx = with(density) { maxHeight.toPx() }
        val peekTopPx = layoutPx - with(density) { PeekHeight.toPx() }
        val fadePx = with(density) { BannerFade.toPx() }
        val gapPx = with(density) { Space.s8.toPx() }
        val sheetTop = { runCatching { sheet.bottomSheetState.requireOffset() }.getOrDefault(peekTopPx) }
        val visibility = { ((sheetTop() - (peekTopPx - fadePx)) / fadePx).coerceIn(0f, 1f) }
        if (visibility() > 0f) {
            Banners(
                ui, cb,
                roomPx = { peekTopPx - gapPx * 2 - mapNaturalPx },
                Modifier
                    .onSizeChanged { bannersPx = it.height }
                    .offset { IntOffset(0, (sheetTop() - gapPx - bannersPx).roundToInt()) }
                    .graphicsLayer { alpha = visibility() },
            )
        }
    }
}

private val PeekHeight = 360.dp
private val BannerFade = 120.dp

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
            // Unbounded, so its measured height is what it needs, whatever room it's given.
            Column(Modifier.fillMaxWidth().wrapContentHeight(unbounded = true).onSizeChanged { middlePx = it.height }) {
                // Skeletons only until the link has a state. Gating on the folder load left first runs
                // with WireGuard off or the laptop asleep stuck on skeletons forever.
                FadeSwap(!ui.loaded && shown == null) { pending -> Column(Modifier.fillMaxWidth()) {
                if (pending) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Space.s24), horizontalArrangement = Arrangement.SpaceBetween) {
                        repeat(3) { Skeleton(64.dp, 64.dp, shape = Shapes.pill) }
                    }
                } else {
                    val hops = when (shown) {
                        null -> listOf(Hop.Idle, Hop.Idle)
                        is Link.Up -> listOf(Hop.Live, Hop.Live)
                        Link.Reconnecting -> listOf(Hop.Pulse, Hop.Pulse)
                        Link.VpnOff -> listOf(Hop.Broken, Hop.Idle)
                        is Link.LaptopDown -> listOf(Hop.Live, Hop.Broken)
                    }.toImmutableList()
                    RouteMap(
                        nodes, hops,
                        Modifier
                            .padding(horizontal = Space.s24)
                            .clickable(role = Role.Button, onClickLabel = "Connection details", onClick = cb.onMapDetails)
                            // The splash intro's nodes glide onto this row as it hands over to home.
                            .introAnchor(),
                        nodeSize = 64.dp, lineWidth = 8.dp,
                    )
                    Spacer(Modifier.height(Space.s8))
                    NodeStats(shown, ui, phone, Modifier.padding(horizontal = Space.s16))
                }
                } }
                Spacer(Modifier.height(Space.s16))
                // min height so the map doesn't jump on status change. large fonts can still grow it
                Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = Space.gutter)) {
                    Crossfade(shown, animationSpec = tween(Dur.base, easing = EaseOut), label = "status") { l -> if (l != null) StatusLine(l, ui, cb) }
                }
            }
        }
    }
}

/**
 * A name and one or two facts under each node, so every hop of the route says
 * something, not just the laptop. The edge columns hug the screen edges and the
 * middle one centres on the relay, which is where each node actually sits.
 */
@Composable
private fun NodeStats(l: Link?, ui: HomeUi, phone: PhoneStats, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    val relay: Pair<String, Color>? = when (l) {
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
                // Sessions die when the laptop sleeps, so a low battery gets the warning color.
                (if (onBattery) "On battery · $b%" else "Plugged in · $b%") to (if (onBattery && b < 20) c.warn else c.textMuted)
            },
            (if (ui.account?.locked == true) "Locked" to c.warn else null),
            ui.visibleSessions().count { it.isAlive() }
                .takeIf { it > 0 }?.let { (if (it == 1) "1 session" else "$it sessions") to c.textMuted },
        )
        is Link.LaptopDown -> listOf("Offline" to c.danger)
        else -> emptyList()
    }
    // At big font sizes the facts would push the status under the banners. They all live in
    // the connection details sheet too, so here the names alone stay.
    val large = LocalDensity.current.fontScale > 1.3f
    val phoneFacts = if (large) emptyList() else listOfNotNull(phone.line()?.let { it to c.textMuted })
    Box(modifier.fillMaxWidth()) {
        NodeLabel("This phone", phoneFacts, Alignment.Start, Modifier.align(Alignment.TopStart))
        NodeLabel("Relay", if (large) emptyList() else listOfNotNull(relay), Alignment.CenterHorizontally, Modifier.align(Alignment.TopCenter))
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
        Text(name, style = t.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Remoter.colors.text, textAlign = textAlign)
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
                Appear(ui.visibleSessions().isEmpty()) {
                    Text("Sessions you start show up here", style = t.label, color = c.textMuted)
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
                    if (still) "Still no answer from ${ui.hostname}$seen" else "${ui.hostname} is asleep or offline$seen",
                    t.label.tnum(), c.text, textAlign = TextAlign.Center,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The label stays under the spinner for screen readers, which can't see a spinner.
                    SecondaryButton(if (ui.retrying) "Checking…" else "Retry", cb.onRetry, Modifier.width(160.dp).shake(ui.stillDown), loading = ui.retrying)
                    DetailsLink(cb.onMapDetails)
                }
            }
        }
    }
}

/** Says out loud that the map opens connection details; a tappable map alone looked like art. */
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
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (live) 1f else 0f, tween(Dur.screen, easing = EaseOut), label = "glow")
    if (alpha == 0f) return
    val volt = Remoter.colors.volt
    val strength = if (Remoter.colors.isDark) 0.10f else 0.16f
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height * 0.24f)
        drawRect(
            androidx.compose.ui.graphics.Brush.radialGradient(
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

/** Still has a window on the laptop. The laptop node and the folder rows count the same thing. */
private fun SessionSummary.isAlive() = state != SessionState.Exited && state != SessionState.Gone

/**
 * Each banner has its own transition keyed by session id, so a session that
 * goes away collapses out over 200 ms where it was, instead of vanishing.
 */
@Composable
private fun Banners(ui: HomeUi, cb: HomeCallbacks, roomPx: () -> Float, modifier: Modifier) {
    val list = ui.visibleSessions()
    val states = remember { mutableStateMapOf<String, MutableTransitionState<Boolean>>() }
    val last = remember { mutableStateMapOf<String, SessionSummary>() }
    // What is there when home appears is simply there; a session that starts later opens in.
    val firstFrame = remember { booleanArrayOf(true) }
    SideEffect { firstFrame[0] = false }
    list.forEach { s ->
        last[s.id] = s
        states.getOrPut(s.id) { MutableTransitionState(firstFrame[0]) }.targetState = true
    }
    states.forEach { (id, st) -> if (list.none { it.id == id }) st.targetState = false }
    LaunchedEffect(states.values.map { it.isIdle to it.currentState }) {
        states.entries.filter { (_, st) -> st.isIdle && !st.currentState && !st.targetState }.map { it.key }.forEach {
            states.remove(it)
            last.remove(it)
        }
    }
    if (states.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    var bannerPx by remember { mutableIntStateOf(0) }
    var pillPx by remember { mutableIntStateOf(0) }
    val gapPx = with(LocalDensity.current) { Space.s8.toPx() }
    val order = list.map { it.id } + states.keys.filter { id -> list.none { it.id == id } }
    val fit = bannersThatFit(list.size, roomPx(), bannerPx.toFloat(), pillPx.toFloat(), gapPx)
    // The fold only limits live banners: one that's leaving stays until it has collapsed out.
    val live = list.map { it.id }
    val shown = if (expanded) order else live.take(fit) + order.filter { it !in live }
    // Banners that "+N more" reveals open in one after another instead of all appearing at once.
    val revealed = remember { mutableStateMapOf<String, Int>() }
    shown.forEachIndexed { n, id ->
        if (id !in revealed && !firstFrame[0] && revealed.isNotEmpty()) {
            states[id]?.let { st -> if (st.currentState && st.isIdle) states[id] = MutableTransitionState(false).apply { targetState = true } }
        }
        revealed[id] = n
    }
    revealed.keys.filter { it !in shown }.forEach { revealed.remove(it) }
    Column(modifier.fillMaxWidth().padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        shown.forEachIndexed { n, id ->
            val st = states[id] ?: return@forEachIndexed
            val s = last[id] ?: return@forEachIndexed
            val delay = minOf(n, StaggerMaxItems) * Dur.stagger
            key(id) {
                AnimatedVisibility(
                    visibleState = st,
                    enter = expandVertically(tween(Dur.base, delay, EaseOut)) + fadeIn(tween(Dur.base, delay, EaseOut)),
                    exit = shrinkVertically(tween(Dur.exit, easing = EaseIn)) + fadeOut(tween(Dur.exit, easing = EaseIn)),
                ) { Banner(s, ui, cb, Modifier.onSizeChanged { if (it.height > 0) bannerPx = it.height }) }
            }
        }
        if (list.size > fit || expanded && list.size > 2) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .heightIn(min = Touch.min)
                    .onSizeChanged { pillPx = it.height }
                    .clickable(null, pressIndication(Press.Button), role = Role.Button) { expanded = !expanded }
                    .shadow(if (Remoter.colors.isDark) 0.dp else 6.dp, Shapes.pill)
                    .clip(Shapes.pill)
                    .background(Remoter.colors.surfaceRaised)
                    .padding(horizontal = Space.s16, vertical = Space.s16),
            ) {
                SwapText(
                    when {
                        expanded -> "Show less"
                        fit == 0 -> if (list.size == 1) "1 session" else "${list.size} sessions"
                        else -> "+${list.size - fit} more"
                    },
                    Remoter.type.label.tnum(),
                    Remoter.colors.text,
                )
            }
        }
    }
}

@Composable
private fun Banner(s: SessionSummary, ui: HomeUi, cb: HomeCallbacks, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    val t = Remoter.type
    val ending = s.id in ui.ending
    val (word, tone) = s.statusWord(ending)
    val exited = s.state == SessionState.Exited
    // There is no exit time on the wire, only the start, so an exited banner says which one it shows.
    val spoken = if (exited) {
        "Session ${s.name}, ${word.lowercase()}, started at ${clockTime(s.started)}"
    } else {
        "Session ${s.name}, ${word.lowercase()}, running ${spokenDuration(ui.nowMs - s.started)}"
    } + (s.worktree?.let { ", in worktree $it" } ?: "")
    val large = LocalDensity.current.fontScale > 1.3f
    // Exited banner follows the finger down and fades. Past the line it slides away and clears,
    // short of it it springs back.
    val drag = remember(s.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val threshold = with(LocalDensity.current) { ClearThreshold.toPx() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Touch.row)
            .graphicsLayer {
                translationY = drag.value
                alpha = 1f - (drag.value / (threshold * 2)).coerceIn(0f, 0.9f)
            }
            .sharedContainer("session-${s.id}")
            .clickable(role = Role.Button, onClick = { cb.onSession(s) })
            .shadow(if (c.isDark) 0.dp else 6.dp, Shapes.card)
            .clip(Shapes.card)
            .background(c.surfaceRaised)
            .animateContentSize(tween(Dur.base, easing = EaseOut))
            .then(
                if (exited) {
                    Modifier.pointerInput(s.id) {
                        var past = false
                        detectVerticalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (drag.value > threshold) {
                                        drag.animateTo(threshold * 3, tween(Dur.exit, easing = EaseIn))
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
                            scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) }
                            val now = drag.value + d > threshold
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
            .padding(start = Space.s16, end = if (exited) Space.s4 else Space.s16, top = Space.s8, bottom = Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status and the ending spinner crossfade in one fixed column, so names line up across
        // banners whatever the status word and nothing jumps when a session starts ending.
        FadeSwap(ending, Modifier.widthIn(min = StatusColumn)) { isEnding ->
            if (isEnding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = c.textMuted, strokeWidth = 1.5.dp, trackColor = Color.Transparent)
                    Spacer(Modifier.width(Space.s8))
                    Text("Ending\u2026", style = t.label, color = c.text)
                }
            } else {
                StatusLabel(word, tone, pulsing = s.state == SessionState.Starting)
            }
        }
        Spacer(Modifier.width(Space.s16))
        Column(Modifier.weight(1f)) {
            Text(s.name, style = t.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // An exited banner has no clock ticking on the right, so its start time takes the path's line
            // and the name keeps its room at large font sizes.
            if (exited) {
                Text("started ${clockTime(s.started)}", style = t.label.tnum(), color = c.textMuted)
            } else if (!large) {
                // At large font sizes the path only fit as "...s/remoter", which said nothing the
                // name didn't. The detail screen has the full path.
                Text(trimmedPath(s.path), style = t.label, color = c.textMuted, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
            Appear(s.worktree != null) { s.worktree?.let { WorktreeLine(it) } }
        }
        if (exited) {
            // Swiping down still clears it, but a gesture nobody can see can't be the only way.
            Box(
                Modifier
                    .size(Touch.min)
                    .clickable(null, pressIndication(Press.Icon), role = Role.Button, onClick = { cb.onClearSession(s.id) })
                    .clip(Shapes.pill),
                contentAlignment = Alignment.Center,
            ) { Icon(Glyphs.close, contentDescription = "Clear ${s.name}", tint = c.textMuted, modifier = Modifier.size(20.dp)) }
        } else {
            Text(uptime(ui.nowMs - s.started), style = t.label.tnum(), color = c.textMuted)
        }
    }
}

private val StatusColumn = 76.dp

private val ClearThreshold = 48.dp

/**
 * How many of [count] banners fit in [roomPx] above the sheet, at most two, leaving room for the
 * "+N" pill when some are left out. Zero means only the pill. Before anything is measured the
 * sizes are 0 and two show, which the first measured frame corrects.
 */
internal fun bannersThatFit(count: Int, roomPx: Float, bannerPx: Float, pillPx: Float, gapPx: Float): Int {
    for (k in minOf(2, count) downTo 1) {
        val pill = if (k < count) gapPx + pillPx else 0f
        if (k * bannerPx + (k - 1) * gapPx + pill <= roomPx) return k
    }
    return 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetBody(ui: HomeUi, cb: HomeCallbacks, entrance: Boolean, atPeek: Boolean) {
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
    // Pull to refresh only works at the peek. Pulling down from there has nowhere else to go,
    // but on a raised sheet the same drag has to bring the sheet down, and a refresh gesture
    // sitting on top of it once made an expanded sheet impossible to close.
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
                Text("Start in…", style = t.headline, color = c.text, modifier = Modifier.weight(1f))
                // First run has its own big Browse button, and two would compete. Its place is kept,
                // invisible and inert, so the header doesn't change shape once folders arrive.
                val firstRun = ui.loaded && ui.pinned.isEmpty() && ui.recent.isEmpty()
                QuietLink("Browse ~", cb.onBrowseHome, Modifier.then(if (firstRun) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier), enabled = !firstRun)
            }
            Spacer(Modifier.height(Space.s16))
            SearchPill(cb.onSearch, Modifier.padding(horizontal = Space.gutter).sharedContainer("search").staggerIn(3, entrance))
            Spacer(Modifier.height(Space.s16))
            if (ui.loaded && ui.pinned.isEmpty() && ui.recent.isEmpty()) {
                FirstRun(ui, cb)
            } else {
                // Pins live on the phone, so they show even when the laptop never answered. Pinning
                // collapses the row out of one list and opens it in the other so the move reads.
                Appear(ui.pinned.isNotEmpty()) {
                    SectionLabel("Pinned", if (ui.pinned.size > 1) ({ QuietLink("Reorder", cb.onReorder) }) else null)
                }
                AnimatedItems(ui.pinned.mapIndexed { n, f -> n to f }, key = { it.second.path }) { (n, f) ->
                    HomeRow(f, pinned = true, cb, Modifier.staggerIn(4 + n, entrance), ui)
                }
                FadeSwap(ui.loaded) { loaded -> Column(Modifier.fillMaxWidth()) {
                    if (loaded) {
                        val recent = ui.recent.filter { r -> ui.pinned.none { it.path == r.path } }.take(5)
                        Appear(recent.isNotEmpty()) { SectionLabel("Recent") }
                        AnimatedItems(recent.mapIndexed { n, f -> n to f }, key = { it.second.path }) { (n, f) ->
                            HomeRow(f, pinned = false, cb, Modifier.staggerIn(4 + ui.pinned.size + n, entrance), ui)
                        }
                    } else {
                        FoldersPending(ui, cb)
                    }
                } }
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

/**
 * The folder list before the first load. Skeletons only while an answer can still come;
 * with the link down or the load failed it says why, so the sheet never spins forever.
 */
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
        // The down states already have their action on the map; only a failed load needs one here.
        if (ui.link is Link.Up && ui.loadFailed) SecondaryButton("Try again", cb.onRefresh)
    }
}

@Composable
private fun SectionLabel(text: String, trailing: (@Composable () -> Unit)? = null) {
    if (trailing == null) {
        Text(text, style = Remoter.type.label, color = Remoter.colors.textMuted, modifier = Modifier.padding(start = Space.gutter, top = Space.s8, bottom = Space.s4))
        return
    }
    // The 48 dp link already gives the row its air, so the label drops its own padding and centers on it.
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

/** [pinned] null is a suggestion: no pin, just the chevron. */
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
        Text("Your folders land here", style = t.title, color = c.text)
        Text(
            "Start a session once and it shows up here for one tap next time.",
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

/** "just now", "12 min ago", "3 h ago", "2 d ago": when a folder last had a session start. */
internal fun agoShort(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / (60 * 24)} d ago"
    }
}
