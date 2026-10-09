package me.river.remoter.core.design

import me.river.remoter.core.design.components.hopSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMapTest {
    private val node = 168f
    private val gap = 400f
    private val lw = 18f
    private val ringR = node / 2 + 15f

    // the last line poked through the gap between the ring and the node
    @Test
    fun last_hop_stops_at_the_ring() {
        val (_, end) = hopSpan(1, 3, node, gap, lw, ringR)
        val centre = node / 2 + gap * 2
        assertTrue(end + lw / 2 <= centre - ringR)
    }

    @Test
    fun hops_reach_the_nodes_without_a_ring() {
        val (start, end) = hopSpan(1, 3, node, gap, lw, null)
        assertEquals(node / 2 + gap + node / 2, start)
        assertEquals(node / 2 + gap * 2 - node / 2, end)
        assertEquals(hopSpan(0, 3, node, gap, lw, ringR), hopSpan(0, 3, node, gap, lw, null))
    }
}
