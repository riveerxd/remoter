package me.river.remoter.feature.onboarding

import android.Manifest
import me.river.remoter.core.design.animatedTone
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.Canvas
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import me.river.remoter.core.design.openWireGuard
import me.river.remoter.core.design.Glyphs
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.river.remoter.core.design.Dur
import me.river.remoter.core.design.EaseIn
import me.river.remoter.core.design.EaseOut
import me.river.remoter.core.design.Mark
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.net.Weakness
import androidx.compose.foundation.border
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.components.PrimaryButton
import me.river.remoter.core.design.components.QuietButton
import me.river.remoter.core.design.components.SecondaryButton
import me.river.remoter.core.design.components.StatusLabel
import me.river.remoter.core.design.components.StatusTone
import me.river.remoter.core.design.rememberHaptics
import me.river.remoter.core.design.tnum
import me.river.remoter.feature.session.CommandBlock

enum class CameraAccess { Unknown, Granted, Denied, DeniedForever }

data class OnboardingCallbacks(
    val onOpenWireGuard: () -> Unit = {},
    val onPaste: () -> Unit = {},
    val onSubmitLink: (String) -> Unit = {},
    val onScanned: (String) -> Unit = {},
    val onAllowCamera: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onPairAgain: () -> Unit = {},
    val onScanAgain: () -> Unit = {},
    val onScanInstead: () -> Unit = {},
    val onLinkEdited: () -> Unit = {},
    val onStartOver: () -> Unit = {},
)

@Composable
fun OnboardingContent(step: OnboardingStep, camera: CameraAccess, cb: OnboardingCallbacks, showCamera: Boolean = true) {
    val c = Remoter.colors
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        val p = step.progress()
        if (p > 0f) {
            val shown by animateFloatAsState(p, tween(Dur.base, easing = EaseOut), label = "progress")
            LinearProgressIndicator(
                { shown },
                Modifier.fillMaxWidth().padding(Space.gutter).height(4.dp).clip(Shapes.pill)
                    .semantics { contentDescription = "Step ${(p * 3).toInt()} of 3" },
                color = if (c.isDark) c.volt else c.text, trackColor = c.surface,
                drawStopIndicator = {},
            )
        }
        AnimatedContent(
            step::class,
            transitionSpec = { fadeIn(tween(Dur.base, easing = EaseOut)) togetherWith fadeOut(tween(Dur.exit, easing = EaseIn)) },
            label = "step",
            modifier = Modifier.weight(1f),
        ) { _ ->
            when (step) {
                is OnboardingStep.Connect -> Connect(step, cb)
                is OnboardingStep.Scan -> Scan(step, camera, cb, showCamera)
                OnboardingStep.Pairing -> Centered { StatusLabel("Pairing with the laptop…", StatusTone.Warn, pulsing = true) }
                is OnboardingStep.Confirm -> Confirm(step)
                OnboardingStep.Done -> Centered { Text("Paired", style = Remoter.type.display, color = c.text) }
                is OnboardingStep.Stop -> Stop(step.why, cb)
                is OnboardingStep.PairAgain -> PairAgain(step.reason, cb)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize().padding(Space.s24), contentAlignment = Alignment.Center) { content() }

@Composable
private fun Page(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter, vertical = Space.s24), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
        Text(title, style = Remoter.type.display, color = Remoter.colors.text)
        content()
    }
}

@Composable
private fun Connect(s: OnboardingStep.Connect, cb: OnboardingCallbacks) = Page("Connect WireGuard") {
    // only the tunnel coming up can be seen from here, so step 3 never ticks.
    // scrolls on its own so at large fonts the status and the button stay on screen
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        SetupStep(null, "remoter is installed", null, done = true)
        SetupStep(1, "Import the tunnel", "In WireGuard, tap + and scan the code phone-tunnel.sh shows on the laptop.", done = s.tunnel)
        SetupStep(2, "Switch it on", "The rmt tunnel, in the WireGuard app.", done = s.tunnel)
        SetupStep(3, "Keep it on", "WireGuard's VPN settings: Always-on VPN on, Block connections without VPN off.", done = false)
    }
    // polite live region so TalkBack hears the step tick over
    Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        val (word, tone) = when {
            s.laptop -> "Laptop answered" to StatusTone.Ready
            s.tunnel -> "Tunnel is up, waiting for the laptop\u2026" to StatusTone.Warn
            else -> "Waiting for the tunnel\u2026" to StatusTone.Muted
        }
        StatusLabel(word, tone, pulsing = !s.laptop)
    }
    PrimaryButton("Open WireGuard", cb.onOpenWireGuard)
}

