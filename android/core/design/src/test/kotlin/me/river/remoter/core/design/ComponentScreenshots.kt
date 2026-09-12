package me.river.remoter.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.collections.immutable.persistentListOf
import me.river.remoter.core.design.components.Chip
import me.river.remoter.core.design.components.FolderRow
import me.river.remoter.core.design.components.FolderRowModel
import me.river.remoter.core.design.components.PrimaryButton
import me.river.remoter.core.design.components.ProgressLine
import me.river.remoter.core.design.components.QuietButton
import me.river.remoter.core.design.components.RemoterSnackbar
import me.river.remoter.core.design.components.RoundIconButton
import me.river.remoter.core.design.components.RowNote
import me.river.remoter.core.design.components.SearchPill
import me.river.remoter.core.design.components.SecondaryButton
import me.river.remoter.core.design.components.SkeletonRow
import me.river.remoter.core.design.components.StatusLabel
import me.river.remoter.core.design.components.StatusPill
import me.river.remoter.core.design.components.StatusTone
import me.river.remoter.core.design.components.TerminalCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h2400dp-xxhdpi")
class ComponentScreenshots(private val dark: Boolean, private val fontScale: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0} scale={1}")
        fun params() = listOf(arrayOf<Any>(false, 1f), arrayOf<Any>(true, 1f), arrayOf<Any>(false, 2f), arrayOf<Any>(true, 2f))
    }

    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                // Loops are stopped so a capture never lands on a random frame.
                RemoterTheme(dark = dark, reducedMotion = true) {
                    Column(
                        Modifier.fillMaxWidth().background(Remoter.colors.bg).padding(vertical = Space.s16),
                        verticalArrangement = Arrangement.spacedBy(Space.s16),
                    ) { content() }
                }
            }
        }
        val theme = if (dark) "dark" else "light"
        val scale = if (fontScale == 1f) "100" else "200"
        compose.onRoot().captureRoboImage("src/test/snapshots/${name}_${theme}_$scale.png")
    }

    private val gutter = Modifier.padding(horizontal = Space.gutter)

    @Test
    fun type() = shot("type") {
        val t = Remoter.type
        val c = Remoter.colors.text
        Column(gutter) {
            Text("Ready", style = t.display, color = c)
            Text("Start in…", style = t.headline, color = c)
            Text("remoter", style = t.title, color = c)
            Text("Works right in this folder.", style = t.body, color = c)
            Text("~/Projects/remoter", style = t.label, color = Remoter.colors.textMuted)
            Text("rc-01k6b7y3m4 · 1:23:07", style = t.mono, color = c)
            Text("481 207 · 38 ms · 1:23:07", style = t.title.tnum(), color = c)
        }
    }

    @Test
    fun buttons() = shot("buttons") {
        Column(gutter, verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            PrimaryButton("Start session", {})
            PrimaryButton("Start session", {}, loading = true)
            PrimaryButton("Retry · 24 s", {}, numeric = true)
            SecondaryButton("Keep in background", {})
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                QuietButton("Done", {})
                QuietButton("End it", {}, danger = true)
                RoundIconButton(Glyphs.settings, "Settings", {})
            }
        }
    }

    @Test
    fun search_pill() = shot("search_pill") { SearchPill({}, gutter) }

    @Test
    fun folder_rows() = shot("folder_rows") {
        FolderRow(FolderRowModel("remoter", path = "~/Projects/remoter", isGit = true), {})
        FolderRow(FolderRowModel("a-very-long-folder-name-that-must-still-fit", path = "~/Projects/clients/a-very-long-folder-name-that-must-still-fit", isGit = true), {})
        FolderRow(FolderRowModel("remoter", isGit = true, hasClaudeMd = true, folderCount = 3, running = 1, pinned = true), {}, onPin = {})
        FolderRow(FolderRowModel("scratch", folderCount = 1, pinned = false), {}, onPin = {})
        FolderRow(FolderRowModel(".ssh", note = RowNote.Denied, pinned = false), {}, onPin = {})
        FolderRow(FolderRowModel("Downloads", note = RowNote.Untrusted, pinned = false), {}, onPin = {})
        FolderRow(FolderRowModel("work", note = RowNote.AbsoluteSymlink, pinned = false), {}, onPin = {})
        FolderRow(FolderRowModel("bad\uFFFDname", note = RowNote.Unsupported), {})
        FolderRow(FolderRowModel("a-folder-name-that-is-long-enough-to-need-two-lines-at-large-font-scale", isGit = true), {})
    }

    @Test
    fun chips() = shot("chips") {
        Row(gutter, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
            Chip("~", false, {})
            Chip("Projects", false, {})
            Chip("remoter", true, {})
        }
    }

    @Test
    fun status() = shot("status") {
        Column(gutter, verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            StatusLabel("Starting", StatusTone.Warn)
            StatusLabel("Ready", StatusTone.Ready)
            StatusLabel("1 running", StatusTone.Live)
            StatusLabel("Stuck", StatusTone.Danger)
            StatusLabel("Exited · code 1 · 14:32", StatusTone.Muted)
            StatusPill("Ready", StatusTone.Ready)
        }
    }

    @Test
    fun mark() = shot("mark") {
        Row(gutter, horizontalArrangement = Arrangement.spacedBy(Space.s16)) {
            Mark(size = 96.dp)
            Mark(size = 96.dp, trim = 0.5f, dotScale = 0f)
            Mark(size = 96.dp, chevronColor = Remoter.colors.line)
        }
    }

    @Test
    fun terminal() = shot("terminal") {
        TerminalCard(
            persistentListOf(
                "·✔︎· Connected · remoter · main",
                "    Capacity: 1/32 · New sessions will be created in the current directory",
                "    remoter",
                "Continue coding in the Claude mobile app or https://claude.ai/code",
            ),
            gutter,
        )
    }

    @Test
    fun feedback() = shot("feedback") {
        ProgressLine()
        SkeletonRow()
        SkeletonRow()
        RemoterSnackbar("Couldn't create 'new-thing'", {}, gutter, actionLabel = "Retry", onAction = {})
        RemoterSnackbar("remoter is live · Open Claude", {}, gutter)
    }
}
