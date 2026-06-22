package com.smartech.screens.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.smartech.screens.R
import com.smartech.screens.data.PlayerRepository
import com.smartech.screens.util.DeviceInfo
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

    private val appContext = context.applicationContext

    /** v0.1.75: brand whose bundled landscape splash to show before the
     *  remote splash downloads (and when fully offline). Set from the
     *  screen's saved location via [setBundledBrand]; null = neutral
     *  default. Only LANDSCAPE Smartech & tm:rw splashes are bundled —
     *  portrait always comes from the server. */
    private var bundledBrand: String? = null

    private fun rawUri(resId: Int): Uri =
        Uri.parse("android.resource://${appContext.packageName}/$resId")

    private val bundledSplashUri: Uri
        get() = rawUri(
            when (bundledBrand?.lowercase()) {
                "smartech" -> R.raw.splash_smartech
                "tmrw"     -> R.raw.splash_tmrw
                else       -> R.raw.splash
            }
        )

    /** Remote splash overrides the bundled one when set. */
    private var remoteSplashUri: Uri? = null
    private val splashUri: Uri get() = remoteSplashUri ?: bundledSplashUri

    val player: ExoPlayer = buildExoPlayer(context).apply {
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

    /** v0.1.75: pick which bundled splash (Smartech / tm:rw landscape) to
     *  show before the remote splash arrives. Re-applies immediately if the
     *  bundled splash is what's currently on screen. */
    fun setBundledBrand(brand: String?) {
        val norm = brand?.lowercase()
        if (norm == bundledBrand) return
        bundledBrand = norm
        if (currentSource == Source.SPLASH && remoteSplashUri == null) {
            currentSource = ""    // force re-apply with the new bundled file
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
        // v0.1.21: wrap the whole body in a guard. The "delete a video
        // crash" path was here: when staff remove an item via the
        // tablet's playlist editor, `state.itemIds` and
        // `state.itemDurationsMs` can briefly fall out of sync with
        // ExoPlayer's queue while the new playlist propagates. Any
        // unchecked array access threw IndexOutOfBoundsException and
        // killed the activity. We'd rather skip a single drift tick
        // than die — the next anchor refresh fixes the state.
        try {
            correctDriftInCurrentItemImpl()
        } catch (t: Throwable) {
            LogBuffer.w("PlayerController", "Drift tick skipped: ${t.javaClass.simpleName}: ${t.message ?: ""}", t)
        }
    }

    private fun correctDriftInCurrentItemImpl() {
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
        // v0.1.21: itemIds and itemDurationsMs are parallel lists, both
        // sourced from the same place. If they're not the same length
        // the anchor is mid-update — bail rather than risk indexing
        // into the shorter one with an index from the longer.
        if (state.itemIds.size != state.itemDurationsMs.size) return
        if (state.itemIds.isEmpty()) return

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0 || currentIndex >= state.itemIds.size) return
        // Defensive: queue may include splash at index 0 if mix-splash
        // is somehow on; the math operates on the splash-less itemIds
        // list. Find this item's position in itemIds by id.
        val itemIdx = state.itemIds.indexOf(currentId)
        if (itemIdx < 0 || itemIdx >= state.itemDurationsMs.size) return

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
            // Real divergence: snap. Restore speed. Same backward-
            // guard as snapToGroupExpectedItem — never seek to a
            // previous item from this code path.
            if (expectedIdx < itemIdx) {
                setPlaybackSpeedNudge(0)
                return
            }
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
        // **v0.1.18:** never nudge in the last 1.5 s of an item or the
        // first 500 ms after a transition. Both are danger zones for
        // misaligning the natural transition with the math-expected
        // boundary:
        //   • Late: a 1.01× nudge causes the item to end ~10-15 ms
        //     early per second of playback. If we keep nudging right
        //     up to the natural end, the cumulative early-finish
        //     would re-trigger snap-back.
        //   • Early: the player's currentPosition isn't stable until
        //     a few hundred ms after `seekTo`, so drift reads are
        //     noisy. Letting the player settle avoids spurious nudges.
        val itemDuration = state.itemDurationsMs[itemIdx]
        val nearEnd = player.currentPosition > itemDuration - 1500L
        val nearStart = player.currentPosition < 500L
        if (nearEnd || nearStart) {
            setPlaybackSpeedNudge(0)
            return
        }

        val drift = player.currentPosition - expectedPos
        when {
            abs(drift) < 100L -> setPlaybackSpeedNudge(0)
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
     *
     * **v0.1.18:** dialled the nudge from ±3% to ±1%. The 3% rate
     * caused items to finish ~440 ms before their natural duration,
     * which the math then saw as "you should still be on the last
     * frame of the previous item." `snapToGroupExpectedItem` then
     * seeked backward to replay the tail — user-visible as "the
     * video isn't playing fully." ±1% takes longer to close a gap
     * (a 300 ms drift takes 30 s to recover) but is invisible at
     * boundaries: a 15 s item finishes ~150 ms off natural, which
     * the boundary guard in [snapToGroupExpectedItem] absorbs
     * without snap-back.
     */
    private fun setPlaybackSpeedNudge(direction: Int) {
        val target = when {
            direction > 0 -> 1.01f
            direction < 0 -> 0.99f
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
        // v0.1.21: never let an array-bounds exception in here propagate
        // up through the ExoPlayer listener callback — that path was
        // crashing the activity when the playlist changed underneath us
        // (e.g. staff deleting an item mid-playback). Skipping a snap
        // is fine; the next anchor or transition will re-evaluate.
        try {
            snapToGroupExpectedItemImpl(force)
        } catch (t: Throwable) {
            LogBuffer.w("PlayerController", "Snap skipped: ${t.javaClass.simpleName}: ${t.message ?: ""}", t)
        }
    }

    private fun snapToGroupExpectedItemImpl(force: Boolean) {
        val state = groupSync ?: return
        val currentId = player.currentMediaItem?.mediaId ?: return
        if (currentId == Source.SPLASH) return
        // v0.1.21: parallel-list invariant check, same as in the drift
        // path. Mid-playlist-update reads can otherwise index one list
        // with a position from the other.
        if (state.itemIds.size != state.itemDurationsMs.size) return
        if (state.itemIds.isEmpty()) return

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

        // **v0.1.18:** never seek BACKWARD to a previous item. This
        // path used to trigger when the player transitioned naturally
        // a fraction of a second before the math-expected boundary —
        // e.g. decoder pacing or rate-control overshoot meant a 15 s
        // item finished at wall-clock 14.85 s, so when ExoPlayer fired
        // `onMediaItemTransition` into item 1 at pos 0, the math still
        // said "you should be at item 0 pos 14.85". The old code
        // seeked backward, visibly replaying the last 150 ms of the
        // previous video. User-perceived as "the video isn't playing
        // fully". Forcing forward-only progression means we tolerate
        // a small early-arrival at the start of each item rather than
        // replay tails — the rate-control loop closes the gap
        // mid-item.
        //
        // Force=true (epoch re-anchor) overrides this — that path is
        // explicitly meant to jump to wherever the math says,
        // including backward.
        val currentQueueIdx = player.currentMediaItemIndex
        if (!force) {
            val isOneStepBack = targetQueueIndex == currentQueueIdx - 1 ||
                (currentQueueIdx == 0 && targetQueueIndex == player.mediaItemCount - 1)
            val earlyInCurrent = player.currentPosition < 1500L
            if (isOneStepBack && earlyInCurrent) {
                // Just transitioned early; wall-clock will catch up
                // within the next item. Don't replay the tail.
                return
            }
        }

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

    /** True only when the player has *fallen back* to the splash because
     *  there's no real content to show (pure-splash mode). Unlike
     *  [isOnSplash] this is false during mix-splash playback, where the
     *  splash is a deliberate queue item that shows once per loop — so
     *  the v0.1.76 stuck-on-splash watchdog can use it without firing on
     *  every mix-splash cycle. */
    fun isOnFallbackSplash(): Boolean = currentSource == Source.SPLASH

    fun release() {
        pendingCoordinatedStart?.let { mainHandler.removeCallbacks(it) }
        pendingCoordinatedStart = null
        mainHandler.removeCallbacks(driftTick)
        driftCorrectionTickScheduled = false
        player.release()
    }

    companion object {
        /**
         * v0.1.24: build an ExoPlayer with tuning matched to the host's
         * decoder-class tier (see [DeviceInfo.decoderTierFor]).
         *
         * Two knobs that matter on cheap signage boxes:
         *
         * 1. **LoadControl buffer sizes.** ExoPlayer's defaults are
         *    50 s min / 50 s max of buffered media. For a 10 Mbps
         *    clip that's ~60 MB of RAM in flight, on top of decoded-
         *    frame buffers + the rest of the app — a TX3 Mini with
         *    1 GB total starts OOM-killing background tasks. Cutting
         *    to 10 s min / 20 s max trims peak buffer to ~25 MB and
         *    is plenty for local-file playback (no network rebuffer
         *    risk; the file is already on disk).
         *
         * 2. **Decoder fallback.** `DefaultRenderersFactory
         *    .setEnableDecoderFallback(true)` tells the renderer to
         *    try the next codec instance (often the software
         *    fallback) if the primary one crashes / refuses init.
         *    Slower but a video stays on-screen instead of going
         *    black. Safe on all tiers; only fires when the hardware
         *    path fails, which is rare on capable hardware.
         *
         * The v0.1.23 bitrate filter already removes content that
         * would obviously crash the device. These knobs help with the
         * borderline cases that pass the filter but stress the
         * decoder anyway.
         */
        private fun buildExoPlayer(context: Context): ExoPlayer {
            val tier = DeviceInfo.decoderTierFor(
                DeviceInfo.snapshot(context).ramMb
            )
            val isLow = tier == "low"

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs                 */ if (isLow) 10_000 else 30_000,
                    /* maxBufferMs                 */ if (isLow) 20_000 else 60_000,
                    /* bufferForPlaybackMs         */ 1_500,
                    /* bufferForPlaybackAfterRebufferMs */ 3_000,
                )
                // Local files only — time-based thresholds matter
                // more than the on-disk size of the buffered media.
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val renderers = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)

            LogBuffer.i(
                "PlayerController",
                "ExoPlayer built for $tier-tier device " +
                    "(buffer ${if (isLow) "10/20s" else "30/60s"}, decoder-fallback on)",
            )
            return ExoPlayer.Builder(context, renderers)
                .setLoadControl(loadControl)
                // Kiosk: never let an audio-focus event auto-pause us.
                // The screen is the only thing playing; we don't yield
                // to a phone call ringtone arriving over Bluetooth.
                .setHandleAudioBecomingNoisy(false)
                .build()
        }
    }
}
