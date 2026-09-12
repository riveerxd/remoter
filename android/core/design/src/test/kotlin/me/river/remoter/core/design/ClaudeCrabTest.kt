package me.river.remoter.core.design

import me.river.remoter.core.design.components.CRAB_ROWS
import me.river.remoter.core.design.components.quadrants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * claude's own welcome-screen sprite, quadrant for quadrant. The grid below was rasterized
 * separately from the Unicode block names, so the two check each other.
 */
class ClaudeCrabTest {
    @Test
    fun the_crab_is_claudes_own_sprite() {
        val px = CRAB_ROWS.flatMap { line ->
            listOf(0, 1).map { half -> line.flatMap { c -> quadrants(c).let { q -> listOf(q[half * 2], q[half * 2 + 1]) } }.joinToString("") { if (it) "#" else "." } }
        }
        assertEquals(
            listOf(
                "...#############..",
                "...##.#######.##..",
                ".#################",
                "...#############..",
                "...#.#.......#.#..",
                "..................",
            ).joinToString("\n"),
            px.joinToString("\n"),
        )
    }

    /** It looked off centre in every disc. Across: the drawn pixels' box sits dead centre. */
    @Test
    fun the_crab_is_centred_across() {
        val px = me.river.remoter.core.design.components.crabPixels()
        val left = px.minOf { it[0] } - me.river.remoter.core.design.components.CRAB_LEFT
        val right = px.maxOf { it[0] + it[2] } - me.river.remoter.core.design.components.CRAB_LEFT
        assertEquals("no empty margin left", 0f, left, 0.001f)
        assertEquals("no empty margin right", me.river.remoter.core.design.components.CRAB_W, right, 0.001f)
    }

    /** Down: between the box centre and the weight centre, which is where it reads as centred. */
    @Test
    fun the_crab_is_centred_by_eye_down() {
        val px = me.river.remoter.core.design.components.crabPixels()
        val drop = me.river.remoter.core.design.components.CRAB_DROP
        val top = px.minOf { it[1] } + drop
        val bottom = px.maxOf { it[1] + it[3] } + drop
        val weight = px.sumOf { ((it[1] + drop + it[3] / 2) * it[2] * it[3]).toDouble() } / px.sumOf { (it[2] * it[3]).toDouble() }
        val optical = ((top + bottom) / 2 + weight) / 2
        assertEquals(me.river.remoter.core.design.components.CRAB_H / 2.0, optical, 0.1)
    }
}

