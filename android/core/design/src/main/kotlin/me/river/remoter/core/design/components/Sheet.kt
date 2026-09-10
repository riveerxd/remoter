package me.river.remoter.core.design.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Shapes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.rememberHaptics
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

enum class SheetValue { Open, Hidden }

/**
 * Which sheet is up. One at a time: a sheet that opens dismisses the one before it through that
 * sheet's own dismiss, and leaving the app dismisses whichever is up, so nothing stale greets you
 * on return.
 */
class SheetSlot {
    private var holder: Any? = null
    private var holderDismiss: (() -> Unit)? = null
    private var holderHandOff: (() -> Unit)? = null

    /**
     * [handOff] makes the sheet being replaced vanish without its exit: sliding out under the
     * new sheet's scrim stacked two scrims and two sheets for the length of the exit.
     */
    internal fun claim(token: Any, dismiss: () -> Unit, handOff: () -> Unit = {}) {
        if (holder === token) {
            holderDismiss = dismiss
            holderHandOff = handOff
            return
        }
        val before = holderDismiss
        val beforeHandOff = holderHandOff
        holder = token
        holderDismiss = dismiss
        holderHandOff = handOff
        beforeHandOff?.invoke()
        before?.invoke()
    }

    internal fun release(token: Any) {
        if (holder === token) {
            holder = null
            holderDismiss = null
            holderHandOff = null
        }
    }

    fun dismissAll() {
        val d = holderDismiss
        holder = null
        holderDismiss = null
        holderHandOff = null
        d?.invoke()
    }
}

val LocalSheetSlot = androidx.compose.runtime.staticCompositionLocalOf<SheetSlot?> { null }

/** A flick down faster than this closes the sheet even short of the 40% line, in px/s. */
private const val FlingDismissVelocity = 1800f

private val HeightSpring = spring<IntSize>(dampingRatio = 0.86f, stiffness = 520f)

/**
 * Our own sheet instead of ModalBottomSheet: its height changes a lot between
 * states, and a Material sheet re-anchors and jitters when content resizes.
 * Here the anchors are ours, so a height change is just a spring.
 * Dismisses past 40% of its height with a threshold haptic, and handles
 * predictive back itself: scale to 0.96 following the finger, spring back if
 * cancelled.
 */
