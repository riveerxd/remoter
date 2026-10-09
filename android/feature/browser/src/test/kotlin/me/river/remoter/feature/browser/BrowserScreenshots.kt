package me.river.remoter.feature.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.collections.immutable.persistentListOf
import me.river.remoter.core.design.shots.Variant
import me.river.remoter.core.design.shots.shot
import me.river.remoter.core.net.AppError
import me.river.remoter.core.net.DenyReason
import me.river.remoter.core.net.ErrorCode
import me.river.remoter.core.net.FsEntry
import me.river.remoter.core.net.ListResponse
import me.river.remoter.core.net.SearchHit
import me.river.remoter.core.net.SymlinkKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun e(name: String, git: Boolean = false, deny: DenyReason? = null, link: SymlinkKind = SymlinkKind.None, bad: Boolean = false, running: Int = 0) =
    FsEntry(name, 0, git, git, link, if (link == SymlinkKind.Absolute) "Projects/remoter" else null, 3, running, deny != DenyReason.Untrusted, deny == null && !bad && link == SymlinkKind.None, deny, bad)

private val list = ListResponse(
    "Projects", false, true, true, null,
    listOf(e("api", git = true), e("remoter", git = true, running = 1), e("Downloads"), e(".ssh", deny = DenyReason.Denied), e("work", link = SymlinkKind.Absolute), e("bad�name", bad = true)),
    truncated = false, partial = false,
)

internal val browserStates = mapOf(
    "listing" to BrowserUi("Projects", list, loading = false, pinned = setOf("Projects/remoter")),
    "search_deeper" to BrowserUi("Projects", list, loading = false, query = "rem", deeper = persistentListOf(SearchHit("Projects/clients/remote-kit", "remote-kit", true, 3))),
    "search_none" to BrowserUi("Projects", list, loading = false, query = "foo"),
    "empty" to BrowserUi("Projects/remoter", list.copy(path = "Projects/remoter", entries = emptyList()), loading = false),
    "new_folder" to BrowserUi("Projects", list, loading = false, newFolder = NewFolder(editing = true, name = "new-thing", gitInit = true)),
    "new_folder_error" to BrowserUi("Projects", list, loading = false, newFolder = NewFolder(editing = true, name = "-bad", error = AppError.Validation(ErrorCode.NameInvalid))),
    "loading_first" to BrowserUi("Projects", null, loading = true),
    "load_failed" to BrowserUi("Projects", null, loading = false, hostname = "r1v3r", error = AppError.Unreachable),
    "stale" to BrowserUi("Projects", list, loading = false, hostname = "r1v3r", error = AppError.Unreachable),
    "search_failed" to BrowserUi("Projects", list, loading = false, hostname = "r1v3r", query = "foo", searchError = AppError.Unreachable),
    "denied_here" to BrowserUi(".ssh", list.copy(path = ".ssh", entries = emptyList(), spawnAllowed = false, denyReason = DenyReason.Denied), loading = false),
)

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BrowserScreenshots(private val v: Variant) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = Variant.params()
    }

    @get:Rule val compose = createComposeRule()

    private fun one(k: String) = compose.shot("browser_$k", v) { BrowserContent(browserStates.getValue(k), BrowserCallbacks(), focusSearch = false) }

    @Test fun listing() = one("listing")
    @Test fun search_deeper() = one("search_deeper")
    @Test fun search_none() = one("search_none")
    @Test fun empty() = one("empty")
    @Test fun new_folder() = one("new_folder")
    @Test fun new_folder_error() = one("new_folder_error")
    @Test fun loading_first() {
        // skeletons only show after 1 s
        compose.mainClock.autoAdvance = false
        compose.setContent { me.river.remoter.core.design.RemoterTheme(dark = v.dark, reducedMotion = true) { BrowserContent(browserStates.getValue("loading_first"), BrowserCallbacks(), false) } }
        compose.mainClock.advanceTimeBy(1_100)
        compose.onRoot().captureRoboImage("src/test/snapshots/browser_loading_first_${v.suffix}.png")
    }
    @Test fun denied_here() = one("denied_here")
    @Test fun load_failed() = one("load_failed")
    @Test fun stale() = one("stale")
    @Test fun search_failed() = one("search_failed")
}
