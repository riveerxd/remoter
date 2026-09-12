package me.river.remoter.core.design

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import me.river.remoter.core.design.components.FolderRow
import me.river.remoter.core.design.components.FolderRowModel
import me.river.remoter.core.design.components.RowNote
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A name with bytes remoter won't show arrives with U+FFFD in it, which some fonts draw as nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class FolderRowNameTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unsupported_name_shows_question_marks() {
        compose.setContent {
            RemoterTheme(dark = true, reducedMotion = true) {
                FolderRow(FolderRowModel("bad�name", note = RowNote.Unsupported), onClick = {})
            }
        }
        compose.onNodeWithText("bad?name").assertExists()
        compose.onNodeWithText("Name has characters remoter won't touch").assertExists()
    }
}