@Composable
private fun SetupStep(n: Int?, title: String, detail: String?, done: Boolean) {
    val c = Remoter.colors
    val t = Remoter.type
    Row(
        Modifier.fillMaxWidth().heightIn(min = Touch.min).semantics(mergeDescendants = true) {
            stateDescription = if (done) "Done" else "To do"
        },
        verticalAlignment = Alignment.Top,
    ) {
        // at least 28 dp, wider at large fonts instead of cutting the digit
        val haptics = rememberHaptics()
        var was by remember { mutableStateOf(done) }
        LaunchedEffect(done) {
            if (done && !was) haptics.tick()
            was = done
        }
        val check by animateFloatAsState(if (done) 1f else 0f, tween(if (Remoter.reducedMotion) 0 else Dur.base, easing = EaseOut), label = "tick")
        Box(
            Modifier.padding(top = 2.dp).defaultMinSize(28.dp, 28.dp).clip(Shapes.pill).background(animatedTone(if (done) c.volt else c.surface, "step")).padding(horizontal = Space.s4),
            contentAlignment = Alignment.Center,
        ) {
            // out of layout once the check lands: at 200% the digit held at alpha 0 stretched the disc into an oval
            if (check < 1f) n?.let { Text("$it", style = t.label.tnum(), color = c.text, modifier = Modifier.alpha(1f - check)) }
            Canvas(Modifier.size(14.dp)) {
                if (check > 0f) {
                    val a = Offset(size.width * 0.1f, size.height * 0.55f)
                    val b = Offset(size.width * 0.4f, size.height * 0.85f)
                    val e = Offset(size.width * 0.92f, size.height * 0.2f)
                    drawLine(c.onVolt, a, a + (b - a) * (check / 0.4f).coerceAtMost(1f), 2.dp.toPx(), StrokeCap.Round)
                    if (check > 0.4f) drawLine(c.onVolt, b, b + (e - b) * ((check - 0.4f) / 0.6f), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        Spacer(Modifier.width(Space.s16))
        Column(Modifier.weight(1f)) {
            Text(title, style = t.bodyStrong, color = c.text)
            detail?.let { Text(it, style = t.label, color = c.textMuted) }
        }
    }
}

@Composable
private fun Scan(s: OnboardingStep.Scan, camera: CameraAccess, cb: OnboardingCallbacks, showCamera: Boolean) {
    val c = Remoter.colors
    val t = Remoter.type
    if (s.pasting) {
        var text by rememberSaveable { mutableStateOf("") }
        val clipboard = LocalClipboardManager.current
        BackHandler(onBack = cb.onScanInstead)
        Page("Paste the pairing link") {
            Text("It starts with remoter://pair and comes from the laptop's terminal.", style = t.body, color = c.textMuted)
            BasicTextField(
                text,
                {
                    text = it
                    cb.onLinkEdited()
                },
                textStyle = t.mono.copy(color = c.text),
                cursorBrush = SolidColor(c.text),
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(Shapes.technical).background(c.surface).padding(Space.s16)
                    .semantics { contentDescription = "Pairing link" },
            )
            if (s.pasteInvalid) Text("That isn't a full pairing link. Copy it again from the laptop.", style = t.label, color = c.danger)
            SecondaryButton("Paste", {
                clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    text = it
                    cb.onLinkEdited()
                }
            })
            Spacer(Modifier.weight(1f))
            PrimaryButton("Pair", { cb.onSubmitLink(text) })
            QuietButton("Scan instead", cb.onScanInstead, Modifier.align(Alignment.CenterHorizontally))
        }
        return
    }
    Page("Scan the code from your laptop") {
        Text("Run this on the laptop:", style = t.body, color = c.text)
        CommandBlock("sudo remoterctl pair")
        when {
            s.rejected -> Text("The laptop didn't accept that. Run the pair command again for a new code.", style = t.label, color = c.danger)
            s.unreachable -> Text("Couldn't reach the laptop's pairing port. Check it's still waiting.", style = t.label, color = c.danger)
        }
        Box(Modifier.fillMaxWidth().weight(1f).clip(Shapes.card).background(c.terminal), contentAlignment = Alignment.Center) {
            when (camera) {
                CameraAccess.Granted -> if (showCamera) ScannerView(cb.onScanned)
                CameraAccess.Denied, CameraAccess.Unknown -> Column(Modifier.padding(Space.s24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                    Text("remoter only uses the camera for this code.", style = t.body, color = c.onTerminal)
                    PrimaryButton("Allow camera", cb.onAllowCamera)
                }
                CameraAccess.DeniedForever -> Column(Modifier.padding(Space.s24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                    Text("Camera access is off for remoter.", style = t.body, color = c.onTerminal)
                    PrimaryButton("Open settings", cb.onOpenSettings)
                }
            }
        }
        QuietButton("Paste the pairing link instead", cb.onPaste, Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun Confirm(s: OnboardingStep.Confirm) = Page("Type this on your laptop") {
    val c = Remoter.colors
    val t = Remoter.type
    // scrolls at large fonts, the status stays put
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s16)) {
        Spacer(Modifier.height(Space.s24))
        Text(
            s.code.chunked(3).joinToString(" "),
            style = t.display.tnum(),
            color = c.text,
            modifier = Modifier.semantics { contentDescription = "Code ${s.code.toList().joinToString(" ")}" },
        )
        Text("Boot key ${s.bootKey.chunked(4).joinToString(" ")}", style = t.label.tnum(), color = c.textMuted)
        Text("Check the laptop shows the same boot key before you type.", style = t.body, color = c.textMuted)
        if (s.weaknesses.isNotEmpty()) NotSecure(s.weaknesses)
    }
    StatusLabel("Waiting for the laptop…", StatusTone.Warn, pulsing = true)
}

@Composable
private fun NotSecure(weaknesses: List<Weakness>) {
    val c = Remoter.colors
    val t = Remoter.type
    Column(
        Modifier.fillMaxWidth().clip(Shapes.card).background(c.surface).border(1.dp, c.warn.copy(alpha = 0.5f), Shapes.card).padding(Space.cardPadding)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        Text("This phone isn't fully secure", style = t.bodyStrong, color = c.warn)
        weaknesses.forEach { Text(it.why(), style = t.body, color = c.text) }
        Text("Pairing still works, and the laptop sees this too.", style = t.label, color = c.textMuted)
    }
}

private fun Weakness.why() = when (this) {
    Weakness.NoStrongBox -> "There's no security chip, so the signing key lives in the phone's regular secure hardware."
    Weakness.BootloaderUnlocked -> "The bootloader is unlocked."
    Weakness.BootNotVerified -> "It runs software its maker didn't sign."
}

@Composable
private fun Stop(why: HardStop, cb: OnboardingCallbacks) {
    val (title, body) = when (why) {
        HardStop.Expired -> "This code expired" to "Run sudo remoterctl pair again."
        HardStop.ServerKeyMismatch -> "The laptop's key doesn't match the code" to "Don't pair on this network."
    }
    Page(title) {
        Text(body, style = Remoter.type.body, color = Remoter.colors.textMuted)
        Spacer(Modifier.weight(1f))
        // no retry on purpose. starting over is a deliberate choice, e.g. off a network that swapped the key
        if (why == HardStop.Expired) SecondaryButton("Scan a new code", cb.onScanAgain)
        else SecondaryButton("Start over", cb.onStartOver)
    }
}

@Composable
private fun PairAgain(reason: PairAgainReason, cb: OnboardingCallbacks) {
    val body = when (reason) {
        PairAgainReason.KeyInvalidated -> "A new fingerprint was added, so remoter's key was wiped. That's on purpose."
        PairAgainReason.Revoked -> "The laptop no longer knows this phone. It was revoked there."
        PairAgainReason.Unpaired -> "This phone was unpaired and its keys are gone."
    }
    Page("Pair again") {
        Text(body, style = Remoter.type.body, color = Remoter.colors.text)
        Spacer(Modifier.weight(1f))
        PrimaryButton("Pair again", cb.onPairAgain)
    }
}

@Composable
fun OnboardingScreen(vm: OnboardingViewModel, pairAgain: PairAgainReason?, onPaired: () -> Unit) {
    val context = LocalContext.current
    val step by vm.step.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var camera by remember {
        mutableStateOf(
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) CameraAccess.Granted else CameraAccess.Unknown,
        )
    }
    val activity = context as? android.app.Activity
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        camera = when {
            ok -> CameraAccess.Granted
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true -> CameraAccess.Denied
            else -> CameraAccess.DeniedForever
        }
    }
    LaunchedEffect(Unit) { vm.begin(pairAgain) }
    LaunchedEffect(step) { if (step is OnboardingStep.Scan && camera == CameraAccess.Unknown) ask.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(vm) {
        vm.paired.collect {
            haptics.confirm()
            kotlinx.coroutines.delay(900)
            onPaired()
        }
    }
    OnboardingContent(
        step, camera,
        OnboardingCallbacks(
            onOpenWireGuard = { openWireGuard(context) },
            onPaste = vm::paste,
            onSubmitLink = vm::submitLink,
            onScanned = vm::submitLink,
            onAllowCamera = { ask.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            },
            onPairAgain = vm::pairAgain,
            onScanAgain = vm::scanAgain,
            onScanInstead = vm::scanInstead,
            onLinkEdited = vm::linkEdited,
            onStartOver = vm::startOver,
        ),
    )
}

