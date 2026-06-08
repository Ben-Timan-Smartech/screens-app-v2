package com.smartech.screens.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.smartech.screens.R
import com.smartech.screens.data.PlayerRepository
import com.smartech.screens.util.LogBuffer

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

    init {
        // Re-apply volume whenever the player moves to the next item in the
        // queue. Without this listener, the first MediaItem's volume sticks
        // for the whole loop.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applyVolumeForCurrentItem()
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
     * Apply a sync-group hint. If we're already on [itemId], seek when
     * the position drift exceeds [DRIFT_CORRECTION_MS]. If we're on a
     * different item, jump to that item and seek to the position.
     *
     * Splash items in a mix-splash playlist break sync (the bundled
     * splash isn't part of the server's loop), so we skip the
     * correction whenever we're currently on splash — the next item
     * transition naturally lands us back on a sync-tracked item.
     *
     * `hint.positionMs` is already latency-adjusted on the way in (see
     * `PlayerRepository.PlaybackSyncHint`), so we compute "expected
     * position right now" by adding the time elapsed since the hint
     * was constructed.
     */
    fun applyPlaybackSync(itemId: String, positionMs: Long, adjustedAtMs: Long) {
        val currentId = player.currentMediaItem?.mediaId ?: return
        if (currentId == Source.SPLASH) return
        // How far the expected position has advanced since the hint
        // landed. Capped at a few seconds to defend against the local
        // clock being miles off — clamped values just mean we under-
        // correct rather than panic-seek.
        val sinceHintMs = (System.currentTimeMillis() - adjustedAtMs).coerceIn(0L, 5_000L)
        val expectedPositionMs = positionMs + sinceHintMs

        if (currentId != itemId) {
            // Find the index of the requested item in the current queue
            // and seek straight to it. If the id isn't in the queue
            // we're misaligned — the next playlist apply will fix that.
            val targetIndex = (0 until player.mediaItemCount)
                .firstOrNull { i -> player.getMediaItemAt(i).mediaId == itemId }
                ?: return
            LogBuffer.i(
                "PlayerController",
                "Sync jump → item '$itemId' @ ${expectedPositionMs}ms",
            )
            player.seekTo(targetIndex, expectedPositionMs)
            return
        }
        // Same item — only seek if drift exceeds the threshold, so we
        // don't introduce visible micro-stutters every poll.
        val actualPositionMs = player.currentPosition
        val driftMs = expectedPositionMs - actualPositionMs
        if (kotlin.math.abs(driftMs) > DRIFT_CORRECTION_MS) {
            LogBuffer.i(
                "PlayerController",
                "Sync nudge — drift ${driftMs}ms (actual=${actualPositionMs}, expected=${expectedPositionMs})",
            )
            player.seekTo(expectedPositionMs)
        }
    }

    fun release() {
        player.release()
    }

    private companion object {
        /** Tablets correct drift on every poll, but only if they're off
         *  by more than this much. Below the threshold we let ExoPlayer
         *  ride — small seeks introduce visible stutter on cheap
         *  Android TV boxes. 1500 ms is roughly one "perceptible drift"
         *  unit at the kind of viewing distance these screens get. */
        const val DRIFT_CORRECTION_MS = 1500L
    }
}
