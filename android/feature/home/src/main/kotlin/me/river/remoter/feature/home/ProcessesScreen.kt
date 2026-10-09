package me.river.remoter.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import me.river.remoter.core.design.Press
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.components.Chip
import me.river.remoter.core.design.components.DangerButton
import me.river.remoter.core.design.components.RemoterSheetFor
import me.river.remoter.core.design.components.RemoterSnackbar
import me.river.remoter.core.design.components.SecondaryButton
import me.river.remoter.core.design.components.SkeletonRow
import me.river.remoter.core.design.components.StatusDot
import me.river.remoter.core.design.components.StatusLabel
import me.river.remoter.core.design.components.StatusTone
import me.river.remoter.core.design.components.TopBar
import me.river.remoter.core.design.pressIndication
import me.river.remoter.core.design.tnum
import me.river.remoter.core.net.Proc
import me.river.remoter.core.net.Signal
import me.river.remoter.feature.session.copy
import java.util.Locale

data class ProcsCallbacks(
    val onBack: () -> Unit = {},
    val onSort: (ProcSort) -> Unit = {},
    val onSelect: (Proc?) -> Unit = {},
    val onSignal: (Proc, Signal) -> Unit = { _, _ -> },
    val onNoteShown: () -> Unit = {},
)

@Composable
fun ProcessesScreen(vm: ProcessesViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // polls only while on screen and the app is in front
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.poll() } }
    ProcessesContent(ui, ProcsCallbacks(onBack, vm::sort, vm::select, vm::signal, vm::noteShown))
}

private val CpuCol = 56.dp
private val MemCol = 72.dp

@Composable
fun ProcessesContent(ui: ProcsUi, cb: ProcsCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(cb.onBack, "Processes")
        ui.resources?.let { r -> Gauges(r, Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s8)) }
        Row(Modifier.fillMaxWidth().padding(start = Space.gutter - Space.s4, end = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
            Chip("CPU", ui.sort == ProcSort.Cpu, { cb.onSort(ProcSort.Cpu) })
            Spacer(Modifier.width(Space.s4))
            Chip("Memory", ui.sort == ProcSort.Memory, { cb.onSort(ProcSort.Memory) })
            Spacer(Modifier.weight(1f))
            when {
                ui.error != null -> StatusLabel("Not updating", StatusTone.Warn)
                ui.loaded -> StatusLabel("Live", StatusTone.Live)
            }
        }
        ui.error?.let { Text(it.copy(ui.host).title, style = t.label, color = c.textMuted, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s4)) }
        val large = isLargeFont()
        if (!large) Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s8)) {
            Text("Process", style = t.label, color = c.textMuted, modifier = Modifier.weight(1f))
            Text("CPU", style = t.label, color = c.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(CpuCol))
            Text("Memory", style = t.label, color = c.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(MemCol))
        }
        if (!ui.loaded) {
            repeat(6) { SkeletonRow() }
        } else if (ui.procs.isEmpty()) {
            Text("Nothing listed yet.", style = t.body, color = c.textMuted, modifier = Modifier.padding(Space.gutter))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(ui.procs, key = { "${it.pid}/${it.start}" }) { p ->
                ProcRow(p, large, Modifier.animateItem(), onClick = { cb.onSelect(p) })
            }
            if (ui.truncated) item(key = "more") {
                Text("Only the busiest are listed.", style = t.label, color = c.textMuted, modifier = Modifier.padding(Space.gutter))
            }
        }
    }
    ProcSheet(ui, cb)
    ui.note?.let { n -> key(n) { RemoterSnackbar(n, cb.onNoteShown, autoDismissMs = 4_000) } }
}

internal fun cpuText(p: Double) = String.format(Locale.US, "%.1f%%", p)

@Composable
private fun ProcRow(p: Proc, large: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Remoter.colors
    val t = Remoter.type
    val spoken = buildString {
        append("${p.name}, CPU ${cpuText(p.cpuPct)}, memory ${bytes(p.rss)}")
        p.session?.let { append(", remoter session ${it.name}") }
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Touch.row)
            .clickable(null, pressIndication(Press.Card), role = Role.Button, onClickLabel = "Details", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(horizontal = Space.gutter, vertical = Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Space.s8)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = t.bodyStrong, color = c.text, modifier = Modifier.weight(1f, fill = false))
                p.session?.let { SessionTag(it.name, Modifier.padding(start = Space.s8)) }
            }
            Text(p.cmd, style = t.label, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (large) Text("CPU ${cpuText(p.cpuPct)} · ${bytes(p.rss)}", style = t.label.tnum(), color = c.text)
        }
        if (large) return@Row
        Text(cpuText(p.cpuPct), style = t.label.tnum(), color = if (p.cpuPct >= 50) c.text else c.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(CpuCol))
        Text(bytes(p.rss), style = t.label.tnum(), color = c.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(MemCol))
    }
}

/** Marks what a remoter session runs, so it never gets killed by mistake for some stray claude. */
@Composable
internal fun SessionTag(name: String, modifier: Modifier = Modifier) {
    val c = Remoter.colors
    Row(
        modifier.clip(Shapes.pill).background(c.surface).padding(horizontal = Space.s8, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(StatusTone.Live)
        Spacer(Modifier.width(Space.s4))
        Text(name, style = Remoter.type.label, color = c.text, modifier = Modifier.widthIn(max = 160.dp))
    }
}

@Composable
private fun ProcSheet(ui: ProcsUi, cb: ProcsCallbacks) {
    val c = Remoter.colors
    val t = Remoter.type
    RemoterSheetFor(ui.selected, onDismiss = { cb.onSelect(null) }) { p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, style = t.title, color = c.text, modifier = Modifier.weight(1f, fill = false))
            p.session?.let { SessionTag(it.name, Modifier.padding(start = Space.s8)) }
        }
        Text("pid ${p.pid} · ${p.user} · CPU ${cpuText(p.cpuPct)} · ${bytes(p.rss)}", style = t.label.tnum(), color = c.textMuted)
        Spacer(Modifier.height(Space.s16))
        Box(Modifier.fillMaxWidth().clip(Shapes.technical).background(c.terminal).padding(Space.s16)) {
            Text(p.cmd, style = t.mono, color = c.onTerminal)
        }
        Spacer(Modifier.height(Space.s16))
        if (!p.killable) {
            Text(
                "remoter won't signal this one: it runs as ${p.user}, or remoter itself depends on it.",
                style = t.body, color = c.textMuted,
            )
            return@RemoterSheetFor
        }
        p.session?.let {
            Text("This is part of the session ${it.name}. Ending it from its card closes the window too.", style = t.body, color = c.textMuted)
            Spacer(Modifier.height(Space.s16))
        }
        ui.signalError?.let {
            Text(it.copy(ui.host).title, style = t.body, color = c.danger)
            Spacer(Modifier.height(Space.s8))
        }
        SecondaryButton("${Signal.Term.verb()} (${Signal.Term.unix()})", { cb.onSignal(p, Signal.Term) }, Modifier.fillMaxWidth(), loading = ui.sending == Signal.Term)
        Spacer(Modifier.height(Space.s8))
        DangerButton("${Signal.Kill.verb()} (${Signal.Kill.unix()})", { cb.onSignal(p, Signal.Kill) }, Modifier.fillMaxWidth(), loading = ui.sending == Signal.Kill)
        Spacer(Modifier.height(Space.s8))
        Text("Quit asks it to close. Kill stops it at once, unsaved work and all.", style = t.label, color = c.textMuted)
    }
}
