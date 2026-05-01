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
        deque.addLast(Entry(System.currentTimeMillis(), level, tag, msg, t?.message))
        while (deque.size > MAX) deque.removeFirst()
        _entries.value = deque.toList().reversed() // newest first
    }

    @Synchronized
    fun clear() {
        deque.clear()
        _entries.value = emptyList()
    }
}
