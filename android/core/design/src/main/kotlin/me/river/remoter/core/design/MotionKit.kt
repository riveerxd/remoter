package me.river.remoter.core.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.animation.core.Animatable

// All motion specs live in this file. Arrive: Dur.base + EaseOut. Leave: Dur.exit + EaseIn.
// Springs for anything under a finger, nothing at all under reduced motion.

fun <T> arrive(): FiniteAnimationSpec<T> = tween(Dur.base, easing = EaseOut)
fun <T> leave(): FiniteAnimationSpec<T> = tween(Dur.exit, easing = EaseIn)

enum class Press(val scale: Float) { Button(0.97f), Card(0.98f), Icon(0.92f) }

/**
 * Press feedback for anything tappable: sinks under the finger on [PressSpring], springs back.
 * Ripple is off app wide (RemoterTheme).
 */
@Composable
fun Modifier.pressable(interaction: InteractionSource, kind: Press = Press.Card): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val reduced = Remoter.reducedMotion
    val s by animateFloatAsState(if (pressed && !reduced) kind.scale else 1f, PressSpring, label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

@Composable
fun animatedTone(target: Color, label: String = "tone"): Color =
    animateColorAsState(target, if (Remoter.reducedMotion) tween(0) else arrive(), label = label).value

@Composable
fun animatedAlpha(visible: Boolean, label: String = "alpha"): Float =
    animateFloatAsState(if (visible) 1f else 0f, if (Remoter.reducedMotion) tween(0) else if (visible) arrive() else leave(), label = label).value

private val appearIn: EnterTransition = expandVertically(arrive(), expandFrom = Alignment.Top) + fadeIn(arrive())
private val appearOut: ExitTransition = shrinkVertically(leave(), shrinkTowards = Alignment.Top) + fadeOut(leave())

/**
 * Something that opens up in a column (an inline error, a note, a choice under a picked row) and
 * closes again. Use it below the finger, never above a control the thumb is heading for: that
 * control would move.
 */
@Composable
fun ColumnScope.Appear(visible: Boolean, modifier: Modifier = Modifier, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val still = Remoter.reducedMotion
    AnimatedVisibility(visible, modifier, enter = if (still) EnterTransition.None else appearIn, exit = if (still) ExitTransition.None else appearOut, content = content)
}

@Composable
fun AppearAnywhere(visible: Boolean, modifier: Modifier = Modifier, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val still = Remoter.reducedMotion
    AnimatedVisibility(visible, modifier, enter = if (still) EnterTransition.None else appearIn, exit = if (still) ExitTransition.None else appearOut, content = content)
}

@Composable
fun FadeInPlace(visible: Boolean, modifier: Modifier = Modifier, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val still = Remoter.reducedMotion
    AnimatedVisibility(visible, modifier, enter = if (still) EnterTransition.None else fadeIn(arrive()), exit = if (still) ExitTransition.None else fadeOut(leave()), content = content)
}

fun androidx.compose.animation.AnimatedContentTransitionScope<*>.fadeSwap(): ContentTransform =
    (fadeIn(tween(Dur.base, delayMillis = Dur.exit / 2, easing = EaseOut)) togetherWith fadeOut(leave()))
        .using(SizeTransform(clip = false) { _, _ -> spring<androidx.compose.ui.unit.IntSize>(dampingRatio = 0.86f, stiffness = 520f) })

/**
 * Content keyed on [key] that crossfades when the key changes, and grows or shrinks on the sheet
 * spring. Each side draws from its own [state], never the current one: an error layout fading out
 * once cast the new state and crashed the app.
 */
@Composable
fun <S> FadeSwap(state: S, modifier: Modifier = Modifier, key: (S) -> Any? = { it }, content: @Composable (S) -> Unit) {
    val still = Remoter.reducedMotion
    AnimatedContent(state, modifier, transitionSpec = { if (still) cut() else fadeSwap() }, contentKey = key, label = "swap") { content(it) }
}

private fun cut(): ContentTransform =
    ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() })

