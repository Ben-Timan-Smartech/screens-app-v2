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
    val playbackSync by repository.playbackSyncFlow.collectAsState()

    // Forward any per-location splash file to the controller.
    LaunchedEffect(remoteSplash) { controller.setRemoteSplash(remoteSplash) }

    // Forward the global audio flag. The controller combines this with each
    // item's per-video defaultUnmute on every queue transition.
    LaunchedEffect(audioOn) { controller.setAudioOn(audioOn) }

    LaunchedEffect(state, repository.mixSplash) {
        when (val s = state) {
            is PlayerRepository.State.Playing -> controller.apply(s.items, s.revision, repository.mixSplash)
            else -> controller.playSplash()
        }
    }

    // Sync-group correction. Re-runs every poll because the repository
    // emits a fresh PlaybackSyncHint each /api/state response. The
    // controller decides internally whether the drift is worth a seek.
    // No-op when the tablet isn't in a sync group (flow is null).
    LaunchedEffect(playbackSync) {
        val hint = playbackSync ?: return@LaunchedEffect
        controller.applyPlaybackSync(
            itemId = hint.itemId,
            positionMs = hint.positionMs,
            adjustedAtMs = hint.adjustedAtMs,
        )
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
    }
}
