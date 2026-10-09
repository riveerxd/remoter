package me.river.remoter.feature.settings

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import me.river.remoter.core.design.ListPlacement
import me.river.remoter.core.design.ListFadeOut
import me.river.remoter.core.design.ListFadeIn
import androidx.compose.runtime.setValue
import me.river.remoter.core.design.components.RemoterSwitch
import me.river.remoter.core.design.Appear
import me.river.remoter.core.design.FadeSwap
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import me.river.remoter.core.design.components.PrimaryButton
import me.river.remoter.core.design.components.StatusLabel
import me.river.remoter.core.design.components.StatusTone
import me.river.remoter.core.design.components.DangerButton
import me.river.remoter.core.design.components.HoldButton
import me.river.remoter.feature.session.CommandBlock
import me.river.remoter.feature.session.copy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import me.river.remoter.feature.session.pastWhen
import me.river.remoter.core.design.components.TopBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.components.Chip
import me.river.remoter.core.design.components.QuietButton
import me.river.remoter.core.design.components.RoundIconButton
import me.river.remoter.core.design.components.SkeletonRow
import me.river.remoter.core.design.tnum
import me.river.remoter.core.net.Prefs
import me.river.remoter.feature.session.clockTime

data class SettingsCallbacks(
    val onBack: () -> Unit = {},
    val onPrefs: ((Prefs) -> Prefs) -> Unit = {},
    val onAudit: () -> Unit = {},
    val onLock: () -> Unit = {},
    val onUnpair: () -> Unit = {},
)

/** [nowMs] and [zone] are only parameters so screenshots don't change with the calendar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    ui: SettingsUi,
    cb: SettingsCallbacks,
    nowMs: Long = System.currentTimeMillis(),
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    val c = Remoter.colors
    val t = Remoter.type
    val laptop = ui.state.laptop
    val host = laptop?.hostname ?: "the laptop"
    val p = ui.state.prefs
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
    TopBar(cb.onBack, "Settings")
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter),
        verticalArrangement = Arrangement.spacedBy(Space.section),
    ) {
        Section("Laptop") {
            Fact("Name", host, divider = false)
            Fact("Key", laptop?.serverFp?.let(::groupFp) ?: "Not paired")
            laptop?.let { Fact("Paired", pastWhen(it.pairedAtMs, nowMs, zone)) }
        }
        Section("This phone") {
            Fact("Signing key", keyLevelLabel(laptop?.sigLevel), divider = false)
            Fact("Connection key", keyLevelLabel(laptop?.tlsLevel))
            Fact("Last checked", laptop?.lastAttestMs?.let { pastWhen(it, nowMs, zone) } ?: "Not yet")
        }
        Section("Look") {
            ThemePicker(p.theme) { v -> cb.onPrefs { it.copy(theme = v) } }
        }
        Section("App") {
            Toggle("Show hidden folders", p.showHidden) { v -> cb.onPrefs { it.copy(showHidden = v) } }
            Toggle("Haptics", p.haptics) { v -> cb.onPrefs { it.copy(haptics = v) } }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = Touch.row).clickable(role = Role.Button, onClick = cb.onAudit).clip(Shapes.card).background(c.surface).padding(Space.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Audit log", style = t.bodyStrong, color = c.text, modifier = Modifier.weight(1f))
            androidx.compose.material3.Icon(Glyphs.chevron, null, tint = c.textMuted)
        }
        LostPhone(ui, host, cb.onLock)
        // Unpair sits last and on its own: nothing else on the screen is near it.
        DangerZone(ui, host, cb.onUnpair)
        Spacer(Modifier.height(Space.s24))
    }
    }
}

/**
 * Remote lock is the lost-phone switch, not a destructive action: nothing is
 * deleted. It is a pain to undo though (sudo at the laptop), so it takes a hold.
 */
@Composable
private fun LostPhone(ui: SettingsUi, host: String, onLock: () -> Unit) {
    val c = Remoter.colors
    val t = Remoter.type
    Section("If this phone is lost") {
        FadeSwap(ui.locked) { locked ->
            Column {
                if (locked) {
                    StatusLabel("$host is locked", StatusTone.Warn)
                    Text("It refuses everything from this phone. Unlock it at the laptop:", style = t.body, color = c.textMuted)
                    CommandBlock("sudo remoterctl lock off")
                } else {
                    Text("Lock $host", style = t.bodyStrong, color = c.text)
                    Text("It stops taking anything from this phone until you unlock it at the laptop. Running sessions keep going.", style = t.body, color = c.textMuted)
                    Spacer(Modifier.height(Space.s8))
                    HoldButton("Hold to lock", "Keep holding", onLock, loading = ui.busy)
                    ErrorLine(ui.lockError, host)
                }
            }
        }
    }
}