@Composable
fun RemoterSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    val isVisible by rememberUpdatedState(visible)
    val slot = LocalSheetSlot.current
    val token = remember { Any() }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val state = remember { AnchoredDraggableState(SheetValue.Hidden) }
    var height by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(visible) }
    // Set when another sheet took the slot: this one leaves without its exit.
    var handedOff by remember { mutableStateOf(false) }
    if (slot != null) {
        androidx.compose.runtime.LaunchedEffect(visible) {
            if (visible) {
                handedOff = false
                slot.claim(token, dismiss = { dismiss() }, handOff = { handedOff = true })
            } else {
                slot.release(token)
            }
        }
        androidx.compose.runtime.DisposableEffect(slot) { onDispose { slot.release(token) } }
    }
    val back = remember { Animatable(0f) }
    val drag = remember { MutableInteractionSource() }
    val dragging by drag.collectIsDraggedAsState()
    var nestedFinger by remember { mutableStateOf(false) }
    var backEdgeLeft by remember { mutableStateOf(true) }

    LaunchedEffect(visible, height) {
        if (height <= 0f) {
            if (visible) shown = true
            return@LaunchedEffect
        }
        state.updateAnchors(DraggableAnchors { SheetValue.Open at 0f; SheetValue.Hidden at height })
        if (visible) {
            shown = true
            state.animateTo(SheetValue.Open, me.river.remoter.core.design.SheetSpring)
        } else if (shown) {
            if (handedOff) {
                state.snapTo(SheetValue.Hidden)
            } else {
                state.animateTo(SheetValue.Hidden, androidx.compose.animation.core.tween(me.river.remoter.core.design.Dur.exit, easing = me.river.remoter.core.design.EaseIn))
            }
            shown = false
            handedOff = false
        }
    }
    // Crossing 40% buzzes once, and only under a finger: the sheet opening
    // from Hidden starts past the line and must not count.
    LaunchedEffect(state) {
        var past = false
        snapshotFlow { state.offset to (dragging || nestedFinger) }.collect { (off, finger) ->
            if (height <= 0f || off.isNaN()) return@collect
            val nowPast = off > height * 0.4f
            if (finger && nowPast && !past && isVisible) haptics.threshold()
            past = nowPast
        }
    }
    // Only Open to Hidden is a dismissal. The state starts Hidden, and reading
    // that first value as a dismissal closed sheets the moment they opened.
    LaunchedEffect(state) {
        var prev: SheetValue? = null
        snapshotFlow { state.settledValue }.collect { v ->
            if (prev == SheetValue.Open && v == SheetValue.Hidden && isVisible) dismiss()
            prev = v
        }
    }

    // The content scrolls, so drags that begin on it reach the scroller first. This hands
    // the scroller's leftovers to the sheet: a downward drag once the content is at its top
    // moves the sheet, and while the sheet is pulled down an upward drag lifts it back before
    // the content scrolls. Without it only the handle could move the sheet.
    val settle: suspend (Float) -> Unit = { velocity ->
        nestedFinger = false
        val off = state.offset
        if (height > 0f && !off.isNaN() && off > 0f) {
            val target = if (off > height * 0.4f || velocity > FlingDismissVelocity) SheetValue.Hidden else SheetValue.Open
            state.animateTo(target, me.river.remoter.core.design.SheetSpring)
        }
    }
    val nested = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val off = state.offset
                if (source != NestedScrollSource.UserInput || available.y >= 0f || off.isNaN() || off <= 0f) return Offset.Zero
                nestedFinger = true
                return Offset(0f, state.dispatchRawDelta(available.y))
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                nestedFinger = true
                return Offset(0f, state.dispatchRawDelta(available.y))
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val off = state.offset
                if (off.isNaN() || off <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                settle(available.y)
                return available
            }
        }
    }

    PredictiveBackHandler(enabled = visible) { progress ->
        try {
            progress.collect { e ->
                backEdgeLeft = e.swipeEdge == androidx.activity.BackEventCompat.EDGE_LEFT
                back.snapTo(e.progress)
            }
            dismiss()
            back.snapTo(0f)
        } catch (e: CancellationException) {
            scope.launch { back.animateTo(0f, me.river.remoter.core.design.SheetSpring) }
            throw e
        }
    }

    if (!shown) return
    val maxSheet = with(LocalDensity.current) { (androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height * 0.92f).toDp() }
    val frac = if (height > 0f && !state.offset.isNaN()) (1f - state.offset / height).coerceIn(0f, 1f) else 0f
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f * frac))
                .clickable(remember { MutableInteractionSource() }, indication = null) { dismiss() }
                .semantics { contentDescription = "Close" },
        )
        val density = LocalDensity.current
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxSheet)
                .onSizeChanged { height = it.height.toFloat() }
                .offset { IntOffset(0, if (state.offset.isNaN()) height.roundToInt() else state.offset.roundToInt()) }
                .graphicsLayer {
                    val s = 1f - 0.04f * back.value
                    scaleX = s
                    scaleY = s
                    translationX = (if (backEdgeLeft) 1f else -1f) * back.value * with(density) { Space.s16.toPx() }
                }
                .nestedScroll(nested)
                .anchoredDraggable(
                    state,
                    reverseDirection = false,
                    orientation = Orientation.Vertical,
                    interactionSource = drag,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(state, positionalThreshold = { it * 0.4f }),
                )
                // light mode raised is white on white, only the shadow lifts it
                .shadow(if (Remoter.colors.isDark) 0.dp else 16.dp, Shapes.sheet)
                .clip(Shapes.sheet)
                .background(Remoter.colors.surfaceRaised)
                // The keyboard pushes the sheet up, so Start never hides under it while naming.
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .animateContentSize(HeightSpring),
        ) {
            DragHandle()
            // At large font sizes the content can outgrow the screen; it scrolls
            // inside the sheet instead of being squeezed.
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(start = Space.sheetPadding, end = Space.sheetPadding, bottom = Space.sheetPadding),
                content = content,
            )
        }
    }
}

/**
 * Shows [item] while it isn't null, closes when it is. Keeps drawing the last item while it slides
 * away, otherwise the sheet empties and shrinks mid exit.
 */
@Composable
fun <T : Any> RemoterSheetFor(
    item: T?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    var last by remember { mutableStateOf(item) }
    if (item != null) last = item
    RemoterSheet(visible = item != null, onDismiss = onDismiss, modifier = modifier) {
        last?.let { content(it) }
    }
}

