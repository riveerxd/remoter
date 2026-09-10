package me.river.remoter.core.design

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/** Provided by the app per navigation entry; null in tests and previews, where nothing is shared. */
class SharedScopes(val shared: SharedTransitionScope, val animated: AnimatedVisibilityScope)

val LocalSharedScopes = compositionLocalOf<SharedScopes?> { null }

private val Container = BoundsTransform { _, _ -> tween(Dur.screen, easing = EaseOut) }

/**
 * The search pill growing into the browser field, a banner growing into
 * session detail. A no-op when there is no transition to share.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.sharedContainer(key: String, onlyOnEnter: Boolean = false): Modifier = composed {
    val s = LocalSharedScopes.current ?: return@composed this
    // Session detail grows out of its banner, but shrinking the whole page back into a 72 dp
    // card under the back gesture squashed its buttons into the card. Leaving, it just fades.
    if (onlyOnEnter && s.animated.transition.targetState == androidx.compose.animation.EnterExitState.PostExit) return@composed this
    with(s.shared) {
        this@composed.sharedBounds(
            rememberSharedContentState(key),
            s.animated,
            boundsTransform = Container,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}