/**
 * Same surface as the other cards, just a red border and its own heading.
 * No "Unpair" title on top, the button already says it.
 */
@Composable
private fun DangerZone(ui: SettingsUi, host: String, onUnpair: () -> Unit) {
    val c = Remoter.colors
    val t = Remoter.type
    Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        Text("Danger zone", style = t.title, color = c.text, modifier = Modifier.semantics { heading() })
        Column(
            Modifier.fillMaxWidth().clip(Shapes.card).background(c.surface).border(1.dp, c.danger.copy(alpha = 0.4f), Shapes.card).padding(Space.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Space.s8),
        ) {
            Text(
                "$host forgets this phone and the keys on it are deleted. To use remoter again you pair from scratch, with sudo at the laptop.",
                style = t.body,
                color = c.textMuted,
            )
            Spacer(Modifier.height(Space.s8))
            DangerButton("Unpair this phone", onUnpair, loading = ui.unpairing)
            Text("Your fingerprint confirms it.", style = t.label, color = c.textMuted)
            ErrorLine(ui.unpairError, host)
        }
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ErrorLine(error: me.river.remoter.core.net.AppError?, host: String) {
    var last by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(error) }
    if (error != null) last = error
    Appear(error != null) {
        last?.let { Text(it.copy(host).title, style = Remoter.type.label, color = Remoter.colors.danger) }
    }
}

fun groupFp(b64: String): String {
    val bytes = me.river.remoter.core.net.B64.decode(b64) ?: return b64
    return me.river.remoter.core.net.B64.hex(bytes).uppercase().take(16).chunked(4).joinToString(" ")
}

/**
 * The heading sits above its card, the way the start sheet's sections do. Inside the card it read
 * as one more row, and every card looked like every other.
 */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        Text(title, style = Remoter.type.label, color = Remoter.colors.textMuted, modifier = Modifier.padding(start = Space.s4).semantics { heading() })
        Column(Modifier.fillMaxWidth().clip(Shapes.card).background(Remoter.colors.surface).padding(horizontal = Space.cardPadding, vertical = Space.s8)) {
            content()
        }
    }
}

@Composable
private fun Fact(k: String, v: String, divider: Boolean = true) {
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Remoter.colors.line))
    Row(Modifier.fillMaxWidth().heightIn(min = Touch.min).padding(vertical = Space.s8), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = Remoter.type.body, color = Remoter.colors.textMuted, modifier = Modifier.width(140.dp))
        Text(v, style = Remoter.type.body.tnum(), color = Remoter.colors.text)
    }
}

/**
 * System, Light or Dark as one segmented control. The fill slides to the pick under a spring, the
 * same way a chosen mode card settles, and the colors of the whole app blend after it.
 */
@Composable
private fun ThemePicker(current: me.river.remoter.core.net.ThemePref, set: (me.river.remoter.core.net.ThemePref) -> Unit) {
    val c = Remoter.colors
    val options = me.river.remoter.core.net.ThemePref.entries
    val index = options.indexOf(current)
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxWidth().padding(vertical = Space.s8).heightIn(min = Touch.min).clip(Shapes.pill).background(c.bg),
    ) {
        val segment = maxWidth / options.size
        val x by androidx.compose.animation.core.animateDpAsState(segment * index, androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 520f), label = "theme pick")
        Box(Modifier.offset(x = x).width(segment).height(Touch.min).padding(Space.s4).clip(Shapes.pill).background(c.cta))
        Row(Modifier.fillMaxWidth()) {
            options.forEach { o ->
                val picked = o == current
                val fg by androidx.compose.animation.animateColorAsState(if (picked) c.onCta else c.text, androidx.compose.animation.core.tween(me.river.remoter.core.design.Dur.base), label = "theme label")
                Box(
                    Modifier
                        .weight(1f)
                        .height(Touch.min)
                        .clip(Shapes.pill)
                        .selectable(picked, role = Role.RadioButton) { set(o) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (o) {
                            me.river.remoter.core.net.ThemePref.System -> "System"
                            me.river.remoter.core.net.ThemePref.Light -> "Light"
                            me.river.remoter.core.net.ThemePref.Dark -> "Dark"
                        },
                        style = Remoter.type.bodyStrong,
                        color = fg,
                    )
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    val c = Remoter.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Touch.min).toggleable(on, role = Role.Switch, onValueChange = set),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Remoter.type.body, color = c.text, modifier = Modifier.weight(1f))
        // The off thumb and border in textMuted: Material's grey thumb on a white track was under the
        // 3:1 a control needs to be seen in light mode.
        RemoterSwitch(on)
    }
}

