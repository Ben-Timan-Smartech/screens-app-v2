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
        player.release()
    }
}
