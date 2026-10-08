package me.river.remoter

import android.content.Intent
import android.provider.Settings as SystemSettings
import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import me.river.remoter.core.design.components.LocalSheetSlot
import me.river.remoter.core.design.components.SheetSlot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import me.river.remoter.core.design.Dur
import me.river.remoter.core.design.EaseIn
import me.river.remoter.core.design.EaseOut
import me.river.remoter.core.design.LocalHapticsEnabled
import me.river.remoter.core.design.LocalSharedScopes
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.SharedScopes
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.components.RemoterSnackbar
import me.river.remoter.core.design.components.RemoterSnackbarHost
import me.river.remoter.core.net.folderName
import me.river.remoter.feature.browser.BrowserNav
import me.river.remoter.feature.browser.BrowserScreen
import me.river.remoter.feature.browser.BrowserViewModel
import me.river.remoter.feature.home.HomeNav
import me.river.remoter.feature.home.HomeScreen
import me.river.remoter.feature.onboarding.OnboardingScreen
import me.river.remoter.feature.onboarding.PairAgainReason
import me.river.remoter.core.design.openWireGuard
import me.river.remoter.feature.session.ClaudeOpener
import me.river.remoter.feature.session.ErrorActions
import me.river.remoter.feature.session.SessionDetailContent
import me.river.remoter.feature.session.SessionDetailViewModel
import me.river.remoter.feature.session.StartCallbacks
import me.river.remoter.feature.session.StartSheet
import me.river.remoter.feature.session.StartTarget
import me.river.remoter.feature.session.StartViewModel
import me.river.remoter.feature.settings.AuditContent
import me.river.remoter.feature.settings.AuditViewModel
import me.river.remoter.feature.settings.SettingsCallbacks
import me.river.remoter.feature.settings.SettingsContent
import me.river.remoter.feature.settings.SettingsViewModel

@Composable
fun RemoterRoot(entrance: Boolean, onEntrance: () -> Unit) {
    val root: RootViewModel = hiltViewModel()
    val state by root.state.collectAsStateWithLifecycle()
    val system = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (state?.prefs?.theme) {
        me.river.remoter.core.net.ThemePref.Light -> false
        me.river.remoter.core.net.ThemePref.Dark -> true
        else -> system
    }
    // Status bar icons follow the chosen look, not the phone's: dark icons on a dark map vanish.
    val view = androidx.compose.ui.platform.LocalView.current
    val window = (LocalActivity.current as? android.app.Activity)?.window
    androidx.compose.runtime.SideEffect {
        window?.let { w -> androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !dark }
    }
    RemoterTheme(dark = dark, animate = true) {
        val s = state
        // nothing to decide until the first local read (splash covers it)
        if (s == null) {
            Box(Modifier.fillMaxSize().background(Remoter.colors.bg))
            return@RemoterTheme
        }
        CompositionLocalProvider(LocalHapticsEnabled provides s.prefs.haptics) {
            val start: NavKey = when {
                s.laptop == null -> Onboarding()
                else -> Home
            }
            Graph(start, root, entrance, onEntrance)
        }
    }
}

internal fun NavBackStack<NavKey>.popTo(pred: (NavKey) -> Boolean): Boolean {
    val i = indexOfLast(pred)
    if (i < 0) return false
    while (size > i + 1) removeAt(lastIndex)
    return true
}

/**
 * Back from a screen's own arrow. Only while that screen is on top and something is under it: a
 * second tap landed on the screen still fading out and popped Home too, and an empty stack crashed.
 */
internal fun NavBackStack<NavKey>.popFrom(key: NavKey) {
    if (size > 1 && lastOrNull() == key) removeAt(lastIndex)
}

