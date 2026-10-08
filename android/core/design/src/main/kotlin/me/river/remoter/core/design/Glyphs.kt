package me.river.remoter.core.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private fun glyph(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
    }.build()

/**
 * The handful of glyphs the app draws. The five the core icon set lacks are
 * drawn here instead of pulling in the extended set, which is several
 * megabytes for five shapes.
 */
object Glyphs {
    val search = Icons.Filled.Search
    val back = Icons.AutoMirrored.Filled.ArrowBack
    val chevron = Icons.AutoMirrored.Filled.KeyboardArrowRight
    val check = Icons.Filled.Check
    val close = Icons.Filled.Close
    val lock = Icons.Filled.Lock
    val settings = Icons.Filled.Settings

    val folder = glyph("folder", "M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z")
    val link = glyph(
        "link",
        "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z",
    )
    val pin = glyph(
        "pin",
        "M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z",
    )
    val shield = glyph("shield", "M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z")
    val phone = glyph("phone", "M16 1H8C6.34 1 5 2.34 5 4v16c0 1.66 1.34 3 3 3h8c1.66 0 3-1.34 3-3V4c0-1.66-1.34-3-3-3zm-4 20.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM17 17H7V4h10v13z")
    val relay = glyph("relay", "M4 4h16v6H4V4zm2 2v2h2V6H6zm-2 8h16v6H4v-6zm2 2v2h2v-2H6z")
    val laptop = glyph("laptop", "M4 5h16c.55 0 1 .45 1 1v10H3V6c0-.55.45-1 1-1zm1 2v7h14V7H5zM1 17h22v1c0 .55-.45 1-1 1H2c-.55 0-1-.45-1-1v-1z")
    val copy = glyph("copy", "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z")
    /** clock wound back = past conversation */
    val history = glyph("history", "M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z")
    val play = glyph("play", "M8 5v14l11-7z")
    /** reorder grip, as a menu glyph */
    val reorder = glyph("reorder", "M20 9H4v2h16V9zM4 15h16v-2H4v2z")
    val plus = glyph("plus", "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val git = glyph(
        "git",
        "M6 2.5a3 3 0 0 1 1 5.83v7.34a3 3 0 1 1-2 0V8.33A3 3 0 0 1 6 2.5zm0 16a1 1 0 1 0 0 2 1 1 0 0 0 0-2z",
        "M18 2.5a3 3 0 0 1 1 5.83V10a4 4 0 0 1-4 4H8.5v-2H15a2 2 0 0 0 2-2V8.33A3 3 0 0 1 18 2.5z",
    )
}
