package com.smartech.screens.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.smartech.screens.util.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backstop for the 1-in-1000 case where ExoPlayer freezes mid-loop —
 * stuck buffering on a flaky network, a corrupted cached MP4, a decoder
 * stall on a cheap Android TV box — and there's nobody around to
 * physically reboot the tablet.
 *
 * Three signals get watched every [pollIntervalMs]:
 *
 *  1. **Position has stopped advancing while we should be playing.**
 *     If `isPlaying` was reported true and `currentPosition` is within
 *     [POSITION_STALL_TOLERANCE_MS] of the prior poll, we count it as
 *     a stall tick. [STALL_TICKS_BEFORE_KICK] consecutive stalls →
 *     escalate.
 *  2. **Player has been in `STATE_BUFFERING` for too long.** Normal
 *     buffering resolves in seconds. Two minutes of continuous
 *     buffering means the network died mid-segment and OkHttp's retry
 *     loop is spinning to no effect.
 *  3. **An [onPlayerError] fired we never recovered from.** The
 *     listener flips a flag; if the next poll sees it still set, we
 *     escalate.
 *
 * Recovery ladder when any signal fires:
 *
 *   • **Kick (cheap):** [Player.prepare]. Re-resolves the current
 *     media item — fixes most transient stalls (network blip, brief
 *     decoder hiccup) without disturbing the queue or wall-clock
 *     playback position.
 *   • **Rebuild (medium):** [onKick] callback — caller passes a
 *     lambda that re-publishes the current playlist (e.g.
 *     `repository.refreshPlaylist()`). Useful when the queue itself
 *     is in a weird state.
 *   • **Restart (nuclear):** [onRestart] callback — caller passes
 *     `repository.scheduleSelfRestart`, which kills the activity and
 *     reopens it. Known-good recovery path.
 *
 * Splash is treated specially: when it's the current item the
 * watchdog still runs (splash can stall too), but the recovery is
 * limited to the kick — we don't want to rebuild the queue or
 * restart the activity just because the bundled splash hiccuped.
 */
