package me.river.remoter.feature.home

import androidx.compose.foundation.background
import me.river.remoter.core.design.animatedTone
import me.river.remoter.core.design.PressSpring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.river.remoter.core.design.Dur
import me.river.remoter.core.design.EaseOut
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.rememberHaptics
import me.river.remoter.core.design.components.QuietButton
import me.river.remoter.core.design.components.MenuRow
import me.river.remoter.core.design.components.MenuHeader
import me.river.remoter.core.design.components.LocalSheetSlot
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.components.RemoterSheetFor
import me.river.remoter.core.design.components.RemoterSnackbar
import me.river.remoter.core.design.tnum
import me.river.remoter.core.net.Link
import me.river.remoter.core.net.SessionSummary
import me.river.remoter.core.net.displayPath
import me.river.remoter.feature.session.RequestId
import kotlin.math.roundToInt

data class HomeNav(
    val onStart: (FolderItem) -> Unit,
    val onSettings: () -> Unit,
    val onSearch: () -> Unit,
    val onBrowse: (String) -> Unit,
    val onSession: (SessionSummary) -> Unit,
    val onOpenWireGuard: () -> Unit,
    val onOpenClaude: (SessionSummary) -> Unit = {},
)

internal const val UndoMs = 5_000L

private sealed interface HomeSheet {
    data class Menu(val f: FolderItem, val pinned: Boolean) : HomeSheet
    data object Reorder : HomeSheet
    data object Map : HomeSheet
    data object New : HomeSheet
}

@Composable
fun HomeScreen(vm: HomeViewModel, nav: HomeNav, entrance: Boolean) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    val slot = LocalSheetSlot.current
    val clip = LocalClipboardManager.current
    // Leaving for the browser drops the New sheet: coming back, home is where you were.
    fun leave(go: () -> Unit) {
        sheet = null
        go()
    }
    val callbacks = HomeCallbacks(
        onSettings = nav.onSettings,
        onSearch = { leave(nav.onSearch) },
        onBrowseHome = { leave { nav.onBrowse("") } },
        // Start takes the one sheet slot, which drops New without its exit.
        onFolder = { f -> if (slot == null) sheet = null; nav.onStart(f) },
        onFolderMenu = { f, p -> sheet = HomeSheet.Menu(f, p) },
        onSession = nav.onSession,
        onClearSession = vm::clear,
        onRetry = vm::retry,
        onOpenWireGuard = nav.onOpenWireGuard,
        onRefresh = { vm.refresh(manual = true) },
        onMapDetails = { sheet = HomeSheet.Map },
        onTogglePin = { f, pinned -> if (pinned) vm.unpin(f.path) else vm.pin(f.path) },
        onReorder = { sheet = HomeSheet.Reorder },
        onNew = { sheet = HomeSheet.New },
        onOpenClaude = nav.onOpenClaude,
    )
    Box(Modifier.fillMaxSize()) {
        HomeContent(ui, callbacks, entrance)
        ui.undoUnpin?.let { (_, path) ->
            // The host runs the 5 s grace and holds it while a finger is on the snackbar.
            key(path) {
                RemoterSnackbar(
                    "Unpinned ${displayPath(path)}", vm::undoShown,
                    actionLabel = "Undo", onAction = { vm.undoUnpin() },
                    autoDismissMs = UndoMs,
                )
            }
        }
        if (ui.refreshFailed) {
            RemoterSnackbar(
                "Couldn't refresh from ${ui.hostname}", vm::refreshFailShown,
                actionLabel = "Retry", onAction = { vm.refreshFailShown(); vm.refresh(manual = true) },
            )
        }
        copied?.let { path ->
            key(path) { RemoterSnackbar("Copied $path", { copied = null }, autoDismissMs = CopiedMs) }
        }
        RemoterSheetFor(sheet, onDismiss = { sheet = null }) { s ->
            when (s) {
                is HomeSheet.Menu -> {
                    MenuHeader(s.f.name, displayPath(s.f.path))
                    // Start takes the one sheet slot, which drops this menu without its exit so two
                    // sheets never slide past each other. No slot (preview, test): close it here.
                    MenuRow(Glyphs.play, "Start here", { if (slot == null) sheet = null; nav.onStart(s.f) }, divider = false)
                    if (s.pinned) {
                        MenuRow(Glyphs.pin, "Unpin", { sheet = null; vm.unpin(s.f.path) })
                        MenuRow(Glyphs.reorder, "Reorder", { sheet = HomeSheet.Reorder })
                    }
                    MenuRow(Glyphs.copy, "Copy path", {
                        clip.setText(AnnotatedString(displayPath(s.f.path)))
                        copied = displayPath(s.f.path)
                        sheet = null
                    })
                }
                HomeSheet.Reorder -> Reorder(ui, vm::move) { sheet = null }
                HomeSheet.Map -> MapDetails(ui)
                HomeSheet.New -> NewSession(ui, callbacks)
            }
        }
    }
}

/** Long enough to read the path, short enough to be out of the way: the copy itself was instant. */
internal const val CopiedMs = 2_000L

/**
 * Drag a handle and the row lifts and follows the finger; the others slide out of its way
 * as it passes their middle, and it lands where it was let go. TalkBack gets Move up and
 * Move down on each row instead.
 */
