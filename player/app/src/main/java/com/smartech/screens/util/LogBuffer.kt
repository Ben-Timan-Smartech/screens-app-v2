package com.smartech.screens.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

/**
 * Bounded in-memory log buffer. Anything important the player does — push
 * received, playlist refreshed, download started/finished, registration —
 * goes through here. The super-admin "Device admin" screen subscribes to
 * [entries] and shows the most recent N. Also writes through to logcat.
 *
 * Thread-safe; can be called from any coroutine.
 */
object LogBuffer {

    private const val MAX = 100

    enum class Level { D, I, W, E }

    data class Entry(
        val time: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val cause: String? = null,
    )

    private val deque: ArrayDeque<Entry> = ArrayDeque()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    /** v0.1.25: monotonic counter, incremented on every add. Used by
     *  the log-shipper to know which entries it hasn't sent yet — it
     *  remembers the cursor it last shipped through, then ships
     *  everything with `seq > cursor`. Survives clear() being called
     *  externally so the cursor remains a sane bound for "have we
     *  shipped this entry already". */
    private var seq: Long = 0L

    /** Pair of (entry, sequence-number) — sequence is per-process and
     *  monotonic. [drainSinceSeq] returns these so the shipper can
     *  remember "I sent up to N", come back later, send N+1 onwards. */
    data class StampedEntry(val seq: Long, val entry: Entry)

    fun d(tag: String, msg: String) = add(Level.D, tag, msg, null)
    fun i(tag: String, msg: String) = add(Level.I, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = add(Level.W, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = add(Level.E, tag, msg, t)

    @Synchronized
    private fun add(level: Level, tag: String, msg: String, t: Throwable?) {
        // Mirror to logcat so adb logcat still works.
        when (level) {
            Level.D -> Log.d(tag, msg)
            Level.I -> Log.i(tag, msg)
            Level.W -> if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
            Level.E -> if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        }
        seq++
        deque.addLast(Entry(System.currentTimeMillis(), level, tag, msg, t?.message))
        while (deque.size > MAX) deque.removeFirst()
        _entries.value = deque.toList().reversed() // newest first
    }

    /** v0.1.25: return entries whose sequence is greater than [sinceSeq]
     *  and whose level is at or above [minLevel]. Used by the log-
     *  shipper to upload only the warnings/errors the server hasn't
     *  seen yet. Returned list is in chronological order (oldest first)
     *  to make server-side appending straightforward.
     *
     *  Note: this looks at the in-memory deque, which is bounded to
     *  [MAX] entries. If shipping falls behind by more than MAX
     *  entries, older warnings get evicted before they're uploaded —
     *  acceptable trade-off for not having a separate on-disk spool.
     *  Run the shipper from the heartbeat loop (every 10 s) and we'll
     *  never come close. */
    @Synchronized
    fun drainSinceSeq(sinceSeq: Long, minLevel: Level): Pair<Long, List<Entry>> {
        // The deque stores entries oldest → newest; their sequence
        // numbers are seq - size + 1 .. seq.
        val newest = seq
        if (newest <= sinceSeq) return newest to emptyList()
        val skip = (sinceSeq - (newest - deque.size)).coerceAtLeast(0L).toInt()
        val out = mutableListOf<Entry>()
        // Iterate in insertion (chronological) order.
        val it = deque.iterator()
        var i = 0
        while (it.hasNext()) {
            val e = it.next()
            if (i++ < skip) continue
            if (e.level.ordinal >= minLevel.ordinal) out += e
        }
        return newest to out
    }

    /** v0.1.74: full current buffer in chronological order (oldest first).
     *  Used by the CMS "Request logs" command to upload everything the
     *  tablet currently holds — all levels, not just the W+ the heartbeat
     *  shipper sends — so an operator can pull a screen's latest logs on
     *  demand. */
    @Synchronized
    fun snapshot(): List<Entry> = deque.toList()

    @Synchronized
    fun clear() {
        deque.clear()
        _entries.value = emptyList()
    }
}
