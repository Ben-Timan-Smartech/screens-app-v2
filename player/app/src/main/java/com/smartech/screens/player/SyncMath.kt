package com.smartech.screens.player

/**
 * v0.2.25: the loop maths for sync groups, with no Android types so it can be
 * unit tested (see SyncMathTest). Everything is in milliseconds.
 *
 * A group plays one playlist on one clock. `elapsedMs` is server time since
 * the loop started; the playlist repeats forever, so position in the loop is
 * `elapsed mod total`.
 */
object SyncMath {

    /** Where in the playlist the group should be. */
    data class Spot(val index: Int, val positionMs: Long)

    private fun mod(x: Long, n: Long): Long = ((x % n) + n) % n

    /** The item and position [elapsedMs] lands on. Null for an empty or
     *  zero-length playlist. Negative elapsed counts as 0. */
    fun locate(elapsedMs: Long, durationsMs: List<Long>): Spot? {
        if (durationsMs.isEmpty()) return null
        val total = durationsMs.sum()
        if (total <= 0L) return null
        val offset = mod(elapsedMs.coerceAtLeast(0L), total)
        var acc = 0L
        for (i in durationsMs.indices) {
            val d = durationsMs[i]
            if (offset < acc + d) return Spot(i, offset - acc)
            acc += d
        }
        return Spot(durationsMs.size - 1, (durationsMs.last() - 1L).coerceAtLeast(0L))
    }

    /** [deltaMs] as the shortest signed distance around a loop of [totalMs]:
     *  the result is in (-total/2, total/2]. Measuring the long way round is
     *  what made a screen that was slightly ahead at the loop's start read as
     *  a whole loop behind, and jump back to the last video. */
    fun wrapSigned(deltaMs: Long, totalMs: Long): Long {
        if (totalMs <= 0L) return 0L
        val d = mod(deltaMs, totalMs)
        return if (d > totalMs / 2) d - totalMs else d
    }

    /** How far the player is ahead (+) or behind (-) the group clock, going
     *  the short way round the loop. */
    fun gapMs(index: Int, positionMs: Long, durationsMs: List<Long>, elapsedMs: Long): Long {
        if (index !in durationsMs.indices) return 0L
        val total = durationsMs.sum()
        val before = durationsMs.subList(0, index).sum()
        val here = before + positionMs.coerceIn(0L, durationsMs[index])
        return wrapSigned(here - elapsedMs.coerceAtLeast(0L), total)
    }

    /** Time from now until the item [spot] is on ends, by the group clock. */
    fun remainingMs(spot: Spot, durationsMs: List<Long>): Long =
        (durationsMs.getOrElse(spot.index) { 0L } - spot.positionMs).coerceAtLeast(0L)

    /** Gaps bigger than this aren't nudged; the caller jumps instead. */
    const val JUMP_MS = 800L
    private const val START_MS = 30L
    private const val STOP_MS = 10L

    /**
     * The playback speed that closes a gap of [gapMs]. [correcting] is whether
     * a correction is already running: start at [START_MS], stop under
     * [STOP_MS], so the speed doesn't flick on and off around one threshold.
     * A bigger gap gets a bigger nudge, from 1% up to 4% (too small to see on
     * muted video), so a 100 ms gap closes in two to three seconds.
     */
    fun speedFor(gapMs: Long, correcting: Boolean): Float {
        val abs = kotlin.math.abs(gapMs)
        if (abs <= (if (correcting) STOP_MS else START_MS)) return 1f
        val delta = (abs / 2000f).coerceIn(0.01f, 0.04f)
        return if (gapMs > 0L) 1f - delta else 1f + delta
    }

    /** k-th occurrence of an id among the playable queue for the k-th
     *  occurrence in the group's full playlist: maps a playlist position to a
     *  queue position even when a video appears twice, or some are missing
     *  from this screen. Returns null if this screen doesn't have it. */
    fun queueIndexOf(timelineIds: List<String>, timelineIndex: Int, queueIds: List<String>): Int? {
        if (timelineIndex !in timelineIds.indices) return null
        val id = timelineIds[timelineIndex]
        val nth = timelineIds.subList(0, timelineIndex).count { it == id }
        var seen = 0
        for (i in queueIds.indices) {
            if (queueIds[i] == id) {
                if (seen == nth) return i
                seen++
            }
        }
        return null
    }

    /** Inverse of [queueIndexOf]: the playlist position a queue item plays. */
    fun timelineIndexOf(timelineIds: List<String>, queueIds: List<String>, queueIndex: Int): Int? {
        if (queueIndex !in queueIds.indices) return null
        val id = queueIds[queueIndex]
        val nth = queueIds.subList(0, queueIndex).count { it == id }
        var seen = 0
        for (i in timelineIds.indices) {
            if (timelineIds[i] == id) {
                if (seen == nth) return i
                seen++
            }
        }
        return null
    }
}