private fun NavBackStack<NavKey>.resetTo(key: NavKey) {
    clear()
    add(key)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Graph(start: NavKey, root: RootViewModel, entrance: Boolean, onEntrance: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current as ComponentActivity
    val back = rememberNavBackStack(start)
    // Scoped to the activity so closing the sheet, or leaving a folder, never cancels a start.
    val startVm: StartViewModel = hiltViewModel(activity)
    val sheets = remember { SheetSlot() }
    val startUi by startVm.ui.collectAsStateWithLifecycle()
    val snack by root.snack.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        root.foreground()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        root.background()
        sheets.dismissAll()
        // "X is live" is only news right now. Its timer only runs on screen, so otherwise it
        // shows up minutes later about a session long opened or ended.
        startVm.snackShown()
    }
    // A Start sheet has nothing to do over pairing: pair again can come from anywhere.
    val top = back.lastOrNull()
    LaunchedEffect(top) { if (top is Onboarding) startVm.done() }

    // no Claude app and no store either
    fun openClaude(link: me.river.remoter.core.net.ClaudeLink?) {
        if (!ClaudeOpener.open(context, link)) root.say("The Claude app isn't installed")
    }
    fun startIn(path: String, isGit: Boolean) = startVm.open(StartTarget(path, folderName(path), isGit))
    fun pairAgain(r: PairAgainReason) = back.resetTo(Onboarding(r))
    val locking by root.locking.collectAsStateWithLifecycle()
    val capEnding by root.ending.collectAsStateWithLifecycle()
    val capEnded by root.ended.collectAsStateWithLifecycle()
    val errorActions = ErrorActions(
        locking = locking,
        endingIds = capEnding,
        endedIds = capEnded,
        onLockLaptop = root::lockLaptop,
        onPairAgain = { startVm.done(); pairAgain(PairAgainReason.KeyInvalidated) },
        onOpenDateSettings = { context.startActivity(Intent(SystemSettings.ACTION_DATE_SETTINGS)) },
        onOpenWireGuard = { openWireGuard(context) },
        onEnd = root::end,
        onOpenInClaude = { openClaude(it.claude); startVm.done() },
    )

    val push = slideInHorizontally(tween(Dur.screen, easing = EaseOut)) { it } togetherWith
        (slideOutHorizontally(tween(Dur.screen, easing = EaseOut)) { -(it * 0.3f).toInt() } + fadeOut(tween(Dur.screen), targetAlpha = 0.6f))
    val pop = (slideInHorizontally(tween(Dur.screen, easing = EaseOut)) { -(it * 0.3f).toInt() } + fadeIn(tween(Dur.screen), initialAlpha = 0.6f)) togetherWith
        slideOutHorizontally(tween(Dur.exit, easing = EaseIn)) { it }
    val predictive = (slideInHorizontally { -(it * 0.3f).toInt() } + fadeIn(initialAlpha = 0.6f)) togetherWith
        (slideOutHorizontally { it } + scaleOut(targetScale = 0.94f))
    val fadeMeta = NavDisplay.transitionSpec { fadeIn(tween(Dur.screen, easing = EaseOut)) togetherWith fadeOut(tween(Dur.exit, easing = EaseIn)) } +
        NavDisplay.popTransitionSpec { fadeIn(tween(Dur.screen, easing = EaseOut)) togetherWith fadeOut(tween(Dur.exit, easing = EaseIn)) } +
        // Fade screens go back with a fade too, the folder slide looked like a glitch on session
        // detail. The small scale keeps the gesture feeling attached.
        NavDisplay.predictivePopTransitionSpec { _ ->
            // The leaving page is gone by 60% of the swipe, so the two never sit half and half.
            fadeIn(tween(Dur.screen, easing = LinearEasing)) togetherWith
                (fadeOut(tween(Dur.exit, easing = LinearEasing)) + scaleOut(tween(Dur.screen, easing = LinearEasing), targetScale = 0.97f))
        }

    CompositionLocalProvider(LocalSheetSlot provides sheets) { RemoterSnackbarHost(bottomInset = snackbarInset(back.lastOrNull())) {
        SharedTransitionLayout {
            val shared = this
            @Composable
            fun Shared(content: @Composable () -> Unit) =
                CompositionLocalProvider(LocalSharedScopes provides SharedScopes(shared, LocalNavAnimatedContentScope.current), content = content)

            NavDisplay(
                backStack = back,
                onBack = { if (back.size > 1) back.removeAt(back.lastIndex) },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                sharedTransitionScope = shared,
                transitionSpec = { push },
                popTransitionSpec = { pop },
                predictivePopTransitionSpec = { predictive },
                entryProvider = entryProvider {
                    entry<Onboarding>(metadata = fadeMeta) { k ->
                        OnboardingScreen(hiltViewModel(), k.pairAgain) {
                            onEntrance()
                            back.resetTo(Home)
                        }
                    }
                    // App lock is gone. A back stack saved before the update can still hold it.
                    entry<Lock> { LaunchedEffect(Unit) { back.resetTo(Home) } }
                    entry<Home>(metadata = fadeMeta) {
                        Shared {
                            HomeScreen(
                                hiltViewModel(),
                                HomeNav(
                                    onStart = { startIn(it.path, it.isGit) },
                                    onSettings = { back.add(Settings) },
                                    onSearch = { back.add(Browser("", focusSearch = true)) },
                                    onBrowse = { back.add(Browser(it)) },
                                    onSession = { back.add(Session(it.id)) },
                                    onOpenWireGuard = { openWireGuard(context) },
                                ),
                                entrance,
                            )
                        }
                    }
                    entry<Browser> { k ->
                        Shared {
                            BrowserScreen(
                                hiltViewModel<BrowserViewModel, BrowserViewModel.Factory>(creationCallback = { it.create(k.path) }),
                                BrowserNav(
                                    onBack = { back.popFrom(k) },
                                    // breadcrumb tap = pop entries
                                    onCrumb = { p -> if (!back.popTo { it is Browser && it.path == p }) back.add(Browser(p)) },
                                    onOpen = { back.add(Browser(it)) },
                                    onStart = ::startIn,
                                    onGone = { p ->
                                        root.say("${folderName(p)} isn't there anymore")
                                        back.popFrom(k)
                                    },
                                ),
                                k.focusSearch,
                            )
                        }
                    }
                    entry<Session>(metadata = fadeMeta) { k ->
                        val vm = hiltViewModel<SessionDetailViewModel, SessionDetailViewModel.Factory>(creationCallback = { it.create(k.id) })
                        val ui by vm.ui.collectAsStateWithLifecycle()
                        // On End, home comes first; the banner shows Ending and collapses there.
                        LaunchedEffect(vm) { vm.goHome.collect { back.popTo { it is Home } } }
                        Shared {
                            SessionDetailContent(
                                ui,
                                onBack = { back.popFrom(k) },
                                onOpenClaude = ::openClaude,
                                onEnd = { vm.end() },
                                onZoom = vm::zoom,
                                errors = errorActions,
                                onDismissError = vm::clearError,
                            )
                        }
                    }
                    entry<Settings>(metadata = fadeMeta) {
                        val vm: SettingsViewModel = hiltViewModel()
                        val ui by vm.ui.collectAsStateWithLifecycle()
                        LaunchedEffect(vm) { vm.unpaired.collect { pairAgain(PairAgainReason.Unpaired) } }
                        SettingsContent(
                            ui,
                            SettingsCallbacks(
                                onBack = { back.popFrom(Settings) },
                                onPrefs = { f -> vm.set(f) },
                                onAudit = { back.add(Audit) },
                                onLock = { vm.lockLaptop() },
                                onUnpair = { vm.unpair() },
                            ),
                        )
                    }
                    entry<Audit>(metadata = fadeMeta) {
                        val vm: AuditViewModel = hiltViewModel()
                        val ui by vm.ui.collectAsStateWithLifecycle()
                        AuditContent(ui, onBack = { back.popFrom(Audit) }, onMore = { vm.more() })
                    }
                },
            )
        }
        StartSheet(
            startUi,
            StartCallbacks(
                onMode = startVm::setMode,
                onName = startVm::setName,
                onStart = startVm::start,
                onRetry = startVm::retry,
                onClose = {
                    val st = startUi.state
                    if (st is me.river.remoter.feature.session.StartState.Ready || st is me.river.remoter.feature.session.StartState.Exited) startVm.done() else startVm.close()
                },
                onDone = startVm::done,
                onEndIt = startVm::endIt,
                onOpenClaude = { openClaude(it); startVm.done() },
                onUseWorktree = startVm::startInWorktree,
                onHandoffInstead = startVm::handoffInstead,
                onPickPast = startVm::selectResume,
                onRetryPast = startVm::retryPast,
                onMorePast = startVm::expandPast,
                onPastChoice = startVm::setHandoff,
                errors = errorActions,
            ),
        )
        RootSnackbars(
            ready = startUi.readySnack?.name,
            onReadyShown = startVm::snackShown,
            onOpenReady = { startUi.readySnack?.let { openClaude(it.claude) }; startVm.snackShown() },
            message = snack,
            onMessageShown = root::snackShown,
        )
    } }
}

/**
 * The height of the bar each screen keeps at its bottom, so a snackbar sits above it. Browser's
 * bar is its "Start in X" button with a gutter above and below (BrowserScreen's BottomBar).
 */
internal fun snackbarInset(top: NavKey?): Dp = when (top) {
    is Browser -> Touch.primaryButton + Space.gutter * 2
    else -> 0.dp
}

@Composable
internal fun RootSnackbars(
    ready: String?,
    onReadyShown: () -> Unit,
    onOpenReady: () -> Unit,
    message: String?,
    onMessageShown: () -> Unit,
) {
    if (ready != null) {
        key(ready) {
            RemoterSnackbar("$ready is live", onReadyShown, actionLabel = "Open Claude", onAction = onOpenReady, autoDismissMs = ReadyMs, claudeAction = true)
        }
    }
    if (message != null) {
        key(message) { RemoterSnackbar(message, onMessageShown, autoDismissMs = NoteMs) }
    }
}

internal const val ReadyMs = 6_000L
internal const val NoteMs = 4_000L
