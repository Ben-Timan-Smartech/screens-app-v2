package com.smartech.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMathTest {
    private val loop = listOf(7_000L, 10_000L, 5_000L)   // total 22 s

    @Test fun locate_walks_the_loop_and_wraps() {
        assertEquals(SyncMath.Spot(0, 0L), SyncMath.locate(0L, loop))
        assertEquals(SyncMath.Spot(0, 6_999L), SyncMath.locate(6_999L, loop))
        assertEquals(SyncMath.Spot(1, 0L), SyncMath.locate(7_000L, loop))
        assertEquals(SyncMath.Spot(2, 4_999L), SyncMath.locate(21_999L, loop))
        assertEquals(SyncMath.Spot(0, 0L), SyncMath.locate(22_000L, loop))
        assertEquals(SyncMath.Spot(1, 3_000L), SyncMath.locate(22_000L * 5 + 10_000L, loop))
    }

    @Test fun locate_handles_edges() {
        assertNull(SyncMath.locate(0L, emptyList()))
        assertNull(SyncMath.locate(0L, listOf(0L, 0L)))
        assertEquals(SyncMath.Spot(0, 0L), SyncMath.locate(-5_000L, loop))
    }

    @Test fun gap_is_the_short_way_round_the_loop() {
        // 60 ms ahead just after the wrap is +60, not a whole loop behind.
        // Player: start of item 0 at 60 ms. Clock: 22_000 - 0 → last 0 ms of
        // the loop is "21_940 ms in".
        assertEquals(60L, SyncMath.gapMs(0, 60L, loop, 22_000L))
        // Player about to wrap (item 2 at 4_950), clock already 40 ms into
        // item 0 of the next loop: the player is 90 ms behind.
        assertEquals(-90L, SyncMath.gapMs(2, 4_950L, loop, 22_040L))
        // Ordinary mid-item readings.
        assertEquals(100L, SyncMath.gapMs(1, 3_100L, loop, 10_000L))
        assertEquals(-250L, SyncMath.gapMs(1, 2_750L, loop, 10_000L))
    }

    @Test fun wrap_signed_stays_in_half_a_loop() {
        for (d in -100_000L..100_000L step 737) {
            val w = SyncMath.wrapSigned(d, 22_000L)
            assertTrue(w > -11_000L && w <= 11_000L)
            assertEquals(0L, Math.floorMod(d - w, 22_000L))
        }
    }

    @Test fun speed_has_hysteresis_and_scales() {
        assertEquals(1f, SyncMath.speedFor(20L, correcting = false))
        assertEquals(1f, SyncMath.speedFor(-25L, correcting = false))
        assertTrue(SyncMath.speedFor(20L, correcting = true) < 1f)          // keep going
        assertEquals(1f, SyncMath.speedFor(8L, correcting = true))
        val small = SyncMath.speedFor(-60L, correcting = false)              // behind → faster
        val big = SyncMath.speedFor(-700L, correcting = false)
        assertTrue(small > 1f && big > small)
        assertEquals(1.04f, big, 0.0001f)
        val ahead = SyncMath.speedFor(700L, correcting = false)
        assertEquals(0.96f, ahead, 0.0001f)
    }

    @Test fun queue_mapping_handles_missing_and_repeated_items() {
        val timeline = listOf("a", "b", "a", "c")
        val queue = listOf("a", "a", "c")                  // "b" isn't on this screen
        assertEquals(0, SyncMath.queueIndexOf(timeline, 0, queue))
        assertNull(SyncMath.queueIndexOf(timeline, 1, queue))
        assertEquals(1, SyncMath.queueIndexOf(timeline, 2, queue))
        assertEquals(2, SyncMath.queueIndexOf(timeline, 3, queue))
        assertEquals(2, SyncMath.timelineIndexOf(timeline, queue, 1))
        assertEquals(3, SyncMath.timelineIndexOf(timeline, queue, 2))
        assertNull(SyncMath.timelineIndexOf(timeline, queue, 9))
    }

    @Test fun remaining_counts_down_to_the_boundary() {
        assertEquals(7_000L, SyncMath.remainingMs(SyncMath.Spot(0, 0L), loop))
        assertEquals(1L, SyncMath.remainingMs(SyncMath.Spot(1, 9_999L), loop))
        assertEquals(0L, SyncMath.remainingMs(SyncMath.Spot(1, 12_000L), loop))
    }
}
