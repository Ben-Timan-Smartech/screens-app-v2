package com.smartech.screens.player

/** v0.2.25: how a synced screen is doing, for the heartbeat. Built on the main
 *  thread by each sync tick ([PlayerController]) and read from the heartbeat
 *  loop, so it's immutable. Null outside a sync group. */
data class SyncReport(
    val anchorMs: Long,
    val itemIndex: Int,
    val itemId: String?,
    val positionMs: Long,
    /** Player minus group clock, + = ahead. Null while not measurable. */
    val driftMs: Long?,
    val speed: Float,
    /** Corrective jumps in the last 10 minutes. */
    val seeks: Int,
    val items: Int,
    /** "playing" | "waiting" (for a coordinated start) | "loading" |
     *  "holding" (paused at the group's clock) | "image" */
    val state: String,
)
