package com.smartech.screens.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** Media id of the item currently on screen. Observed by the product-
     *  info-card overlay so it can render the playing item's info. The
     *  bundled splash uses the [Source.SPLASH] sentinel; the card treats
     *  that as "no card". */
    private val _currentMediaId = MutableStateFlow<String?>(null)
    val currentMediaIdFlow: StateFlow<String?> = _currentMediaId.asStateFlow()

    /** Global "unmute everything" flag pushed from the server/staff overlay. */
    private var audioOn: Boolean = false
    /** Per-item override: id → defaultUnmute. Populated whenever a playlist
     *  is applied; consulted on every item transition. The bundled splash
     *  is never in this map and therefore always plays at the audioOn
     *  volume (muted by default). */
    private val defaultUnmuteById = mutableMapOf<String, Boolean>()
    /** Ids of still images in the queue (no drift to measure on those). */
    private val imageIds = mutableSetOf<String>()
    /** Last "#r<n>" rebuild request seen in a revision; see [apply]. */
    private var lastForceNonce = 0

    /** v0.1.12 client-side sync state. When non-null, the player snaps to
     *  the group-correct item on every queue transition; the math runs
     *  locally instead of every poll asking the server "where should I
     *  be?". See [applyGroupSync]. */
    private data class GroupSyncState(
        val loopStartedAtMs: Long,
        /** Server clock minus SystemClock.elapsedRealtime(). Monotonic on
         *  purpose: the wall clock can be stepped by seconds when Android
         *  corrects it, which would throw a screen out of step. */
        val serverOffsetRtMs: Long,
        /** Item ids in order, matching the current queue (skipping splash
         *  if mix-splash is on — but mix-splash is forced off in sync
         *  groups, see serve.py). */
        val itemIds: List<String>,
        /** Per-item duration in ms, parallel to [itemIds]. */
        val itemDurationsMs: List<Long>,
        /** Sum of durations. Cached to avoid recomputing on every
         *  transition. */
        val totalDurationMs: Long,
    ) {
        fun serverNowMs(): Long = SystemClock.elapsedRealtime() + serverOffsetRtMs
        fun elapsedMs(): Long = serverNowMs() - loopStartedAtMs
    }

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

    @Volatile private var lastSyncReport: SyncReport? = null
    fun syncReport(): SyncReport? = lastSyncReport

    /** When the last corrective jump happened, newest last. */
    private val seekTimesMs = ArrayDeque<Long>()
    private fun noteSeek() {
        seekTimesMs.addLast(SystemClock.elapsedRealtime())
        while (seekTimesMs.size > 120) seekTimesMs.removeFirst()
    }

    /** How far ahead to aim a jump; learned from how late each one lands, so
     *  a slow box doesn't keep landing behind and jumping again. */
    private var seekLeadMs: Long = SEEK_LEAD_MS
    private var lastJumpAtMs: Long = 0L
    private var checkLandingAtMs: Long = 0L

    /** Elapsed-realtime of the last item change; drift isn't read for a
     *  moment after one because the position takes time to settle. */
    private var lastTransitionAtMs: Long = 0L
    /** A pause the sync code made itself: wait for the group clock. */
    private var holdRunnable: Runnable? = null
    private var waitingForSlot = false
    private val boundaryRunnable = Runnable { onBoundary() }
    private var speedCorrecting = false

    /**
     * v0.1.17 (rewritten v0.2.25): keep this screen on the group's clock.
     *
     * Two TX3-class boxes playing the same H.264 file drift past each other at
     * ~0.5–1 % even with clocks that agree to ~10 ms, because their decoders
     * pace differently. So: measure how far the player is from the group
     * clock every 500 ms, the short way round the loop ([SyncMath.gapMs]), and
     * close small gaps with a speed change no one can see (1–4 %, scaled to
     * the gap). Bigger gaps are closed by a jump (behind) or a short wait on
     * the last frame (ahead), never by replaying a tail.
     *
     * Changes between items are driven by the group's clock, not by where a
     * file happens to end ([onBoundary]): a file shorter than its slot holds
     * its last frame, and one longer is cut at the slot. Files whose length
     * matches their slot still change over naturally.
     */
    private var driftCorrectionTickScheduled: Boolean = false
    private var currentPlaybackSpeed: Float = 1.0f
    private val driftTick: Runnable = object : Runnable {
        override fun run() {
            driftCorrectionTickScheduled = false
            try {
                syncTick()
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

    private fun stopSyncTimers() {
        mainHandler.removeCallbacks(boundaryRunnable)
        cancelHold()
        waitingForSlot = false
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
        // for the whole loop. In a sync group, what plays when is decided by
        // the group clock ([syncTick], [onBoundary]), not here.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentMediaId.value = player.currentMediaItem?.mediaId
                applyVolumeForCurrentItem()
                lastTransitionAtMs = SystemClock.elapsedRealtime()
                // Reset the playback rate at every transition. Drift
                // accumulated on the previous item could have left the
                // speed off from a recent nudge; without this reset the
                // new item would start mid-correction.
                speedCorrecting = false
                setPlaybackSpeed(1f)
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
     * v0.2.16: web page mode took over the screen. Stop decoding (a hidden
     * video loop would burn the box's CPU/GPU under the WebView) and drop the
     * queue. Resetting [currentSource] means the next [apply] / [playSplash]
     * — PlayerScreen re-runs one as soon as it's composed again — rebuilds the
     * queue from scratch, so switching back restores the playlist.
     */
    fun suspendForWebPage() {
        if (currentSource == Source.NONE && player.mediaItemCount == 0) return
        player.stop()
        player.clearMediaItems()
        currentSource = Source.NONE
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
        // Rebuild only when what would play changes. Not on the revision: the
        // server bumps it for settings that don't touch the videos (product
        // card, rotation...), and each rebuild restarts this screen at item 0.
        // "Restart player" and the watchdog ask for a rebuild by tagging the
        // revision with "#r<n>" (PlayerRepository.forceReapply), so that still
        // counts. Encode mixSplash too so toggling triggers a re-apply.
        revision.substringAfter("#r", "").toIntOrNull()?.let { lastForceNonce = it }
        val sourceKey = playlist.joinToString("|") {
            "${it.item.id}:${it.file.path}:${it.file.length()}:${it.item.durationSec}"
        } + "|splash=$mixSplash|force=$lastForceNonce"
        val expectedCount = playlist.size + (if (mixSplash) 1 else 0)
        if (sourceKey == currentSource && player.mediaItemCount == expectedCount) return
        currentSource = sourceKey

        val items = mutableListOf<MediaItem>()
        if (mixSplash) {
            items += MediaItem.Builder().setMediaId(Source.SPLASH).setUri(splashUri).build()
        }
        items += playlist.map { lv ->
            val builder = MediaItem.Builder()
                .setMediaId(lv.item.id)
                .setUri(Uri.fromFile(lv.file))
            // v0.2.14: a still has no intrinsic duration, so ExoPlayer needs
            // setImageDurationMs to know how long to show it before advancing.
            // Setting it is also what routes the item through Media3's image
            // pipeline (decode → PlayerView) instead of the media extractors.
            // We use the same dwell the server put in durationSec, so the
            // group-sync loop math (which reads durationSec) lines up across an
            // image exactly as it does for a video.
            if (lv.item.type == "image") {
                val dwellMs = (lv.item.durationSec ?: 10).coerceAtLeast(1).toLong() * 1000L
                builder.setImageDurationMs(dwellMs)
            }
            builder.build()
        }
        // Refresh the per-item defaultUnmute map for volume decisions.
        imageIds.clear()
        playlist.filter { it.item.type == "image" }.forEach { imageIds += it.item.id }
        defaultUnmuteById.clear()
        for (lv in playlist) {
            if (lv.item.defaultUnmute) defaultUnmuteById[lv.item.id] = true
        }
        val waiting = pendingCoordinatedStart != null
        val group = groupSync
        val spot = if (group != null && !mixSplash && !waiting) {
            // In a group, start where the group is, not at item 0. Otherwise
            // every rebuild would show the wrong video until the next sync
            // tick corrected it.
            SyncMath.locate(
                group.elapsedMs() + seekLeadMs,
                playlist.map { (it.item.durationSec ?: 15).coerceAtLeast(1).toLong() * 1000L },
            )?.takeIf { group.elapsedMs() >= 0L }
        } else null
        if (spot != null) {
            player.setMediaItems(items, spot.index, spot.positionMs)
        } else {
            player.setMediaItems(items, /* resetPosition = */ true)
        }
        player.prepare()
        if (waiting) {
            // The group starts together in a moment: be ready on the first
            // frame rather than playing on ahead of everyone.
            player.seekTo(0, 0L)
            player.playWhenReady = false
        } else {
            player.play()
        }
        // onMediaItemTransition fires asynchronously; apply now so the very
        // first item doesn't briefly play at the previous loop's volume.
        applyVolumeForCurrentItem()
        // Same reasoning for the current-item id the card overlay reads —
        // seed it now rather than waiting for the first transition.
        _currentMediaId.value = player.currentMediaItem?.mediaId
    }

    /**
     * Apply (or clear) a group-sync anchor.
     *
     * The tablet does all "where should I be?" maths locally, from
     * `loopStartedAtMs` and the item durations it already has (see
     * [SyncMath]); [syncTick] keeps it there.
     *
     * Pass `null` to clear: the screen is no longer in a sync group
     * and should play through its queue without correction.
     */
    fun applyGroupSync(
        loopStartedAtMs: Long?,
        serverOffsetRtMs: Long,
        itemIds: List<String>,
        itemDurationsMs: List<Long>,
    ) {
        if (loopStartedAtMs == null || itemIds.isEmpty()) {
            if (groupSync != null) {
                LogBuffer.i("PlayerController", "Group sync cleared")
                leaveGroupSync()
            }
            return
        }
        val total = itemDurationsMs.sum().coerceAtLeast(1L)
        val next = GroupSyncState(
            loopStartedAtMs = loopStartedAtMs,
            serverOffsetRtMs = serverOffsetRtMs,
            itemIds = itemIds,
            itemDurationsMs = itemDurationsMs,
            totalDurationMs = total,
        )
        val prev = groupSync
        groupSync = next
        // First time we got an anchor, or the loop epoch changed (new
        // playlist push, group restart).
        if (prev == null || prev.loopStartedAtMs != next.loopStartedAtMs) {
            LogBuffer.i(
                "PlayerController",
                "Group sync acquired — loopStartedAtMs=$loopStartedAtMs, " +
                    "${itemIds.size} items, total=${total}ms",
            )
            pendingCoordinatedStart?.let { mainHandler.removeCallbacks(it) }
            pendingCoordinatedStart = null
            stopSyncTimers()

            val msUntilStart = -next.elapsedMs()
            // Is the epoch in the (near) future? Then this is a coordinated
            // start: sit on the first frame of the first video and start on
            // the exact moment, so every screen in the group starts together.
            // Guard band: anything further out than 60 s is a clock anomaly.
            if (msUntilStart in 1L..60_000L) {
                LogBuffer.i("PlayerController", "Coordinated start in ${msUntilStart}ms")
                val target = (0 until player.mediaItemCount).firstOrNull { i ->
                    player.getMediaItemAt(i).mediaId != Source.SPLASH
                } ?: 0
                player.seekTo(target, 0L)
                player.playWhenReady = false
                armCoordinatedStart(next)
            } else {
                placeOnGroupClock(next)
            }
        }
        // Every time we receive an anchor — coordinated-start or
        // already-running — make sure the sync loop is ticking.
        scheduleDriftTickIfNeeded()
    }

    /** Wait for the start moment: re-checked once close to it, so a clock
     *  offset that improved in the meantime is used. */
    private fun armCoordinatedStart(target: GroupSyncState) {
        val wait = -target.elapsedMs()
        val fire: Runnable = if (wait > 400L) {
            Runnable { pendingCoordinatedStart = null; armCoordinatedStart(target) }
        } else {
            Runnable {
                pendingCoordinatedStart = null
                LogBuffer.i("PlayerController", "Coordinated start firing")
                lastTransitionAtMs = SystemClock.elapsedRealtime()
                player.playWhenReady = true
                scheduleDriftTickIfNeeded()
            }
        }
        pendingCoordinatedStart = fire
        mainHandler.postDelayed(fire, if (wait > 400L) wait - 300L else wait.coerceAtLeast(0L))
    }

    private fun leaveGroupSync() {
        groupSync = null
        lastSyncReport = null
        pendingCoordinatedStart?.let { mainHandler.removeCallbacks(it) }
        pendingCoordinatedStart = null
        stopSyncTimers()
        speedCorrecting = false
        setPlaybackSpeed(1f)
        player.pauseAtEndOfMediaItems = false
        // The group may have left us paused (waiting for a slot); a screen on
        // its own must be playing.
        if (!player.playWhenReady && player.mediaItemCount > 0) player.playWhenReady = true
    }

    /** Jump to where the group is now and play. */
    private fun placeOnGroupClock(state: GroupSyncState) {
        val ahead = state.elapsedMs() + seekLeadMs
        val spot = SyncMath.locate(ahead, state.itemDurationsMs) ?: return
        val q = SyncMath.queueIndexOf(state.itemIds, spot.index, queueIds())
        if (q == null) {
            holdForMissing()
            return
        }
        noteSeek()
        waitingForSlot = false
        setPlaybackSpeed(1f)
        player.seekTo(q, spot.positionMs)
        player.playWhenReady = true
        val now = SystemClock.elapsedRealtime()
        lastTransitionAtMs = now
        lastJumpAtMs = now
        checkLandingAtMs = now
    }

    /** Ids of the queue's items, in order, matching [GroupSyncState.itemIds]
     *  (splash excluded — mix-splash is forced off in groups, but defend). */
    private fun queueIds(): List<String> =
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    /** The group is on a video this screen doesn't have: hold the last frame
     *  until the group moves on, rather than playing something else. */
    private fun holdForMissing() {
        waitingForSlot = true
        setPlaybackSpeed(1f)
        player.playWhenReady = false
    }

    private fun setPlaybackSpeed(speed: Float) {
        if (currentPlaybackSpeed == speed) return
        currentPlaybackSpeed = speed
        player.playbackParameters = PlaybackParameters(speed)
    }

    /** Keep this screen on the group's clock; runs every 500 ms. Any exception
     *  is caught: skipping a tick beats crashing the activity when the queue
     *  changes underneath (a playlist edit, a purge). */
    private fun syncTick() {
        try {
            syncTickImpl()
        } catch (t: Throwable) {
            LogBuffer.w("PlayerController", "Sync tick skipped: ${t.javaClass.simpleName}: ${t.message ?: ""}", t)
        }
    }

    private fun syncTickImpl() {
        val s = groupSync ?: return
        if (s.itemIds.isEmpty() || s.itemIds.size != s.itemDurationsMs.size) return
        val elapsed = s.elapsedMs()
        if (elapsed < 0L || pendingCoordinatedStart != null) {
            report(s, SyncMath.Spot(0, 0L), null, "waiting")
            return
        }
        val spot = SyncMath.locate(elapsed, s.itemDurationsMs) ?: return
        val queueIds = queueIds()
        mainHandler.removeCallbacks(boundaryRunnable)
        mainHandler.postDelayed(
            boundaryRunnable,
            SyncMath.remainingMs(spot, s.itemDurationsMs).coerceAtLeast(1L),
        )

        val wantQueue = SyncMath.queueIndexOf(s.itemIds, spot.index, queueIds)
        if (wantQueue == null) {
            if (!waitingForSlot) holdForMissing()
            report(s, spot, null, "holding")
            return
        }
        if (waitingForSlot) {
            // The video it was waiting for is here now, or the group moved on.
            placeOnGroupClock(s)
            report(s, spot, null, "loading")
            return
        }
        if (player.playbackState != Player.STATE_READY) {
            report(s, spot, null, "loading")
            return
        }
        val timelineIdx = SyncMath.timelineIndexOf(s.itemIds, queueIds, player.currentMediaItemIndex) ?: return
        applyEndPolicy(s, timelineIdx)
        if (!player.playWhenReady) {
            // Paused by us (holding on the last frame, or waiting out a gap)
            // or at the end of a short item until its slot is up. A pause with
            // no reason left for it mustn't outlive a tick.
            val atEnd = player.duration != C.TIME_UNSET &&
                player.currentPosition >= player.duration - 150L
            if (holdRunnable == null && !atEnd) player.playWhenReady = true
            report(s, spot, null, "holding")
            return
        }
        if (player.currentMediaItem?.mediaId == Source.SPLASH) return

        val gap = SyncMath.gapMs(timelineIdx, player.currentPosition, s.itemDurationsMs, elapsed)
        val nowRt = SystemClock.elapsedRealtime()
        val settling = nowRt - lastTransitionAtMs < 400L
        if (!settling && checkLandingAtMs > 0L) {
            // Learn from the last jump: land early next time if we were late.
            checkLandingAtMs = 0L
            seekLeadMs = (seekLeadMs - gap / 2).coerceIn(0L, 3_000L)
        }
        val mayJump = nowRt - lastJumpAtMs > JUMP_COOLDOWN_MS
        val isImage = player.currentMediaItem?.mediaId in imageIds
        if (!settling) {
            when {
                gap < -SyncMath.JUMP_MS && mayJump -> {
                    LogBuffer.i("PlayerController", "Behind the group by ${-gap}ms: jumping to the group clock")
                    placeOnGroupClock(s)
                }
                gap > SyncMath.JUMP_MS -> waitOut(gap)
                gap < -SyncMath.JUMP_MS -> setPlaybackSpeed(SyncMath.speedFor(gap, true))
                else -> {
                    val speed = SyncMath.speedFor(gap, speedCorrecting)
                    speedCorrecting = speed != 1f
                    setPlaybackSpeed(speed)
                }
            }
        }
        report(s, spot, if (settling || isImage) null else gap, if (isImage) "image" else "playing")
    }

    /** Ahead of the group by a lot: pause on this frame until it catches up.
     *  Better than playing on and then jumping backwards. */
    private fun waitOut(gapMs: Long) {
        val wait = gapMs.coerceAtMost(5_000L)
        LogBuffer.i("PlayerController", "Ahead of the group by ${gapMs}ms: waiting ${wait}ms")
        player.playWhenReady = false
        val resume = Runnable {
            holdRunnable = null
            if (groupSync != null && pendingCoordinatedStart == null && !waitingForSlot) player.playWhenReady = true
        }
        holdRunnable = resume
        mainHandler.postDelayed(resume, wait)
    }

    /** A file that ends well before its slot must pause on its last frame
     *  (the next item is due at the group's moment, not at the file's end). */
    private fun applyEndPolicy(s: GroupSyncState, timelineIdx: Int) {
        val dur = player.duration
        if (dur == C.TIME_UNSET || player.playbackState != Player.STATE_READY) return
        val slot = s.itemDurationsMs[timelineIdx]
        val wantPause = dur < slot - SHORT_FILE_SLACK_MS
        if (player.pauseAtEndOfMediaItems != wantPause) player.pauseAtEndOfMediaItems = wantPause
    }

    /** Fires at the end of the item the group is on: make sure the next one
     *  starts at the right moment whatever the files' lengths. */
    private fun onBoundary() {
        try {
            onBoundaryImpl()
        } catch (t: Throwable) {
            LogBuffer.w("PlayerController", "Boundary skipped: ${t.javaClass.simpleName}: ${t.message ?: ""}", t)
        }
    }

    private fun onBoundaryImpl() {
        val s = groupSync ?: return
        if (pendingCoordinatedStart != null) return
        val elapsed = s.elapsedMs()
        if (elapsed < 0L) return
        val spot = SyncMath.locate(elapsed, s.itemDurationsMs) ?: return
        mainHandler.removeCallbacks(boundaryRunnable)
        mainHandler.postDelayed(
            boundaryRunnable,
            SyncMath.remainingMs(spot, s.itemDurationsMs).coerceAtLeast(1L),
        )
        val q = SyncMath.queueIndexOf(s.itemIds, spot.index, queueIds())
        if (q == null) {
            cancelHold()
            holdForMissing()
            return
        }
        waitingForSlot = false
        val cur = player.currentMediaItemIndex
        if (cur == q) {
            // (If it's waiting out a lead, [holdRunnable] resumes it.)
            // Already there. A one-video group restarts here: at the end of a
            // short file, rewind it rather than waiting for a natural loop.
            val atEnd = !player.playWhenReady && player.playbackState == Player.STATE_READY &&
                player.duration != C.TIME_UNSET && player.currentPosition >= player.duration - 100L
            if (atEnd) {
                player.seekTo(q, spot.positionMs)
                player.playWhenReady = true
                setPlaybackSpeed(1f)
                lastTransitionAtMs = SystemClock.elapsedRealtime()
            }
            return
        }
        // Still on an earlier item. If it's about to end by itself, let it:
        // a natural change is smoother than a seek.
        val left = player.duration - player.currentPosition
        val endingNaturally = player.playWhenReady && player.playbackState == Player.STATE_READY &&
            player.duration != C.TIME_UNSET && left in 0L..NATURAL_END_MS &&
            (cur + 1) % player.mediaItemCount == q && !player.pauseAtEndOfMediaItems
        if (endingNaturally) return
        cancelHold()
        player.seekTo(q, spot.positionMs)
        player.playWhenReady = true
        setPlaybackSpeed(1f)
        lastTransitionAtMs = SystemClock.elapsedRealtime()
    }

    private fun cancelHold() {
        holdRunnable?.let { mainHandler.removeCallbacks(it) }
        holdRunnable = null
    }

    private fun report(s: GroupSyncState, spot: SyncMath.Spot, driftMs: Long?, state: String) {
        val now = SystemClock.elapsedRealtime()
        while (seekTimesMs.isNotEmpty() && now - seekTimesMs.first() > 600_000L) seekTimesMs.removeFirst()
        lastSyncReport = SyncReport(
            anchorMs = s.loopStartedAtMs,
            itemIndex = spot.index,
            itemId = s.itemIds.getOrNull(spot.index),
            positionMs = spot.positionMs,
            driftMs = driftMs,
            speed = currentPlaybackSpeed,
            seeks = seekTimesMs.size,
            items = s.itemIds.size,
            state = state,
        )
    }

    /**
     * v0.1.98: skip to the next item in the queue — the customer-facing
     * "next video" control.
     *
     * Only ever called when the screen is NOT in a sync group (the server
     * forces tapNext off for group members, and the UI hides the control):
     * a group plays off shared loop math, so a lone skip would both break
     * the lockstep and get yanked back by the next sync tick.
     *
     * ExoPlayer is single-threaded — this must be called from the main
     * thread, which is where the Compose click lands.
     */
    fun skipToNext() {
        if (player.mediaItemCount <= 1) return          // nothing to skip to
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else {
            // End of a non-wrapping queue: loop back to the top rather than
            // dead-end on the last frame.
            player.seekTo(0, 0L)
        }
        player.play()   // a skip should never leave the screen paused
        LogBuffer.i("PlayerController", "Tap-next → ${player.currentMediaItem?.mediaId ?: "?"}")
    }

    /**
     * v0.2.0: how far through the current item playback is, as 0..1 — the
     * input to the on-screen progress bar. Null means "nothing meaningful to
     * show", which the bar renders as nothing at all:
     *   • on the splash (a loop, not a piece of content with a duration),
     *   • before ExoPlayer knows the duration (C.TIME_UNSET while preparing),
     *   • on an empty queue.
     *
     * Read live rather than pushed through a StateFlow: position advances
     * continuously and the bar samples it on its own tick, so a flow would
     * just be a second copy of the same clock.
     *
     * ExoPlayer is single-threaded — call from the main thread only. The
     * bar's sampling loop is a Compose LaunchedEffect, which satisfies that.
     */
    fun progressFraction(): Float? {
        if (isOnSplash()) return null
        val duration = player.duration
        if (duration == androidx.media3.common.C.TIME_UNSET || duration <= 0L) return null
        val position = player.currentPosition.coerceAtLeast(0L)
        return (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
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
        stopSyncTimers()
        driftCorrectionTickScheduled = false
        player.release()
    }

    companion object {
        /** How far ahead to aim the first jump: roughly what a seek takes. */
        private const val SEEK_LEAD_MS = 200L
        /** Least time between jumps: past this, a slow box keeps catching up
         *  with speed instead of seeking again and again. */
        private const val JUMP_COOLDOWN_MS = 5_000L
        /** A file this much shorter than its slot holds its last frame. */
        private const val SHORT_FILE_SLACK_MS = 120L
        /** An item ending within this of its slot's end changes over itself. */
        private const val NATURAL_END_MS = 150L

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