@Composable
fun AuditContent(ui: AuditUi, onBack: () -> Unit, onMore: () -> Unit, host: String = "the laptop") {
    val c = Remoter.colors
    val t = Remoter.type
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(onBack, "Audit log")
        if (ui.error != null && ui.days.isEmpty()) {
            val copy = ui.error.copy(host)
            Column(
                Modifier.fillMaxSize().padding(Space.s32),
                verticalArrangement = Arrangement.spacedBy(Space.s16, Alignment.CenterVertically),
            ) {
                Text("Couldn't load the log", style = t.title, color = c.text)
                Text(copy.title, style = t.body, color = c.textMuted)
                copy.body?.let { Text(it, style = t.body, color = c.textMuted) }
                copy.command?.let { CommandBlock(it) }
                PrimaryButton("Retry", onMore)
            }
            return
        }
        if (!ui.loading && ui.days.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(Space.s32), contentAlignment = Alignment.Center) {
                Text("Nothing yet. Everything this phone does on the laptop gets logged here.", style = t.body, color = c.textMuted)
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize()) {
            ui.days.forEach { day ->
                item(key = "d" + day.label) {
                    Text(day.label, style = t.label, color = c.textMuted, modifier = Modifier.animateItem(fadeInSpec = ListFadeIn, placementSpec = ListPlacement, fadeOutSpec = ListFadeOut).padding(start = Space.gutter, top = Space.s16, bottom = Space.s4))
                }
                // Not keyed by request id alone: pairing, lock and unlock have no request behind
                // them and all log "-", and a repeated key crashes the list.
                itemsIndexed(day.entries, key = { i, e -> "${e.ts}/${e.action}/${e.requestId}/$i" }) { _, e ->
                    Row(Modifier.animateItem(fadeInSpec = ListFadeIn, placementSpec = ListPlacement, fadeOutSpec = ListFadeOut).fillMaxWidth().heightIn(min = Touch.row).padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
                        Text(clockTime(e.ts), style = t.label.tnum(), color = c.textMuted, modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f).padding(end = Space.s8)) {
                            Text(auditAction(e.action, e.result), style = t.bodyStrong, color = c.text)
                            e.path?.let { Text(auditPath(e.action, it), style = t.label, color = c.textMuted) }
                        }
                        val (word, tone) = auditResult(e.action, e.result)
                        StatusLabel(word, tone)
                    }
                }
            }
            // A failed page swaps the skeleton for Retry: the skeleton's effect only
            // fires once, so leaving it up would spin forever.
            if (!ui.end && ui.error != null) item(key = "retry") {
                Row(Modifier.animateItem(fadeInSpec = ListFadeIn, placementSpec = ListPlacement, fadeOutSpec = ListFadeOut).fillMaxWidth().heightIn(min = Touch.row).padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
                    Text("Couldn't load more", style = t.body, color = c.textMuted, modifier = Modifier.weight(1f))
                    QuietButton("Retry", onMore)
                }
            } else if (!ui.end) item(key = "more") {
                LaunchedEffect(Unit) { onMore() }
                Box(Modifier.animateItem(fadeInSpec = ListFadeIn, placementSpec = ListPlacement, fadeOutSpec = ListFadeOut)) { SkeletonRow() }
            }
        }
    }
}


/** The stored value is the enum name ("Tee"), which nobody reads that way. Words first, hardware name after. */
internal fun keyLevelLabel(level: String?): String = when (level?.lowercase()) {
    null -> "None"
    "tee" -> "Hardware (TEE)"
    "strongbox" -> "Security chip (StrongBox)"
    "software" -> "Software"
    else -> level
}
