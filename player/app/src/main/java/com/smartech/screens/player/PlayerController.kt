package com.smartech.screens.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.smartech.screens.R
import com.smartech.screens.data.PlayerRepository
import com.smartech.screens.util.LogBuffer
import kotlin.math.abs

/**
 * Thin wrapper around [ExoPlayer] that plays a list of locally-cached files on
 * an infinite loop. Intentionally simple — no seekbars, no UI events, no
 * analytics. The Compose surface just binds this to a `PlayerView`.
 *
 * On first boot (or whenever the cache has no playable content) we loop a
 * bundled splash video baked into the APK, so the screen always shows
 * something on-brand instead of a placeholder string.
 */
@UnstableApi
class PlayerController(context: Context) {

    private val bundledSplashUri: Uri =
        Uri.parse("android.resource://${context.packageName}/${R.raw.splash}")
    /** Remote splash overrides the bundled one when set. */
    private var remoteSplashUri: Uri? = null
    private val splashUri: Uri get() = remoteSplashUri ?: bundledSplashUri

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
            // Default to silent — the global audioOn flag flips it on,
            // and per-video defaultUnmute (set in the Content Library)
            // overrides the silent default on individual items.
            volume = 0f
        }

    /** Global "unmute everything" flag pushed from the server/staff overlay. */
    private var audioOn: Boolean = false
    /** Per-item override: id → defaultUnmute. Populated whenever a playlist
     *  is applied; consulted on every item transition. The bundled splash
     *  is never in this map and therefore always plays at the audioOn
     *  volume (muted by default). */
    private val defaultUnmuteById = mutableMapOf<String, Boolean>()

    /** v0.1.12 client-side sync state. When non-null, the player snaps to
     *  the group-correct item on every queue transition; the math runs
     *  locally instead of every poll asking the server "where should I
     *  be?". See [applyGroupSync]. */
    private data class GroupSyncState(
        val loopStartedAtMs: Long,
        val serverOffsetMs: Long,
        /** Item ids in order, matching the current queue (skipping splash
         *  if mix-splash is on — but mix-splash is forced off in sync
         *  groups, see serve.py). */
        val itemIds: List<String>,
        /** Per-item duration in ms, parallel to [itemIds]. */
        val itemDurationsMs: List<Long>,
        /** Sum of durations. Cached to avoid recomputing on every
         *  transition. */
        val totalDurationMs: Long,
    )

    @Volatile
    private var groupSync: GroupSyncState? = null

    /** v0.1.15: Pending coordinated-start resume.
     *
     *  When the server reset the group's loop epoch to a moment in the
     *  near future (see `COORDINATED_START_DELAY_SEC` in serve.py), the
     *  tablet seeks ExoPlayer to (item 0, position 0), pauses, and
     *  schedules this Runnable to fire `play()` at the exact wall-clock
     *  instant. Every member of the group does the same thing — so when
     *  the epoch hits, every prepared tablet resumes simultaneously
     *  from frame 0.
     *
     *  Stored so we can cancel it if a new anchor arrives (e.g. another
     *  rev bump during the wait). Always touched on the main thread. */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingCoordinatedStart: Runnable? = null

    /**
     * v0.1.17: in-flight playback-rate adjustment for sync drift.
     *
     * Even with the v0.1.13 NTP clock-sync working perfectly (clocks
     * agree to within ~10 ms — confirmed by the calibration overlay),
     * two TX3-class boxes playing the same H.264 file drift past each
     * other at ~0.5-1% over real-time because their hardware decoders
     * pace differently. Over a 15-second video that's 75-150 ms of
     * drift — easily visible side-by-side, and accumulating across
     * loop iterations.
     *
     * The v0.1.12 snap-at-transition model can't catch this: snaps
     * only fire at item boundaries, and between boundaries drift
     * runs free. By the time the next transition arrives, the two
     * tablets may have crossed an item boundary at different moments
     * and end up on different items entirely.
     *
     * Fix: sample the actual-vs-expected position every 500 ms while
     * in group sync. Tiny drifts (< 50 ms) we leave alone. Mid-range
     * drifts (50 ms – 2 s) we nudge with `setPlaybackParameters(speed)`
     * — invisible to the eye, no buffer flash, no audio pitch
     * artefact for the muted-by-default case. Anything > 2 s falls
     * back to a real seek (rare — happens when transition timing
     * diverges by more than an item's worth of jitter).
     *
     * The seek-then-flash path that v0.1.11 used at a 3 s threshold
     * is preserved only as a last resort because rate-control alone
     * can't close a multi-second gap fast enough to be useful.
     */
    private var driftCorrectionTickScheduled: Boolean = false
    private var currentPlaybackSpeed: Float = 1.0f
    private val driftTick: Runnable = object : Runnable {
        override fun run() {
            driftCorrectionTickScheduled = false
            try {
                correctDriftInCurrentItem()
            } finally {
                // Re-arm only while we're in group sync. The
                // applyGroupSync path schedules the first tick on
                // anchor acquisition; releasing clears the flag and
                // the loop dies. Polling at 500 ms gives us 2 Hz
                // correction — fast enough to keep cumulative drift
                // under ~200 ms even on a sloppy decoder.
                if (groupSync != null) {
                    driftCorrectionTickScheduled = true
                    mainHandler.postDelayed(this, 500L)
                }
            }
        }
    }

    private fun scheduleDriftTickIfNeeded() {
        if (driftCorrectionTickScheduled) return
        if (groupSync == null) return
        driftCorrectionTickScheduled = true
        mainHandler.postDelayed(driftTick, 500L)
    }

    init {
        // Re-apply volume whenever the player moves to the next item in the
        // queue. Without this listener, the first MediaItem's volume sticks
        // for the whole loop. The same listener also handles sync snap —
        // see [snapToGroupExpectedItem] — so group corrections only happen
        // at natural item boundaries where ExoPlayer is already changing
        // items (i.e. visible jumps become invisible).
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applyVolumeForCurrentItem()
                snapToGroupExpectedItem()
                // Reset the playback rate at every transition. Drift
                // accumulated on the previous item could have left the
                // speed at 1.03/0.97 from a recent nudge; without this
                // reset the new item would start mid-correction.
                if (currentPlaybackSpeed != 1.0f) {
                    currentPlaybackSpeed = 1.0f
                    player.playbackParameters = PlaybackParameters(1.0f)
                }
            }
        })
    }

    /** Flip the global audioOn flag and re-apply volume immediately. */
    fun setAudioOn(value: Boolean) {
        if (audioOn == value) return
        audioOn = value
        LogBuffer.i("PlayerController", "Audio → ${if (value) "on" else "off"}")
        applyVolumeForCurrentItem()
    }

    private fun applyVolumeForCurrentItem() {
        val id = player.currentMediaItem?.mediaId
        val perItem = id?.let { defaultUnmuteById[it] } ?: false
        val unmute = audioOn || perItem
        val target = if (unmute) 1f else 0f
        if (player.volume != target) player.volume = target
    }

    /** Swap in a downloaded splash; null reverts to the bundled one. */
    fun setRemoteSplash(file: java.io.File?) {
        val newUri = file?.let { Uri.fromFile(it) }
        if (newUri == remoteSplashUri) return
        remoteSplashUri = newUri
        // If we're currently showing the splash, re-apply with the new file.
        if (currentSource == Source.SPLASH) {
            currentSource = ""    // force re-apply
            playSplash()
        }
    }

    /** Distinct sentinels so a real playlist with the literal string "splash" can't collide. */
    private object Source {
        const val NONE = ""
        const val SPLASH = "__splash__"
    }

    private var currentSource: String = Source.NONE

    init {
        // Show the bundled splash immediately on launch — before the first
        // playlist arrives. As soon as `apply()` is called with real items it
        // takes over.
        playSplash()
    }

    /** Plays the APK-bundled splash on loop. Idempotent. */
    fun playSplash() {
        if (currentSource == Source.SPLASH) return
        currentSource = Source.SPLASH
        player.setMediaItem(MediaItem.fromUri(splashUri))
        player.prepare()
        player.play()
    }

    /**
     * Replace the queue if the playlist revision changed. When [mixSplash] is
     * true, the bundled splash is prepended so it plays first before the
     * pushed content; the loop then cycles splash → item 1 → item 2 → … →
     * splash again. When false, only the pushed items loop.
     */
    fun apply(
        playlist: List<PlayerRepository.LocalVideo>,
        revision: String,
        mixSplash: Boolean = false,
    ) {
        if (playlist.isEmpty()) {
            playSplash()
            return
        }
        // Encode mixSplash into the source key so toggling triggers a re-apply.
        val sourceKey = "$revision|splash=$mixSplash"
        val expectedCount = playlist.size + (if (mixSplash) 1 else 0)
        if (sourceKey == currentSource && player.mediaItemCount == expectedCount) return
        currentSource = sourceKey

        val items = mutableListOf<MediaItem>()
        if (mixSplash) {
            items += MediaItem.Builder().setMediaId(Source.SPLASH).setUri(splashUri).build()
        }
        items += playlist.map { lv ->
            MediaItem.Builder()
                .setMediaId(lv.item.id)
                .setUri(Uri.fromFile(lv.file))
                .build()
        }
        // Refresh the per-item defaultUnmute map for volume decisions.
        defaultUnmuteById.clear()
        for (lv in playlist) {
            if (lv.item.defaultUnmute) defaultUnmuteById[lv.item.id] = true
        }
        player.setMediaItems(items, /* resetPosition = */ true)
        player.prepare()
        player.play()
        // onMediaItemTransition fires asynchronously; apply now so the very
        // first item doesn't briefly play at the previous loop's volume.
        applyVolumeForCurrentItem()
    }

    /**
     * Apply (or clear) a group-sync anchor.
     *
     * New in v0.1.12: the tablet now does ALL "which item should I be
     * on?" math locally using just `loopStartedAtMs` + the item
     * durations it already has. Sync correction happens in
     * [snapToGroupExpectedItem], which is only called at media-item
     * transitions — so the corrective seek is invisible (ExoPlayer is
     * already changing items at exactly that moment).
     *
     * Pass `null` to clear: the screen is no longer in a sync group
     * and should play through its queue without correction.
     */
    fun applyGroupSync(
        loopStartedAtMs: Long?,
        serverOffsetMs: Long,
        itemIds: List<String>,
        itemDurationsMs: List<Long>,
    ) {
        if (loopStartedAtMs == null || itemIds.isEmpty()) {
            if (groupSync != null) LogBuffer.i("PlayerController", "Group sync cleared")
            groupSync = null
            // Restore normal playback speed; the drift loop dies on
            // its next tick (it checks groupSync != null to re-arm).
            if (currentPlaybackSpeed != 1.0f) {
                currentPlaybackSpeed = 1.0f
                player.playbackParameters = PlaybackParameters(1.0f)
            }
            return
        }
        val total = itemDurationsMs.sum().coerceAtLeast(1L)
        val next = GroupSyncState(
            loopStartedAtMs = loopStartedAtMs,
            serverOffsetMs = serverOffsetMs,
            itemIds = itemIds,
            itemDurationsMs = itemDurationsMs,
            totalDurationMs = total,
        )
        val prev = groupSync
        groupSync = next
        // First time we got an anchor, or the loop epoch changed (new
        // playlist push, group reset) — snap immediately rather than
        // waiting for the next natural transition. This is the only
        // time we issue a mid-item seek; from here on, snapping
        // happens at queue boundaries only.
        if (prev == null || prev.loopStartedAtMs != next.loopStartedAtMs) {
            LogBuffer.i(
                "PlayerController",
                "Group sync acquired — loopStartedAtMs=$loopStartedAtMs, " +
                    "${itemIds.size} items, total=${total}ms",
            )
            // Cancel any pending coordinated-start runnable from a prior
            // anchor — a new epoch supersedes the old wait.
            pendingCoordinatedStart?.let { mainHandler.removeCallbacks(it) }
            pendingCoordinatedStart = null

            // Is the epoch in the (near) future? If so, this is a
            // coordinated-start signal from the server: seek to item 0
            // position 0, pause, and resume at the exact wall-clock
            // instant. Every tablet in the group does the same thing,
            // so when wall-clock catches up to loopStartedAtMs they all
            // resume from frame 0 simultaneously — no staircase based
            // on who polled first.
            val serverNowMs = System.currentTimeMillis() + serverOffsetMs
            val msUntilStart = loopStartedAtMs - serverNowMs
            // Guard band: 60 s. Anything further out is almost certainly
            // a clock-skew anomaly rather than an intentional coordinated
            // start; fall through to the normal snap path, which clamps
            // elapsed to 0 and lands on item 0 anyway.
            if (msUntilStart in 1L..60_000L) {
                LogBuffer.i(
                    "PlayerController",
                    "Coordinated start in ${msUntilStart}ms — pausing on item 0",
                )
                // Seek to (item 0, position 0). The first item in the
                // queue may be splash (mix-splash off in groups, but
                // defensive) — skip past it to the first real item.
                val target = (0 until player.mediaItemCount).firstOrNull { i ->
                    player.getMediaItemAt(i).mediaId != Source.SPLASH
                } ?: 0
                player.seekTo(target, 0L)
                player.playWhenReady = false
                val resume = Runnable {
                    LogBuffer.i("PlayerController", "Coordinated start firing")
                    player.playWhenReady = true
                    pendingCoordinatedStart = null
                    scheduleDriftTickIfNeeded()
                }
                pendingCoordinatedStart = resume
                mainHandler.postDelayed(resume, msUntilStart)
            } else {
                snapToGroupExpectedItem(force = true)
            }
        }
        // Every time we receive an anchor — coordinated-start or
        // already-running — make sure the drift correction loop is
        // ticking. Idempotent; the scheduler is a no-op when a tick
        // is already in flight.
        scheduleDriftTickIfNeeded()
    }

    /**
     * Pulled from [driftTick]. Compares the player's actual position
     * within the current item against the math-expected position
     * (same formula as [snapToGroupExpectedItem]) and decides how to
     * close the gap:
     *   • drift < 50 ms        → leave the speed alone (or restore 1.0×)
     *   • drift 50 ms – 2 s    → nudge speed to 1.03× / 0.97×
     *   • drift > 2 s          → real seek (last-resort path)
     */
    private fun correctDriftInCurrentItem() {
        val state = groupSync ?: return
        // Don't mid-item correct while a coordinated-start pause is
        // armed — that branch is doing its own scheduled work.
        if (pendingCoordinatedStart != null) return
        val currentId = player.currentMediaItem?.mediaId ?: return
        if (currentId == Source.SPLASH) return
        // Player must be in a position we can actually compare. STATE_READY
        // is the only state where `currentPosition` is trustworthy.
        if (player.playbackState != Player.STATE_READY) return
        if (!player.playWhenReady) return

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0 || currentIndex >= state.itemIds.size) return
        // Defensive: queue may include splash at index 0 if mix-splash
        // is somehow on; the math operates on the splash-less itemIds
        // list. Find this item's position in itemIds by id.
        val itemIdx = state.itemIds.indexOf(currentId)
        if (itemIdx < 0) return

        val serverNowMs = System.currentTimeMillis() + state.serverOffsetMs
        val elapsed = (serverNowMs - state.loopStartedAtMs).coerceAtLeast(0L)
        val offset = elapsed % state.totalDurationMs

        // Expected position within the loop, then narrow to this item.
        var cumulative = 0L
        var expectedIdx = -1
        var expectedPos = 0L
        for (i in state.itemDurationsMs.indices) {
            val d = state.itemDurationsMs[i]
            if (offset < cumulative + d) {
                expectedIdx = i
                expectedPos = offset - cumulative
                break
            }
            cumulative += d
        }
        if (expectedIdx < 0) return

        // If we're on the wrong item, let the snap-at-transition path
        // handle it on the next natural boundary (or last-resort seek).
        if (expectedIdx != itemIdx) {
            // Big drift — only seek if we're more than ~1 s past the
            // wrong-item boundary. Otherwise we're a few hundred ms
            // either side of a transition and the next onMediaItemTransition
            // will pull us into line.
            val itemDur = state.itemDurationsMs[itemIdx]
            val howFarOff = if (expectedIdx > itemIdx) {
                // Expected later in loop — we're behind. Distance is
                // remainder of this item + cumulative of items between.
                var d = itemDur - player.currentPosition
                for (j in (itemIdx + 1) until expectedIdx) d += state.itemDurationsMs[j]
                d += expectedPos
                d
            } else {
                // Expected earlier — we're ahead (or loop wrapped).
                // Don't try to "seek backward" mid-item; the next
                // natural wrap will fix it.
                Long.MAX_VALUE
            }
            if (howFarOff in 0L..2000L) {
                // Within rate-control range — speed up so the boundary
                // catches up. Skip the seek.
                setPlaybackSpeedNudge(+1)
                return
            }
            // Real divergence: snap. Restore speed.
            setPlaybackSpeedNudge(0)
            LogBuffer.i(
                "PlayerController",
                "Drift seek → item ${state.itemIds[expectedIdx]} at ${expectedPos}ms (wrong item)",
            )
            // Map expectedIdx (itemIds space) back to queue index.
            val queueIdx = (0 until player.mediaItemCount)
                .firstOrNull { i -> player.getMediaItemAt(i).mediaId == state.itemIds[expectedIdx] }
                ?: return
            player.seekTo(queueIdx, expectedPos)
            return
        }

        // Right item — close the gap with rate control.
        val drift = player.currentPosition - expectedPos
        when {
            abs(drift) < 50L -> setPlaybackSpeedNudge(0)
            abs(drift) < 2000L -> setPlaybackSpeedNudge(if (drift > 0) -1 else +1)
            else -> {
                // > 2 s: rate-control would take too long. Seek.
                setPlaybackSpeedNudge(0)
                LogBuffer.i(
                    "PlayerController",
                    "Drift seek → ${expectedPos}ms (was ${player.currentPosition}ms, drift=${drift}ms)",
                )
                player.seekTo(player.currentMediaItemIndex, expectedPos)
            }
        }
    }

    /**
     * `direction` is -1 (slow down), 0 (run at real-time), or +1 (speed up).
     * Maps to 0.97× / 1.00× / 1.03×. The 3% nudge closes a 50–2000 ms
     * gap in 1.6 s – 67 s respectively, which fits comfortably inside
     * a single 15 s item — so the next transition arrives with the
     * tablet back in sync. Wider rates (e.g. 1.10×) get noticeably
     * pitchy in audio and look jerky in video; 1.03× is invisible.
     */
    private fun setPlaybackSpeedNudge(direction: Int) {
        val target = when {
            direction > 0 -> 1.03f
            direction < 0 -> 0.97f
            else -> 1.0f
        }
        if (currentPlaybackSpeed == target) return
        currentPlaybackSpeed = target
        player.playbackParameters = PlaybackParameters(target)
    }

    /**
     * If we're in a sync group, jump to the item the group should be
     * on right now. Called from [Player.Listener.onMediaItemTransition]
     * — i.e. only at natural item boundaries, where ExoPlayer is
     * already swapping items and a seek is invisible. The one
     * exception is when [applyGroupSync] receives a new epoch, which
     * forces an immediate snap (see `force=true`).
     *
     * Splash items in a mix-splash queue aren't part of the group
     * loop, so the snap is skipped while splash is the current item
     * — the next real-item transition will land us on the correct
     * group position. (Mix-splash is forced off in groups by the
     * server anyway, but defensive check stays.)
     */
    private fun snapToGroupExpectedItem(force: Boolean = false) {
        val state = groupSync ?: return
        val currentId = player.currentMediaItem?.mediaId ?: return
        if (currentId == Source.SPLASH) return

        // Server's "now" = local now + offset. Use it to compute where
        // in the loop we should be.
        val serverNowMs = System.currentTimeMillis() + state.serverOffsetMs
        val elapsed = (serverNowMs - state.loopStartedAtMs).coerceAtLeast(0L)
        val offset = elapsed % state.totalDurationMs

        // Walk durations to find which item the loop is currently on.
        var cumulative = 0L
        var expectedIndex = -1
        var positionInItem = 0L
        for (i in state.itemDurationsMs.indices) {
            val d = state.itemDurationsMs[i]
            if (offset < cumulative + d) {
                expectedIndex = i
                positionInItem = offset - cumulative
                break
            }
            cumulative += d
        }
        if (expectedIndex < 0) {
            expectedIndex = state.itemDurationsMs.size - 1
            positionInItem = state.itemDurationsMs.last() - 1
        }
        val expectedId = state.itemIds[expectedIndex]
        if (currentId == expectedId && !force) return
        if (currentId == expectedId && force) {
            // We're on the right item but were just told to re-anchor —
            // seek to the right position-in-item.
            player.seekTo(positionInItem)
            return
        }
        // Find the queue index of the expected item. If it's not in
        // our queue, we're playing the wrong playlist — the next
        // refreshLivePlaylist() will fix that.
        val targetQueueIndex = (0 until player.mediaItemCount)
            .firstOrNull { i -> player.getMediaItemAt(i).mediaId == expectedId }
            ?: return
        LogBuffer.i(
            "PlayerController",
            "Group sync snap → item '$expectedId' at ${positionInItem}ms",
        )
        player.seekTo(targetQueueIndex, positionInItem)
    }

    /** True when the current queue item is the bundled / remote splash
     *  rather than a real playlist video. Used by [PlaybackWatchdog]
     *  to scope recovery actions: a hiccup during splash never
     *  warrants restarting the activity. */
    fun isOnSplash(): Boolean {
        val source = currentSource
        if (source == Source.SPLASH) return true
        // mix-splash mode: queue is [splash, item1, item2…]. Check the
        // current media item's id directly.
        return player.currentMediaItem?.mediaId == Source.SPLASH
    }

    fun release() {
        pendingCoordinatedStart?.let { mainHandler.removeCallbacks(it) }
        pendingCoordinatedStart = null
        mainHandler.removeCallbacks(driftTick)
        driftCorrectionTickScheduled = false
        player.release()
    }
}