@UnstableApi
class PlaybackWatchdog(
    private val player: ExoPlayer,
    private val onKick: suspend () -> Unit,
    private val onRestart: () -> Unit,
    private val isOnSplash: () -> Boolean = { false },
    /**
     * v0.1.49: caller-supplied label for the currently-playing item,
     * woven into every watchdog log line so the operator can read
     * the JSONL log file (or CMS Recent activity) and see which
     * video is misbehaving. Expected to return a short human-readable
     * string ("SONOS Era 300 (sonos-3)" / "splash" / null).
     * Implementation should be cheap — called once per tick.
     */
    private val currentItemLabel: () -> String? = { null },
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** Counters tracking each signal across consecutive polls. */
    private var stallTicks = 0
    private var bufferingTicks = 0

    /** True when [onPlayerError] fired since the last successful poll.
     *  Cleared by either a successful tick or a recovery escalation. */
    @Volatile
    private var pendingError = false

    /** Snapshot from the previous tick — [Long.MIN_VALUE] sentinel
     *  means "no prior reading", which suppresses the first tick's
     *  stall detection so we never act on a single sample. */
    private var lastPositionMs: Long = Long.MIN_VALUE

    /**
     * v0.1.49: per-item kick counter. Keyed by the mediaId of the
     * currently-playing item. When the same video racks up multiple
     * watchdog kicks across loops, it gets a distinct log line so
     * the operator can spot "video X is the problem" vs "the whole
     * system is struggling." Cleared on [Player.Listener.onMediaItemTransition].
     */
    private val perItemKicks = mutableMapOf<String, Int>()

    /** Hop the consecutive-stall counter doesn't trip on a real
     *  loop boundary (REPEAT_MODE_ALL resets position to 0 between
     *  items). Cleared on every [Player.Listener.onMediaItemTransition]. */
    private val transitionListener = object : Player.Listener {
        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int,
        ) {
            // Forget last position; the next tick will re-sample from
            // a fresh baseline. Without this, the rollover from end of
            // item N to start of item N+1 looks identical to a stall.
            lastPositionMs = Long.MIN_VALUE
            stallTicks = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            val label = currentItemLabel() ?: "(unknown item)"
            LogBuffer.w(TAG, "ExoPlayer error on '$label': ${error.errorCodeName} — ${error.message}")
            pendingError = true
        }
    }

    fun start() {
        if (job?.isActive == true) return
        player.addListener(transitionListener)
        job = scope.launch {
            // First tick after a brief warmup so the first sample isn't
            // taken from a still-initialising player.
            delay(WARMUP_MS)
            while (isActive) {
                runCatching { tick() }
                    .onFailure { LogBuffer.w(TAG, "Watchdog tick failed: ${it.message}", it) }
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        player.removeListener(transitionListener)
        lastPositionMs = Long.MIN_VALUE
        stallTicks = 0
        bufferingTicks = 0
        pendingError = false
    }

    private suspend fun tick() {
        // Read ExoPlayer state on the main thread (it's not thread-safe).
        val snapshot = withContext(Dispatchers.Main) {
            Snapshot(
                playbackState = player.playbackState,
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady,
                positionMs = player.currentPosition,
            )
        }

        // Surface error first — it indicates a real failure, not a
        // performance hiccup.
        if (pendingError) {
            LogBuffer.w(TAG, "Recovering from unhandled player error")
            pendingError = false
            escalate(level = RecoveryLevel.KICK, reason = "player error")
            return
        }

        // Buffering stuck.
        if (snapshot.playbackState == Player.STATE_BUFFERING && snapshot.playWhenReady) {
            bufferingTicks++
            if (bufferingTicks >= BUFFERING_TICKS_BEFORE_KICK) {
                bufferingTicks = 0
                escalate(level = RecoveryLevel.KICK, reason = "buffering for ${BUFFERING_TICKS_BEFORE_KICK * pollIntervalMs / 1000}s")
                return
            }
        } else {
            bufferingTicks = 0
        }

        // Position-stall detection only runs while READY + playing.
        if (snapshot.playbackState == Player.STATE_READY && snapshot.isPlaying) {
            val baseline = lastPositionMs
            lastPositionMs = snapshot.positionMs
            if (baseline != Long.MIN_VALUE) {
                val advanced = snapshot.positionMs - baseline
                // Allow a small tolerance for the case where the loop
                // wrapped around between samples (unlikely with a 30s
                // poll on multi-second clips, but possible).
                val looksStalled = kotlin.math.abs(advanced) < POSITION_STALL_TOLERANCE_MS
                if (looksStalled) {
                    stallTicks++
                    val label = currentItemLabel() ?: "(unknown item)"
                    LogBuffer.w(
                        TAG,
                        "Position stalled at ${snapshot.positionMs}ms on '$label' " +
                            "(tick $stallTicks/$STALL_TICKS_BEFORE_KICK)",
                    )
                    when (stallTicks) {
                        STALL_TICKS_BEFORE_KICK -> {
                            escalate(RecoveryLevel.KICK, "position stuck on '$label'")
                        }
                        STALL_TICKS_BEFORE_REBUILD -> {
                            escalate(RecoveryLevel.REBUILD, "position still stuck on '$label' after kick")
                        }
                        STALL_TICKS_BEFORE_RESTART -> {
                            escalate(RecoveryLevel.RESTART, "position still stuck on '$label' after rebuild")
                        }
                    }
                } else {
                    stallTicks = 0
                }
            }
        } else {
            // Reset the counter whenever we're not actively playing —
            // a paused or ended state isn't a stall.
            stallTicks = 0
            lastPositionMs = Long.MIN_VALUE
        }
    }

    private suspend fun escalate(level: RecoveryLevel, reason: String) {
        // Splash never escalates past KICK. Restarting the activity
        // just because the bundled splash hiccuped would be silly
        // (the player will re-show it on relaunch anyway).
        val effective = if (isOnSplash() && level != RecoveryLevel.KICK) {
            LogBuffer.i(TAG, "On splash — downgrading $level to KICK ($reason)")
            RecoveryLevel.KICK
        } else level

        // v0.1.49: per-item kick counter. If the same item triggers
        // KICK multiple times in a row, log a distinctive "repeat
        // offender" line so the operator can search the JSONL log
        // for that and find the bad video quickly. We don't take
        // automated action — a video that genuinely needs the
        // intervention might be a stale CDN entry that fixes itself
        // on the next refresh — but the log line is the breadcrumb
        // they need.
        val currentMediaId = withContext(Dispatchers.Main) { player.currentMediaItem?.mediaId }
        if (effective == RecoveryLevel.KICK && currentMediaId != null) {
            val count = (perItemKicks[currentMediaId] ?: 0) + 1
            perItemKicks[currentMediaId] = count
            if (count == REPEAT_OFFENDER_KICK_THRESHOLD) {
                val label = currentItemLabel() ?: currentMediaId
                LogBuffer.w(
                    TAG,
                    "Repeat-offender video: '$label' has triggered $count watchdog kicks. " +
                        "Consider removing it from the playlist or re-encoding.",
                )
            }
        }

        when (effective) {
            RecoveryLevel.KICK -> {
                LogBuffer.w(TAG, "Watchdog KICK — $reason")
                withContext(Dispatchers.Main) { player.prepare() }
            }
            RecoveryLevel.REBUILD -> {
                LogBuffer.w(TAG, "Watchdog REBUILD — $reason")
                runCatching { onKick() }
                    .onFailure { LogBuffer.w(TAG, "Rebuild callback failed: ${it.message}") }
            }
            RecoveryLevel.RESTART -> {
                LogBuffer.w(TAG, "Watchdog RESTART — $reason")
                stallTicks = 0
                runCatching {
                    withContext(Dispatchers.Main) { onRestart() }
                }.onFailure { LogBuffer.w(TAG, "Restart callback failed: ${it.message}") }
            }
        }
    }

    private data class Snapshot(
        val playbackState: Int,
        val isPlaying: Boolean,
        val playWhenReady: Boolean,
        val positionMs: Long,
    )

    private enum class RecoveryLevel { KICK, REBUILD, RESTART }

    companion object {
        private const val TAG = "PlaybackWatchdog"

        /** 30 s — frequent enough to catch a stall before it ruins the
         *  customer's view, rare enough to add zero meaningful CPU. */
        const val DEFAULT_POLL_INTERVAL_MS = 30_000L

        /** Don't take the first sample until ExoPlayer's had a moment
         *  to actually start playing the first item. */
        private const val WARMUP_MS = 10_000L

        /** Position-delta below this between two polls is "stuck". 1 s
         *  is more than enough — a real playback advances by ~30 s
         *  between 30-second polls. */
        private const val POSITION_STALL_TOLERANCE_MS = 1_000L

        /** How many consecutive stuck polls before each escalation
         *  level fires. Each tier waits one extra poll cycle so the
         *  cheaper recovery has a chance to actually work first. */
        private const val STALL_TICKS_BEFORE_KICK = 2     // ~60 s
        private const val STALL_TICKS_BEFORE_REBUILD = 4  // ~120 s
        private const val STALL_TICKS_BEFORE_RESTART = 6  // ~180 s

        /** STATE_BUFFERING longer than 4 polls (~2 min) → cheap kick. */
        private const val BUFFERING_TICKS_BEFORE_KICK = 4

        /** v0.1.49: when the same video triggers this many kicks across
         *  loops, surface a distinct "repeat offender" log line. The
         *  watchdog doesn't auto-remove anything — that's the operator's
         *  call after they see the log. */
        private const val REPEAT_OFFENDER_KICK_THRESHOLD = 3
    }
}
