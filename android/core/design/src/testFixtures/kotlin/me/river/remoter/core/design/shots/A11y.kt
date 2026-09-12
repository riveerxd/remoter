package me.river.remoter.core.design.shots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult

private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

private fun SemanticsNode.label(): String {
    val c = config
    val d = c.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
    val t = c.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
    val e = c.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    return listOf(d, t, e).joinToString(" ").trim()
}

/**
 * Every node a finger can act on says what it is: text or a content
 * description, its own or its children's, which is what a screen reader reads.
 */
fun ComposeContentTestRule.unlabelledClickables(): List<String> =
    onRoot().fetchSemanticsNode().all()
        .filter { it.config.contains(SemanticsActions.OnClick) || it.config.contains(SemanticsActions.OnLongClick) }
        .filter { n -> n.all().all { it.label().isEmpty() } }
        .map { "clickable at ${it.boundsInRoot}" }

/**
 * Text cut off at this font scale. Paths may trim on purpose (from the left,
 * so the folder name stays); names and copy must grow instead.
 */
fun ComposeContentTestRule.clippedText(allow: (String) -> Boolean = { it.startsWith("~/") || it.startsWith("…/") }): List<String> =
    onRoot().fetchSemanticsNode().all().mapNotNull { n ->
        val get = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@mapNotNull null
        val out = mutableListOf<TextLayoutResult>()
        get(out)
        val r = out.firstOrNull() ?: return@mapNotNull null
        val text = r.layoutInput.text.text
        // With soft wrap on, text clips only by ellipsis or by running out of
        // height. didOverflowWidth is no guide: letter spacing trips it under
        // Robolectric, and the one soft-wrap-off text, the terminal, scrolls.
        val clipped = r.didOverflowHeight || r.isLineEllipsized(r.lineCount - 1)
        if (clipped && !allow(text)) "\"$text\"" else null
    }

fun ComposeContentTestRule.spoken(): List<String> =
    onRoot().fetchSemanticsNode().all().map { it.label() }.filter { it.isNotEmpty() }
