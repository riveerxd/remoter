package me.river.remoter.feature.browser

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import me.river.remoter.core.design.components.MenuRow
import me.river.remoter.core.design.components.MenuHeader
import me.river.remoter.core.design.components.LocalSheetSlot
import me.river.remoter.core.design.Glyphs
import me.river.remoter.core.design.openWireGuard
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.river.remoter.core.net.DenyReason
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.Touch
import me.river.remoter.core.design.components.PrimaryButton
import me.river.remoter.core.design.components.RemoterSheetFor
import me.river.remoter.core.design.components.RemoterSnackbar
import me.river.remoter.core.net.FsEntry
import me.river.remoter.core.net.displayPath
import me.river.remoter.core.net.folderName

data class BrowserNav(
    val onBack: () -> Unit,
    val onCrumb: (String) -> Unit,
    val onOpen: (String) -> Unit,
    val onStart: (path: String, isGit: Boolean) -> Unit,
    /** The folder is gone on the laptop: pop to the nearest one that still exists. */
    val onGone: (String) -> Unit,
)

private sealed interface BrowserSheet {
    data class Menu(val e: FsEntry) : BrowserSheet
    data class Link(val e: FsEntry) : BrowserSheet
    data class Block(val b: Blocked) : BrowserSheet
}

@Composable
fun BrowserScreen(vm: BrowserViewModel, nav: BrowserNav, focusSearch: Boolean) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<BrowserSheet?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    val slot = LocalSheetSlot.current
    val clip = LocalClipboardManager.current
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.gone.collect { nav.onGone(it) } }
    Box(Modifier.fillMaxSize()) {
        BrowserContent(
            ui,
            BrowserCallbacks(
                onBack = nav.onBack,
                onCrumb = nav.onCrumb,
                onQuery = vm::setQuery,
                onOpen = nav.onOpen,
                onEntryMenu = { sheet = BrowserSheet.Menu(it) },
                onSymlink = { sheet = BrowserSheet.Link(it) },
                onPin = { vm.togglePin(it) },
                onNewFolder = vm::openNewFolder,
                onNewName = vm::setNewName,
                onToggleGit = vm::toggleGit,
                onSubmitNew = vm::submitNewFolder,
                onCancelNew = vm::cancelNewFolder,
                onStartHere = { nav.onStart(ui.path, ui.list?.isGit == true) },
                onBlocked = { sheet = BrowserSheet.Block(it) },
                onRetry = { vm.load() },
                onRetrySearch = vm::retrySearch,
                onOpenWireGuard = { openWireGuard(context) },
            ),
            focusSearch,
        )
        // Clears the bottom bar: the button, its gutter on both sides, and the nav bar under it.
        val snackPlace = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = Space.gutter, end = Space.gutter, bottom = Touch.primaryButton + Space.gutter * 2 + Space.s8)
        val undo = ui.undoUnpin
        val msg = ui.snack
        // One snackbar at a time. The newest thing the user did wins, and a create
        // error that needs Retry comes back once the undo is gone.
        when {
            copied != null -> {
                val path = copied.orEmpty()
                key(path) { RemoterSnackbar("Copied $path", { copied = null }, snackPlace, autoDismissMs = 2_000) }
            }
            undo != null -> {
                RemoterSnackbar(
                    "Unpinned ${displayPath(undo.second)}", vm::undoShown, snackPlace,
                    actionLabel = "Undo", onAction = { vm.undoUnpin() },
                    autoDismissMs = 5_000,
                )
            }
            msg != null -> {
                RemoterSnackbar(
                    msg, vm::snackShown, snackPlace,
                    actionLabel = if (ui.snackRetry) "Retry" else null,
                    onAction = if (ui.snackRetry) vm::retryNewFolder else null,
                    autoDismissMs = if (ui.snackRetry) null else 4_000,
                )
            }
        }
        RemoterSheetFor(sheet, onDismiss = { sheet = null }) { s ->
            when (s) {
                is BrowserSheet.Menu -> {
                    val child = vm.childPath(s.e.name)
                    MenuHeader(s.e.name, displayPath(child))
                    // an older laptop still says untrusted, and its refusal explains
                    val canStart = s.e.spawnAllowed || s.e.denyReason == DenyReason.Untrusted
                    // The Start sheet takes the sheet slot, which closes this menu without its exit.
                    if (canStart) MenuRow(Glyphs.play, "Start here", { if (slot == null) sheet = null; nav.onStart(child, s.e.isGit) }, divider = false)
                    MenuRow(Glyphs.pin, if (child in ui.pinned) "Unpin" else "Pin", { sheet = null; vm.togglePin(child) }, divider = canStart)
                    MenuRow(Glyphs.copy, "Copy path", {
                        clip.setText(AnnotatedString(displayPath(child)))
                        copied = displayPath(child)
                        sheet = null
                    })
                }
                is BrowserSheet.Link -> {
                    Text("${s.e.name} opens only by its real path", style = Remoter.type.title, color = Remoter.colors.text)
                    Spacer(Modifier.height(Space.s8))
                    val target = s.e.symlinkTarget
                    if (target != null) {
                        Text("It points to ${displayPath(target)}.", style = Remoter.type.body, color = Remoter.colors.textMuted)
                        Spacer(Modifier.height(Space.s16))
                        PrimaryButton("Go to ${folderName(target)}", { sheet = null; nav.onOpen(target) })
                    } else {
                        Text("It points outside your home folder, so remoter won't follow it.", style = Remoter.type.body, color = Remoter.colors.textMuted)
                    }
                }
                is BrowserSheet.Block -> BlockedCopy(s.b, ui.hostname, ui.name, onOpenWireGuard = { sheet = null; openWireGuard(context) })
            }
        }
    }
}

