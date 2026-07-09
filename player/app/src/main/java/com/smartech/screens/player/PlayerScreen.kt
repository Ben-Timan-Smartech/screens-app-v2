package com.smartech.screens.player

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.smartech.screens.data.PlayerRepository

/**
 * Single PlayerView, always mounted. The [PlayerController] decides what's in
 * the queue: a real playlist when one is available, or the bundled splash
 * loop otherwise. That removes the need for a separate "empty" placeholder —
 * the screen always has motion on it.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    controller: PlayerController,
    repository: PlayerRepository,
) {
    val state by repository.state.collectAsState()
    val remoteSplash by repository.remoteSplashFile.collectAsState()
    val audioOn by repository.audioOnFlow.collectAsState()
    val groupSync by repository.groupSyncFlow.collectAsState()
    val calibrateUntilMs by repository.calibrateUntilMsFlow.collectAsState()
    val serverOffsetMs by repository.serverOffsetMsFlow.collectAsState()
    val intendedPlaylist by repository.intendedPlaylist.collectAsState()
    val downloads by repository.downloads.collectAsState()
    val mixSplash by repository.mixSplashFlow.collectAsState()
    // Product-info-card state: the screen-wide toggle, the id of the item
    // currently on screen, and the screen's city (for region pricing).
    val productCard by repository.productCardFlow.collectAsState()
    val currentMediaId by controller.currentMediaIdFlow.collectAsState()
    val city by repository.store.locCity.collectAsState(initial = null)

    // Forward any per-location splash file to the controller.
    LaunchedEffect(remoteSplash) { controller.setRemoteSplash(remoteSplash) }

    // Forward the global audio flag. The controller combines this with each
    // item's per-video defaultUnmute on every queue transition.
    LaunchedEffect(audioOn) { controller.setAudioOn(audioOn) }

    // Keying on the reactive mixSplash value (not repository.mixSplash, which
    // is a plain property read) so a toggle recomposes + re-applies promptly.
    // Without this the toggle only reached the controller via an incidental
    // recomposition — a re-published equal State.Playing gets deduped by the
    // MutableStateFlow, so `state` never changes on its own.
    LaunchedEffect(state, mixSplash) {
        when (val s = state) {
            is PlayerRepository.State.Playing -> controller.apply(s.items, s.revision, mixSplash)
            else -> controller.playSplash()
        }
    }

    // Forward the group sync anchor. The controller does the actual
    // sync math locally on every onMediaItemTransition; we just keep
    // the latest epoch + server-clock-offset in sync with what the
    // server told us. When the screen isn't in a sync group the flow
    // is null and the controller clears its anchor.
    val playing = state
    LaunchedEffect(groupSync, playing) {
        val anchor = groupSync
        val items = (playing as? PlayerRepository.State.Playing)?.items ?: emptyList()
        if (anchor != null && items.isNotEmpty()) {
            controller.applyGroupSync(
                loopStartedAtMs = anchor.loopStartedAtMs,
                serverOffsetMs = anchor.serverOffsetMs,
                itemIds = items.map { it.item.id },
                itemDurationsMs = items.map { (it.item.durationSec ?: 15).toLong() * 1000L },
            )
        } else {
            controller.applyGroupSync(
                loopStartedAtMs = null,
                serverOffsetMs = 0L,
                itemIds = emptyList(),
                itemDurationsMs = emptyList(),
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF141414))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = controller.player
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setKeepContentOnPlayerReset(true)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
        )
        // v0.1.15: giant ticking clock on top of the player when the
        // CMS has put this screen in calibration mode. Renders nothing
        // when calibrateUntilMs is null / past, so the cost on the
        // normal playback path is just collecting two StateFlows.
        CalibrationOverlay(
            calibrateUntilMs = calibrateUntilMs,
            serverOffsetMs = serverOffsetMs,
        )

        // v0.1.26: cold-start loading overlay. When the server has
        // pushed content (intendedPlaylist is non-empty) but the
        // local cache hasn't caught up yet (state isn't Playing),
        // ExoPlayer is looping the bundled splash. Without a hint
        // that's confusing — the admin shows items, the screen
        // shows the on-brand splash. This overlay says
        // "Loading content N of M, X MB" in a low-key corner badge
        // so operators see what's happening. Renders nothing when
        // playback is healthy.
        ColdStartLoadingOverlay(
            isPlaying = state is PlayerRepository.State.Playing,
            intendedItemCount = intendedPlaylist.size,
            downloads = downloads,
        )

        // Shopper-facing product-info card. Resolves the currently-playing
        // item from the live queue by media id, then renders its info over
        // the video — but only when the server has turned productCard on.
        // The overlay itself no-ops when the item has no card data or the
        // current id is the splash sentinel (not in the playlist).
        val playingItems = (state as? PlayerRepository.State.Playing)
            ?.items?.map { it.item } ?: emptyList()
        val currentItem = currentMediaId?.let { id -> playingItems.firstOrNull { it.id == id } }
        ProductInfoCardOverlay(
            enabled = productCard,
            item = currentItem,
            city = city,
        )
    }
}
