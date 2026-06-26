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
     * v0.1.73: invoked with the offending mediaId when ExoPlayer reports
     * a corrupt/truncated source file. The caller purges that video from
     * the cache and re-downloads it (a plain prepare() would just re-read
     * the same bad bytes). No-op by default.
     */
    private val onBadSource: (String) -> Unit = {},
    /**
     * v0.1.49: caller-supplied label for the currently-playing item,
     * woven into every watchdog log line so the operator can read
     * the JSONL log file (or CMS Recent activity) and see which
     * video is misbehaving. Expected to return a short human-readable
     * string ("SONOS Era 300 (sonos-3)" / "splash" / null).
     * Implementation should be cheap — called once per tick.
     */
    private val currentItemLabel: () -> String? = { null },
    /**
     * v0.1.76: the light "restart the player" recovery — re-applies the
     * cached playlist in-process. Invoked as the first rung when a screen
     * is stuck on the splash with content already downloaded. No-op by
     * default.
     */
    private val onRestartPlayer: suspend () -> Unit = {},
    /**
     * v0.1.76: true when the player *should* be showing content — the
     * server pushed a non-empty playlist and every item is cached. Used to
     * detect a screen wedged on the splash. Default false so the
     * splash-stuck recovery stays dormant unless the caller wires it.
     */
    private val shouldBePlayingContent: () -> Boolean = { false },
    /**
     * v0.1.76: true only when the player has *fallen back* to the splash
     * (pure-splash mode), NOT during mix-splash playback where the splash
     * is a deliberate queue item. The stuck-on-splash detector keys off
     * this rather than [isOnSplash] so it never fires on a normal
     * mix-splash loop. Default false so the recovery stays dormant.
     */
    private val isOnFallbackSplash: () -> Boolean = { false },
    /**
     * v0.1.80: invoked with (mediaId, reason) when an item buffers through
     * repeated kicks without ever playing — almost always a clip the device
     * can't decode (too high-res/bitrate, or an unsupported codec). The
     * caller flags it so the playlist view shows WHY, and the watchdog skips
     * past it so one bad video can't freeze the whole screen.
     */
    private val onUnplayable: (mediaId: String, reason: String) -> Unit = { _, _ -> },
    /**
     * v0.1.80: invoked with the mediaId on a healthy (READY + advancing)
     * tick, so the caller can clear a previous "unplayable" flag once the
     * item plays fine again (e.g. after it's re-encoded + re-uploaded).
     */
    private val onItemPlaying: (mediaId: String) -> Unit = {},
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** Counters tracking each signal across consecutive polls. */
    private var stallTicks = 0
    private var bufferingTicks = 0

    /** v0.1.76: consecutive ticks spent on the splash while content is
     *  actually ready to play. Drives the stuck-on-splash recovery ladder. */
    private var splashStuckTicks = 0

    /** One-shot guard so a stuck-on-splash episode triggers at most one
     *  activity reboot per app session — prevents a reboot loop if the
     *  content somehow can't play even after a fresh start. Re-armed once
     *  real content is back on screen. */
    private var splashRebootDone = false

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

    /** v0.1.80: consecutive buffering kicks per item. Drives the
     *  "skip the video this device can't decode" escalation so one
     *  undecodable clip can't freeze the whole screen. */
    private val perItemBufferKicks = mutableMapOf<String, Int>()

    /** v0.1.73: per-item count of cache-purge+re-download attempts after a
     *  bad-source error. Bounds the loop so a genuinely corrupt *source*
     *  file (bad in Drive, not just a bad local copy) can't trigger
     *  endless re-downloads. */
    private val badSourcePurges = mutableMapOf<String, Int>()

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
            // v0.1.80: new item → fresh buffering-skip budget.
            perItemBufferKicks.clear()
        }

        override fun onPlayerError(error: PlaybackException) {
            val label = currentItemLabel() ?: "(unknown item)"
            LogBuffer.w(TAG, "ExoPlayer error on '$label': ${error.errorCodeName} — ${error.message}")
            // v0.1.73: a truncated/corrupt cached MP4 throws a source IO /
            // container-parsing error. A plain prepare() re-reads the same
            // bad bytes, so purge the file and re-download a clean copy.
            // ExoPlayer dispatches this callback on the app's main thread,
            // so currentMediaItem is safe to read here. Bounded per item so
            // a file that's also broken at the source can't loop forever.
            if (error.errorCode in BAD_SOURCE_CODES && !isOnSplash()) {
                val id = player.currentMediaItem?.mediaId
                if (id != null) {
                    val n = (badSourcePurges[id] ?: 0) + 1
                    badSourcePurges[id] = n
                    if (n <= MAX_SOURCE_PURGES) {
                        LogBuffer.w(TAG, "Bad source '$label' (${error.errorCodeName}) — purge + re-download (attempt $n/$MAX_SOURCE_PURGES)")
                        runCatching { onBadSource(id) }
                            .onFailure { LogBuffer.w(TAG, "onBadSource callback failed: ${it.message}") }
                    } else {
                        LogBuffer.w(TAG, "Bad source '$label' persists after $MAX_SOURCE_PURGES re-downloads — leaving it; the file likely needs re-encoding/replacing")
                    }
                }
            }
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
        // v0.1.76: stuck-on-splash-with-content recovery. A screen can sit
        // on the bundled splash even though the server pushed content that's
        // already downloaded (e.g. the first push after setup didn't flip
        // the player off the splash). Normal stall detection never catches
        // this — the splash loops happily — so watch for it explicitly and
        // escalate: restart the player, then (once) reboot the activity.
        if (isOnFallbackSplash() && shouldBePlayingContent()) {
            splashStuckTicks++
            LogBuffer.w(TAG, "On fallback splash but content is ready to play (tick $splashStuckTicks)")
            when (splashStuckTicks) {
                SPLASH_STUCK_TICKS_BEFORE_RESTART -> {
                    LogBuffer.w(TAG, "Splash-stuck → restart player")
                    runCatching { onRestartPlayer() }
                        .onFailure { LogBuffer.w(TAG, "Restart-player callback failed: ${it.message}") }
                }
                SPLASH_STUCK_TICKS_BEFORE_REBOOT -> {
                    if (splashRebootDone) {
                        LogBuffer.w(TAG, "Splash-stuck persists after a reboot — not rebooting again; the content may be unplayable")
                    } else {
                        splashRebootDone = true
                        LogBuffer.w(TAG, "Splash-stuck after restart → rebooting activity")
                        runCatching { withContext(Dispatchers.Main) { onRestart() } }
                            .onFailure { LogBuffer.w(TAG, "Restart callback failed: ${it.message}") }
                    }
                }
            }
            return
        } else {
            splashStuckTicks = 0
            // Re-arm the one-shot reboot once we're off the fallback splash
            // (real content or a mix-splash loop), so a future stuck episode
            // can recover too.
            if (!isOnFallbackSplash()) splashRebootDone = false
        }

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

        // Buffering stuck — the player wants to play but can't fill its
        // buffer. A KICK (re-prepare) fixes a transient blip; but a clip the
        // device simply can't decode (too high-res/bitrate, or an unsupported
        // codec — and the file is local, so it isn't a network wait) buffers
        // FOREVER and kicking never helps. v0.1.80: after a couple of kicks
        // on the same item, give up and skip past it so one bad video can't
        // freeze the whole screen — and log the format so the cause is clear.
        if (snapshot.playbackState == Player.STATE_BUFFERING && snapshot.playWhenReady) {
            bufferingTicks++
            if (bufferingTicks >= BUFFERING_TICKS_BEFORE_KICK) {
                bufferingTicks = 0
                val secs = BUFFERING_TICKS_BEFORE_KICK * pollIntervalMs / 1000
                val mediaId = player.currentMediaItem?.mediaId
                val label = currentItemLabel() ?: "(unknown item)"
                val fmt = currentFormatLabel()
                val kicks = ((if (mediaId != null) perItemBufferKicks[mediaId] else null) ?: 0) + 1
                if (mediaId != null) perItemBufferKicks[mediaId] = kicks
                val canSkip = !isOnSplash() && player.mediaItemCount > 1
                if (mediaId != null && kicks >= BUFFERING_KICKS_BEFORE_SKIP && canSkip) {
                    LogBuffer.w(
                        TAG,
                        "Watchdog SKIP — '$label' ($fmt) buffered through $kicks kicks " +
                            "(~${kicks * secs}s) without playing; skipping to the next item",
                    )
                    perItemBufferKicks.remove(mediaId)
                    runCatching {
                        onUnplayable(
                            mediaId,
                            "Won't play on this screen — stuck buffering, likely too high-res/bitrate " +
                                "or an unsupported codec for this device. Format: $fmt",
                        )
                    }.onFailure { LogBuffer.w(TAG, "onUnplayable callback failed: ${it.message}") }
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                } else {
                    escalate(RecoveryLevel.KICK, "buffering for ${secs}s on '$label'")
                }
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
                    // v0.1.80: advancing normally → clear any stale
                    // "unplayable" flag for this item (e.g. after it was
                    // re-encoded and now plays fine).
                    player.currentMediaItem?.mediaId?.let { onItemPlaying(it) }
                }
            }
        } else {
            // Reset the counter whenever we're not actively playing —
            // a paused or ended state isn't a stall.
            stallTicks = 0
            lastPositionMs = Long.MIN_VALUE
        }
    }

    /** v0.1.80: compact description of the current video track — codec,
     *  resolution, frame rate, bitrate — woven into watchdog log lines and
     *  the unplayable-item reason so "4K HEVC on a legacy box" is obvious at
     *  a glance instead of needing a deep dive. Reads ExoPlayer state; only
     *  call from the Main-dispatched tick/escalate. */
    private fun currentFormatLabel(): String {
        val f = player.videoFormat ?: return "format unknown"
        val codec = (f.sampleMimeType ?: f.codecs ?: "?").substringAfterLast('/')
        val res = if (f.width > 0 && f.height > 0) "${f.width}×${f.height}" else "?"
        val fps = if (f.frameRate > 0f) " @${f.frameRate.toInt()}fps" else ""
        val mbps = if (f.bitrate > 0) " ~%.0fMbps".format(f.bitrate / 1_000_000.0) else ""
        return "$codec $res$fps$mbps"
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
        val fmt = currentFormatLabel()   // v0.1.80: log the video format on every action
        if (effective == RecoveryLevel.KICK && currentMediaId != null) {
            val count = (perItemKicks[currentMediaId] ?: 0) + 1
            perItemKicks[currentMediaId] = count
            if (count == REPEAT_OFFENDER_KICK_THRESHOLD) {
                val label = currentItemLabel() ?: currentMediaId
                LogBuffer.w(
                    TAG,
                    "Repeat-offender video: '$label' has triggered $count watchdog kicks. " +
                        "Consider removing it from the playlist or re-encoding. Format: $fmt",
                )
            }
        }

        when (effective) {
            RecoveryLevel.KICK -> {
                LogBuffer.w(TAG, "Watchdog KICK — $reason · $fmt")
                withContext(Dispatchers.Main) { player.prepare() }
            }
            RecoveryLevel.REBUILD -> {
                LogBuffer.w(TAG, "Watchdog REBUILD — $reason · $fmt")
                runCatching { onKick() }
                    .onFailure { LogBuffer.w(TAG, "Rebuild callback failed: ${it.message}") }
            }
            RecoveryLevel.RESTART -> {
                LogBuffer.w(TAG, "Watchdog RESTART — $reason · $fmt")
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

        /** v0.1.80: buffering kicks on the SAME item before we give up and
         *  skip it. 2 kicks ≈ 4 min of dead-air buffering — long enough to
         *  rule out a transient blip, short enough not to leave a sign frozen.
         *  A local file that buffers this long is one the device can't
         *  decode, so re-kicking it again would never help. */
        private const val BUFFERING_KICKS_BEFORE_SKIP = 2

        /** v0.1.76: ticks spent on the splash (with content ready) before
         *  each stuck-on-splash recovery rung. ~60 s then ~120 s at the
         *  30 s poll — long enough that a download finishing + the normal
         *  re-publish gets its chance first. */
        private const val SPLASH_STUCK_TICKS_BEFORE_RESTART = 2
        private const val SPLASH_STUCK_TICKS_BEFORE_REBOOT = 4

        /** v0.1.49: when the same video triggers this many kicks across
         *  loops, surface a distinct "repeat offender" log line. The
         *  watchdog doesn't auto-remove anything — that's the operator's
         *  call after they see the log. */
        private const val REPEAT_OFFENDER_KICK_THRESHOLD = 3

        /** v0.1.73: error codes that mean "the bytes on disk are bad" —
         *  a truncated download or a malformed/partial MP4 container.
         *  These warrant purging + re-downloading rather than a prepare()
         *  that just re-reads the same corrupt file. */
        private val BAD_SOURCE_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        )

        /** Cap purge+re-download attempts per video per app session, so a
         *  file that's corrupt at the *source* (not just locally) can't
         *  spin in an endless re-download loop. */
        private const val MAX_SOURCE_PURGES = 2
    }
}