@Composable
fun SwapText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) = FadeSwap(text, modifier) { Text(it, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = textAlign) }

/**
 * Rows of a plain column that open in when they arrive and close out when they leave, the way
 * banners do, so a pinned folder visibly moves instead of blinking from one list to another. A row
 * that leaves keeps its place and its last content while it closes. Nothing animates on the first
 * frame: what is there when the screen appears is simply there.
 */
@Composable
fun <T> ColumnScope.AnimatedItems(items: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    val first = androidx.compose.runtime.remember { booleanArrayOf(true) }
    val states = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateMapOf<Any, androidx.compose.animation.core.MutableTransitionState<Boolean>>() }
    val last = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateMapOf<Any, T>() }
    val order = androidx.compose.runtime.remember { mutableListOf<Any>() }
    val keys = items.map(key)
    val still = Remoter.reducedMotion
    items.forEach { item ->
        val k = key(item)
        last[k] = item
        states.getOrPut(k) { androidx.compose.animation.core.MutableTransitionState(first[0]) }.targetState = true
    }
    states.forEach { (k, st) -> if (k !in keys) st.targetState = false }
    // Leaving rows stay where they were among the ones that remain.
    val merged = keys.toMutableList()
    order.forEachIndexed { i, k -> if (k !in keys && states[k] != null) merged.add(minOf(i, merged.size), k) }
    order.clear()
    order.addAll(merged)
    androidx.compose.runtime.SideEffect { first[0] = false }
    androidx.compose.runtime.LaunchedEffect(states.values.map { it.isIdle to it.currentState }) {
        states.entries.filter { (_, st) -> st.isIdle && !st.currentState && !st.targetState }.map { it.key }.forEach {
            states.remove(it)
            last.remove(it)
        }
    }
    merged.forEach { k ->
        val st = states[k] ?: return@forEach
        val item = last[k] ?: return@forEach
        androidx.compose.runtime.key(k) {
            AnimatedVisibility(visibleState = st, enter = if (still) EnterTransition.None else appearIn, exit = if (still) ExitTransition.None else appearOut) { content(item) }
        }
    }
}

val ListPlacement = spring(dampingRatio = 0.86f, stiffness = 520f, visibilityThreshold = IntOffset(1, 1))
val ListFadeIn: FiniteAnimationSpec<Float> = tween(Dur.base, easing = EaseOut)
val ListFadeOut: FiniteAnimationSpec<Float> = tween(Dur.exit, easing = EaseIn)

// spring, settles in about the sheets' time
val SettleSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f)

/**
 * [pressable] as an Indication, so plain clickables get it for free. RemoterTheme provides the
 * Card one instead of Material's ripple; buttons and icons pass their own via [pressIndication].
 * Only the drawing scales, the hit area stays put.
 */
class PressIndication(private val scale: Float) : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): androidx.compose.ui.node.DelegatableNode = PressNode(interactionSource, scale)
    override fun equals(other: Any?) = other is PressIndication && other.scale == scale
    override fun hashCode() = scale.hashCode()
}

private class PressNode(private val source: InteractionSource, private val scale: Float) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val anim = Animatable(1f)

    override fun onAttach() {
        coroutineScope.launch {
            val held = mutableListOf<PressInteraction.Press>()
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> held += i
                    is PressInteraction.Release -> held -= i.press
                    is PressInteraction.Cancel -> held -= i.press
                }
                val target = if (held.isNotEmpty() && !currentValueOf(LocalReducedMotion)) scale else 1f
                launch { anim.animateTo(target, PressSpring) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val s = anim.value
        if (s == 1f) drawContent() else scale(s) { this@draw.drawContent() }
    }
}

private val pressIndications = Press.entries.associateWith { PressIndication(it.scale) }

fun pressIndication(kind: Press): androidx.compose.foundation.Indication = pressIndications.getValue(kind)