@Composable
internal fun ColumnScope.Reorder(ui: HomeUi, move: (Int, Int) -> Unit, onDone: () -> Unit) {
    val c = Remoter.colors
    val haptics = rememberHaptics()
    // Local order so the drop shows at once, not a frame later when the store answers.
    var order by remember(ui.pinned) { mutableStateOf(ui.pinned.toList()) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    var rowPx by remember { mutableFloatStateOf(0f) }
    val from = order.indexOfFirst { it.path == dragging }
    val to = if (from < 0 || rowPx == 0f) from else (from + (dy / rowPx).roundToInt()).coerceIn(0, order.lastIndex)
    fun commit(i: Int, j: Int) {
        if (i == j || i !in order.indices || j !in order.indices) return
        order = order.toMutableList().apply { add(j, removeAt(i)) }
        move(i, j)
    }
    Text("Reorder pinned", style = Remoter.type.title, color = c.text)
    Spacer(Modifier.height(Space.s4))
    order.forEachIndexed { i, f ->
        key(f.path) {
            val lifted = f.path == dragging
            val target = when {
                from < 0 || lifted -> 0f
                i in (from + 1)..to -> -rowPx
                i in to until from -> rowPx
                else -> 0f
            }
            val lift by animateFloatAsState(if (lifted && !Remoter.reducedMotion) 1.02f else 1f, PressSpring, label = "lift")
            val liftShadow by androidx.compose.animation.core.animateDpAsState(if (lifted) 8.dp else 0.dp, tween(Dur.base, easing = EaseOut), label = "lift shadow")
            // Snaps home on drop: the reordered list already puts every row where it lands.
            val shift by animateFloatAsState(
                target,
                if (dragging == null) snap() else tween(Dur.base, easing = EaseOut),
                label = "reorder shift",
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Touch.row)
                    .onSizeChanged { rowPx = it.height.toFloat() }
                    .zIndex(if (lifted) 1f else 0f)
                    // lift on the press spring so the grab reads before the row moves
                    .graphicsLayer {
                        translationY = if (lifted) dy else shift
                        scaleX = lift
                        scaleY = lift
                    }
                    .shadow(liftShadow, Shapes.card)
                    .background(animatedTone(if (lifted) c.surface else c.surfaceRaised, "lift"), Shapes.card)
                    .semantics(mergeDescendants = true) {
                        customActions = buildList {
                            if (i > 0) add(CustomAccessibilityAction("Move up") { commit(i, i - 1); true })
                            if (i < order.lastIndex) add(CustomAccessibilityAction("Move down") { commit(i, i + 1); true })
                        }
                    }
                    .padding(start = Space.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(f.name, style = Remoter.type.bodyStrong, color = c.text, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .size(Touch.min)
                        .semantics { contentDescription = "Drag to reorder ${f.name}" }
                        .pointerInput(f.path, ui.pinned) {
                            // Straight away, no long press: the handle exists only to be dragged.
                            detectDragGestures(
                                onDragStart = { dragging = f.path; dy = 0f; haptics.tick() },
                                onDragEnd = {
                                    val i0 = order.indexOfFirst { it.path == f.path }
                                    val j = if (rowPx == 0f) i0 else (i0 + (dy / rowPx).roundToInt()).coerceIn(0, order.lastIndex)
                                    commit(i0, j)
                                    dragging = null
                                    dy = 0f
                                },
                                onDragCancel = { dragging = null; dy = 0f },
                            ) { ch, d -> ch.consume(); dy += d.y }
                        },
                    contentAlignment = Alignment.Center,
                ) { DragDots(c.textMuted) }
            }
        }
    }
    QuietButton("Done", onDone, Modifier.align(Alignment.CenterHorizontally))
}

/** Two columns of three dots, the usual grip. There's no such glyph in the icon set. */
@Composable
private fun DragDots(color: Color) {
    Canvas(Modifier.size(width = 10.dp, height = 16.dp)) {
        val r = 1.5.dp.toPx()
        for (col in 0..1) for (row in 0..2) {
            drawCircle(color, r, Offset(r + col * (size.width - 2 * r), r + row * (size.height - 2 * r) / 2))
        }
    }
}

@Composable
internal fun MapDetails(ui: HomeUi) {
    val c = Remoter.colors
    val t = Remoter.type
    Text("Connection", style = t.title, color = c.text)
    Column(verticalArrangement = Arrangement.spacedBy(Space.s8), modifier = Modifier.padding(top = Space.s16)) {
        Fact("Laptop key", ui.serverFp?.let(::groupFingerprint) ?: "Not paired")
        Fact("Route", if (ui.direct) "Straight to the laptop" else "Through the relay")
        // Reconnecting can't tell a dropped tunnel from a slow laptop, so it doesn't guess.
        Fact(
            "WireGuard",
            when (ui.link) {
                Link.VpnOff -> "Off"
                Link.Reconnecting -> "Unknown"
                else -> "On"
            },
        )
        Fact(
            "Last probe",
            when (val l = ui.link) {
                is Link.Up -> "${l.latencyMs} ms"
                Link.Reconnecting -> "Retrying"
                Link.VpnOff -> "None, the tunnel is off"
                is Link.LaptopDown -> "No answer"
            },
        )
        ui.lastRequestId?.let { RequestId(it) }
        // the phone's WireGuard app looks the home address up once per tunnel start
        if (ui.direct && ui.link is Link.LaptopDown) {
            Text(
                "If your home address changed, turn the tunnel off and on again.",
                style = t.label, color = c.textMuted, modifier = Modifier.padding(top = Space.s8),
            )
        }
    }
}

@Composable
private fun Fact(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, style = Remoter.type.body, color = Remoter.colors.textMuted, modifier = Modifier.width(120.dp))
        Text(v, style = Remoter.type.body.tnum(), color = Remoter.colors.text)
    }
}

/** First 16 hex characters in four groups of four: `A1F3 09CE 77B2 5D10`. */
fun groupFingerprint(b64: String): String {
    val bytes = me.river.remoter.core.net.B64.decode(b64) ?: return b64
    return me.river.remoter.core.net.B64.hex(bytes).uppercase().take(16).chunked(4).joinToString(" ")
}
