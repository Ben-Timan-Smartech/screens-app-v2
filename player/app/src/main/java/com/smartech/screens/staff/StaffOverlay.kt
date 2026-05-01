package com.smartech.screens.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.smartech.screens.data.PlayerRepository
import com.smartech.screens.data.UserDirectory
import com.smartech.screens.data.VideoItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * Four stages of the on-tablet staff flow, matching the design brief:
 *   1. PIN entry
 *   2. Brand picker (with search + logo grid)
 *   3. Video picker for the chosen brand
 *   4. Success confirmation, auto-dismiss after 15s
 *
 * When `null`, the overlay is hidden and the [CornerUnlockOverlay] is active.
 */
private sealed class Stage {
    data object Pin : Stage()
    /** "Currently playing" home — shows playlist with delete + splash toggle + Add content. */
    data class Playlist(val user: UserDirectory.User) : Stage()
    /** Legacy super-admin home (kept for the Device admin entry point). */
    data class Home(val user: UserDirectory.User) : Stage()
    data class Brands(val user: UserDirectory.User) : Stage()
    data class Videos(val user: UserDirectory.User, val brand: String) : Stage()
    data class Success(val user: UserDirectory.User, val video: VideoItem) : Stage()
    data class Admin(val user: UserDirectory.User) : Stage()
    data class Diagnostics(val user: UserDirectory.User) : Stage()
}

@Composable
fun StaffOverlay(
    repository: PlayerRepository,
    onPickVideo: (VideoItem) -> Unit,
    /**
     * True when the host is a TV-class device (no touchscreen / leanback).
     * Drives two things: the four-corner-tap overlay is suppressed (it can't
     * fire without touch input anyway), and the staff stages should rely on
     * the externally-provided D-pad unlock bus to enter.
     */
    tvLike: Boolean = false,
    /**
     * Unit-emitting flow that the host activity pumps when the TV unlock
     * gesture fires (hold OK / Select for ~1.5s). Used in place of the
     * corner taps on TV hosts; safe to leave null on touch devices.
     */
    externalUnlock: Flow<Unit>? = null,
) {
    var visible by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<Stage>(Stage.Pin) }
    val scope = rememberCoroutineScope()

    // Touch path — invisible four-corner-tap unlock. Skip on TV-class
    // devices: the overlay would still attach pointer-input handlers but
    // a remote can't fire taps, so it's just dead weight.
    if (!tvLike) {
        CornerUnlockOverlay(onUnlock = {
            visible = true
            stage = Stage.Pin
        })
    }

    // D-pad path — collect the activity's unlock bus. Works on any device,
    // but on TVs it's the only entry point.
    if (externalUnlock != null) {
        LaunchedEffect(externalUnlock) {
            externalUnlock.collect {
                visible = true
                stage = Stage.Pin
            }
        }
    }

    if (!visible) return

    // Auto-dismiss after 10s on the success screen — long enough to read,
    // short enough that the customer-facing player isn't stuck on it.
    // Returns to the playlist view rather than dismissing entirely so staff
    // can keep adding without re-PINing.
    if (stage is Stage.Success) {
        val s = stage as Stage.Success
        LaunchedEffect(stage) {
            delay(10_000)
            stage = Stage.Playlist(s.user)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F6F2))
    ) {
        when (val s = stage) {
            Stage.Pin -> PinScreen(
                onCorrect = { user ->
                    // Everyone lands on the playlist view. Super admin can
                    // hop into Device admin from there.
                    stage = Stage.Playlist(user)
                },
                onCancel = { visible = false },
            )
            is Stage.Playlist -> PlaylistView(
                repository = repository,
                user = s.user,
                onAddContent = { stage = Stage.Brands(s.user) },
                onOpenDeviceAdmin = { stage = Stage.Admin(s.user) },
                onDone = { visible = false },
            )
            is Stage.Home -> SuperAdminHome(
                user = s.user,
                onSwapContent = { stage = Stage.Brands(s.user) },
                onDeviceAdmin = { stage = Stage.Admin(s.user) },
                onCancel = { visible = false },
            )
            is Stage.Brands -> BrandPickerScreen(
                remoteLibrary = repository.remoteLibrary,
                onBack = { stage = Stage.Playlist(s.user) },
                onPickBrand = { stage = Stage.Videos(s.user, it) },
                onCancel = { visible = false },
            )
            is Stage.Videos -> {
                // Prefer the remote library (pulled from /api/library) so the
                // staff overlay shows every brand video, not just the current
                // playlist. Falls back to the playlist when the library is
                // unreachable / empty.
                val library = repository.remoteLibrary.state.value
                val videos = if (library.videos.isNotEmpty()) {
                    library.videos
                        .filter { it.brand == s.brand }
                        .map {
                            VideoItem(
                                id = it.id, title = it.title,
                                brand = it.brand, product = it.product,
                                url = it.mediaUrl,
                                durationSec = it.durationSec?.toInt(),
                            )
                        }
                } else {
                    (repository.state.value as? PlayerRepository.State.Playing)
                        ?.items?.map { it.item }
                        ?.filter { it.brand == s.brand || it.brand == null }
                        ?: emptyList()
                }
                VideoPickerScreen(
                    brand = s.brand,
                    videos = videos,
                    onBack = { stage = Stage.Brands(s.user) },
                    onPick = { picked ->
                        com.smartech.screens.util.LogBuffer.i(
                            "StaffOverlay",
                            "Pick → append ${picked.title}",
                        )
                        onPickVideo(picked)
                        // Fire the network call BEFORE the stage change so
                        // it's queued onto the scope immediately. The scope
                        // outlives stage changes anyway, but ordering helps
                        // when debugging logs.
                        scope.launch {
                            repository.pushPlaylistToServer(listOf(picked), mode = "append")
                        }
                        stage = Stage.Success(s.user, picked)
                    },
                    onCancel = { visible = false },
                )
            }
            is Stage.Success -> SuccessScreen(
                video = s.video,
                onBack = { stage = Stage.Videos(s.user, s.video.brand ?: "") },
                onDone = { stage = Stage.Playlist(s.user) },
            )
            is Stage.Admin -> DeviceAdminScreen(
                repository = repository,
                user = s.user,
                // Back goes to the playlist view (the canonical staff home),
                // not the legacy Swap content / Device admin tile picker.
                onBack = { stage = Stage.Playlist(s.user) },
                onCancel = { visible = false },
                onOpenDiagnostics = { stage = Stage.Diagnostics(s.user) },
            )
            is Stage.Diagnostics -> NetworkTestScreen(
                repository = repository,
                onBack = { stage = Stage.Admin(s.user) },
                onCancel = { visible = false },
            )
        }
    }
}
