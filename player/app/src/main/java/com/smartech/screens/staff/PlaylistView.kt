package com.smartech.screens.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartech.screens.data.PlayerRepository
import com.smartech.screens.data.UserDirectory
import com.smartech.screens.data.VideoItem
import com.smartech.screens.util.LogBuffer
import kotlinx.coroutines.launch

private val Ink      = Color(0xFF141414)
private val Bone     = Color(0xFFF7F6F2)
private val BoneSoft = Color(0xFFE6E2D6)
// v0.1.19: bumped from #E2DED3 -> #B8B1A0 so borders read on
// low-contrast TVs. Old value vanished against Bone on the Cyclone
// box's HDMI output.
private val BoneLine = Color(0xFFB8B1A0)
// v0.1.19: secondary text was #6E6B62 (mid-gray, ~5:1). Dropped to
// #3A3832 (~11:1) so timestamps, sub-labels, and "Add content"
// helper copy are legible across a room. Keeps the secondary <
// primary hierarchy because Ink is still pure-dark.
private val Muted    = Color(0xFF3A3832)
// tm:rw `--ok-dot` — same green the Status dot uses for "online".
private val Ok       = Color(0xFF3D8C4B)

/**
 * The first stage staff see after PIN entry. Lists what's currently playing
 * on this screen and lets the user:
 *   • Remove individual videos
 *   • Toggle splash mixing
 *   • Add more content (routes to brand picker)
 *   • Open device admin (super admin only)
 *
 * Editing actions hit the live server immediately and the next sync tick
 * pulls the new state — same path the CMS uses, no special-casing.
 */
@Composable
fun PlaylistView(
    repository: PlayerRepository,
    user: UserDirectory.User,
    onAddContent: () -> Unit,
    onOpenDeviceAdmin: () -> Unit,
    onDone: () -> Unit,
) {
    val mixSplash by repository.mixSplashFlow.collectAsState()
    val audioOn by repository.audioOnFlow.collectAsState()
    // v0.1.89: product info card (price + description) on/off, same
    // per-screen flag the CMS ScreenDetail exposes. Sits above Mix splash
    // in the rail. Unlike Mix splash it's timing-neutral, so there's no
    // sync-group lock — it stays tappable in a group.
    val productCard by repository.productCardFlow.collectAsState()
    val pollMode by repository.pollModeFlow.collectAsState()
    // v0.1.33: read sync-group membership so the Mix splash toggle
    // can grey out + explain itself when the screen is in a group.
    // The server forces mixSplash=false in /api/state for any screen
    // with a syncGroup (v0.1.11 fix — mix-splash's bonus duration
    // breaks the loop math). The stored value is preserved but the
    // tablet always sees false, so the toggle was popping back off
    // every poll.
    val syncGroup by repository.syncGroupFlow.collectAsState()
    // v0.1.36: surface the Sync group card on the content page so
    // staff can see + change group membership without diving into
    // Device admin. The card pivots between member-list (in a group)
    // and join picker (not in one), and exposes a Leave action.
    val syncGroupMembers by repository.syncGroupMembersFlow.collectAsState()
    val availableSyncGroups by repository.availableSyncGroupsFlow.collectAsState()
    // intendedPlaylist mirrors what the server says is on this screen, even
    // when some items are still downloading. That way the row appears the
    // moment the user adds it — with a progress bar — rather than waiting
    // for the bytes to land.
    val canonicalItems by repository.intendedPlaylist.collectAsState()
    val downloads by repository.downloads.collectAsState()
    val failures by repository.downloadFailures.collectAsState()
    val playbackFailures by repository.playbackFailures.collectAsState()
    // Local override so deletes feel instant — clears as soon as the live
    // poll catches up to the new server revision.
    var pendingItems by remember { mutableStateOf<List<VideoItem>?>(null) }
    val items = pendingItems ?: canonicalItems
    LaunchedEffect(canonicalItems) {
        if (pendingItems != null && pendingItems!!.map { it.id } == canonicalItems.map { it.id }) {
            pendingItems = null
        }
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // Reorder helper used by the up/down arrows on each row.
    fun moveItem(from: Int, to: Int) {
        if (from == to || to < 0 || to >= items.size) return
        val next = items.toMutableList()
        val moved = next.removeAt(from)
        next.add(to, moved)
        pendingItems = next       // optimistic UI update
        scope.launch { repository.pushPlaylistToServer(next, mode = "replace") }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all content?") },
            text = {
                Text(
                    "Removes all ${items.size} video${if (items.size == 1) "" else "s"} from this screen. " +
                        "The splash will play on loop until you push more content."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    pendingItems = emptyList()
                    LogBuffer.w("PlaylistView", "Clear all tapped (was ${items.size} items)")
                    scope.launch { repository.pushPlaylistToServer(emptyList(), mode = "replace") }
                }) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }

    Row(Modifier.fillMaxSize().background(Bone)) {
        // Left rail
        Box(
            Modifier
                .width(420.dp)
                .fillMaxHeight()
                .background(Ink)
                .padding(horizontal = 48.dp, vertical = 56.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Text("Now playing".uppercase(), color = Color(0x73FFFFFF), fontSize = 11.sp, letterSpacing = 1.6.sp)
                Spacer(Modifier.height(14.dp))
                Text(
                    "What's on this screen",
                    color = Bone, fontSize = 30.sp, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    if (items.isEmpty())
                        "Nothing pushed yet — splash is looping. Tap Add content to put a video on."
                    else
                        "Tap × to remove a video. Tap Add content to put more on. Splash mixing toggle below.",
                    color = Color(0x99FFFFFF), fontSize = 14.sp,
                )

                Spacer(Modifier.weight(1f))

                // Product info card toggle — shows the price + short
                // description over each product's video. Timing-neutral,
                // so (unlike Mix splash) it's never locked by a sync group.
                // Only actually renders for items that have price/description
                // in the tm:rw catalogue; the server attaches those to
                // /api/state when this is on.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pricing & description", color = Bone, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (productCard)
                                "Shows a product card (price + description) over each product video."
                            else
                                "Product videos play with no price/description overlay.",
                            color = Color(0x99FFFFFF), fontSize = 12.sp,
                        )
                    }
                    DarkToggle(
                        on = productCard,
                        onChange = { value ->
                            LogBuffer.i("PlaylistView", "Product card tapped → $value")
                            scope.launch {
                                repository.setProductCardOnServer(value)
                            }
                        },
                        enabled = true,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Splash mix toggle in the rail (high contrast).
                // v0.1.33: locked off when the screen is in a sync
                // group. The server forces mixSplash=false in
                // /api/state for grouped screens (v0.1.11 fix: the
                // splash's extra duration breaks the loop math
                // tablets use to stay aligned). Letting the user
                // tap the toggle ON only to watch it bounce back
                // OFF on the next poll is worse than just telling
                // them why it's disabled.
                val inGroup = syncGroup != null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mix splash", color = Bone, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (inGroup)
                                "Disabled — screen is in sync group '${syncGroup}'. Leave the group to mix splash."
                            else
                                "Plays the bundled splash between videos.",
                            color = Color(0x99FFFFFF), fontSize = 12.sp,
                        )
                    }
                    DarkToggle(
                        on = mixSplash && !inGroup,
                        onChange = { value ->
                            LogBuffer.i("PlaylistView", "Mix splash tapped → $value")
                            scope.launch {
                                repository.setMixSplashOnServer(value)
                            }
                        },
                        // Greyed out when in a group so the operator
                        // can see the option exists but understand
                        // why they can't toggle it from here.
                        enabled = !inGroup,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Audio on/off toggle. Off (the default) keeps every video
                // muted unless its Content Library entry has "default unmute"
                // set. On unmutes everything regardless.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Audio", color = Bone, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (audioOn) "All videos play with sound."
                            else "Muted by default; videos flagged in the library still play sound.",
                            color = Color(0x99FFFFFF), fontSize = 12.sp,
                        )
                    }
                    DarkToggle(
                        on = audioOn,
                        onChange = { value ->
                            LogBuffer.i("PlaylistView", "Audio tapped → $value")
                            scope.launch {
                                repository.setAudioOnServer(value)
                            }
                        },
                        enabled = true,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Poll mode — three discrete cadences instead of the
                // old binary low-data toggle. Fast = 10 s (install / debug),
                // Normal = 60 s (default), Slow = 5 min (cellular / metered;
                // also skips the ~70 MB per-location splash).
                Column {
                    Text("Poll mode", color = Bone, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        when (pollMode) {
                            PlayerRepository.PollMode.FAST   -> "Checking the server every 10 s."
                            PlayerRepository.PollMode.NORMAL -> "Checking the server every 60 s. Default."
                            PlayerRepository.PollMode.SLOW   -> "Checking every 5 min; skipping the per-location splash to save data."
                        },
                        color = Color(0x99FFFFFF), fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "Fast" to PlayerRepository.PollMode.FAST,
                            "Normal" to PlayerRepository.PollMode.NORMAL,
                            "Slow" to PlayerRepository.PollMode.SLOW,
                        ).forEach { (label, mode) ->
                            val selected = pollMode == mode
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) Bone else Color(0x1AFFFFFF))
                                    .border(
                                        1.dp,
                                        if (selected) Bone else Color(0x33FFFFFF),
                                        RoundedCornerShape(6.dp),
                                    )
                                    .clickable {
                                        if (!selected) {
                                            LogBuffer.i("PlaylistView", "Poll mode tapped → ${mode.wire}")
                                            scope.launch { repository.setPollModeOnServer(mode) }
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    color = if (selected) Ink else Bone,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Refresh now — fires a one-shot poll bypass-style so
                // staff can see a pushed playlist land without waiting
                // for the next poll tick (up to 5 minutes in Slow mode).
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Bone, RoundedCornerShape(6.dp))
                        .clickable {
                            LogBuffer.i("PlaylistView", "Refresh now tapped")
                            repository.refreshNow()
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Refresh now",
                        color = Bone,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(Modifier.height(10.dp))

                // v0.1.82: Restart app — relaunch the player without a full
                // device reboot (cached videos + registration survive). Same
                // action as Device admin → Actions → Restart app, surfaced here
                // on the main staff overlay for quick recovery.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Bone, RoundedCornerShape(6.dp))
                        .clickable {
                            LogBuffer.w("PlaylistView", "Restart app tapped")
                            repository.scheduleSelfRestart()
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Restart app",
                        color = Bone,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Build number — small, low-contrast, monospaced. Lets a
                // staff member confirm what version is on the tablet
                // without leaving the playlist screen (e.g. "is this
                // already on v0.1.14 with the resolution picker?"). The
                // value comes from BuildConfig.VERSION_NAME, which is
                // driven by the top-level VERSION file at build time —
                // so it auto-bumps on every release without any manual
                // edit here.
                Text(
                    "v${com.smartech.screens.BuildConfig.VERSION_NAME}",
                    color = Color(0x66FFFFFF),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // Right pane
        Column(
            Modifier
                .fillMaxSize()
                .background(Bone)
                .padding(horizontal = 56.dp, vertical = 40.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Playlist", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    "${items.size} video${if (items.size == 1) "" else "s"}",
                    color = Muted, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(16.dp))

            if (items.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .border(1.dp, BoneLine, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No videos in the playlist", color = Muted, fontSize = 16.sp)
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items.size) { i ->
                        val v = items[i]
                        val progress = downloads[v.id]
                        // Status derives from three signals: in-flight
                        // downloads, recent failures, and what's on
                        // disk. The cache check isn't reactive on its
                        // own, but it re-runs every time downloads or
                        // failures change (which is exactly when the
                        // file state flips), so the badge stays in
                        // sync without polling.
                        val failed = v.id in failures
                        val cached = remember(downloads, failures) {
                            repository.cache.has(v.id)
                        }
                        PlaylistRow(
                            index = i + 1,
                            video = v,
                            progress = progress,
                            cached = cached,
                            failed = failed,
                            failReason = failures[v.id],
                            playbackFailReason = playbackFailures[v.id],
                            canMoveUp = i > 0,
                            canMoveDown = i < items.size - 1,
                            onMoveUp = { moveItem(i, i - 1) },
                            onMoveDown = { moveItem(i, i + 1) },
                            onRemove = onRemove@{
                                LogBuffer.i("PlaylistView", "Remove tapped: ${v.title}")
                                if (busy) {
                                    LogBuffer.w("PlaylistView", "Remove ignored — busy")
                                    return@onRemove
                                }
                                val next = items.toMutableList().also { it.removeAt(i) }
                                pendingItems = next   // optimistic UI update
                                scope.launch {
                                    busy = true
                                    repository.pushPlaylistToServer(next, mode = "replace")
                                    busy = false
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // v0.1.36: Sync group card on the content page. When in a
            // group it lists every member with an online dot + offers
            // a Leave button; when independent it lists every group on
            // the fleet so staff can join one with a single click —
            // no CMS round-trip. Sits between the playlist and the
            // footer actions so a staff member who's mid-edit can
            // glance down and see who they're locked to.
            SyncGroupCard(
                currentGroupId = syncGroup,
                members = syncGroupMembers,
                availableGroups = availableSyncGroups,
                onJoin = { gid ->
                    LogBuffer.i("PlaylistView", "Sync join tapped → $gid")
                    scope.launch {
                        runCatching { repository.setSyncGroupOnServer(gid) }
                            .onFailure { LogBuffer.w("PlaylistView", "Join failed: ${it.message}") }
                        repository.refreshNow()
                    }
                },
                onLeave = {
                    LogBuffer.i("PlaylistView", "Sync leave tapped")
                    scope.launch {
                        runCatching { repository.setSyncGroupOnServer(null) }
                            .onFailure { LogBuffer.w("PlaylistView", "Leave failed: ${it.message}") }
                        repository.refreshNow()
                    }
                },
                onCalibrate = {
                    LogBuffer.i("PlaylistView", "Calibrate tapped")
                    scope.launch {
                        runCatching { repository.triggerLocalCalibration(60) }
                            .onFailure { LogBuffer.w("PlaylistView", "Calibrate failed: ${it.message}") }
                    }
                },
            )

            Spacer(Modifier.height(20.dp))

            // Footer actions — left-aligned editing actions, right-aligned
            // navigation. Final layout:
            //   [+ Add content] [Clear all]  ...  [Device admin] [Done]
            Row(verticalAlignment = Alignment.CenterVertically) {
                // + Add content — primary edit, left-most.
                Box(
                    Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Ink)
                        .clickable { onAddContent() }
                        .padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+ Add content", color = Bone, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(12.dp))
                // Clear all — destructive, disabled when nothing to clear.
                Box(
                    Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BoneSoft)
                        .border(1.dp, BoneLine, RoundedCornerShape(6.dp))
                        .clickable(enabled = items.isNotEmpty()) { confirmClear = true }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Clear all",
                        color = if (items.isEmpty()) Color(0xFFB5B0A2) else Color(0xFFA63824),
                        fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(Modifier.weight(1f))

                if (user.role == UserDirectory.Role.SUPER_ADMIN) {
                    Text(
                        "Device admin", color = Muted, fontSize = 16.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onOpenDeviceAdmin() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                // Done — primary confirm/exit, far right. Green fill so it
                // reads as a proper button, not a text link.
                Box(
                    Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Ok)
                        .clickable { onDone() }
                        .padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Done",
                        color = Bone,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    index: Int,
    video: VideoItem,
    progress: PlayerRepository.DownloadProgress? = null,
    cached: Boolean = false,
    failed: Boolean = false,
    failReason: String? = null,
    playbackFailReason: String? = null,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onRemove: () -> Unit,
) {
    val downloading = progress != null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, BoneLine, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$index",
                color = Muted, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(28.dp),
            )
            DownloadStatusBadge(
                downloading = downloading,
                cached = cached,
                failed = failed,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(video.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(
                        video.brand,
                        video.product,
                        video.durationSec?.let { "${it}s" },
                    ).joinToString(" · "),
                    color = Muted, fontSize = 13.sp,
                )
            }
            ReorderButton(label = "▲", enabled = canMoveUp, onClick = onMoveUp)
            Spacer(Modifier.width(6.dp))
            ReorderButton(label = "▼", enabled = canMoveDown, onClick = onMoveDown)
            Spacer(Modifier.width(6.dp))
            // Remove button
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(BoneSoft)
                    .clickable { onRemove() }
                    .padding(10.dp),
            ) {
                Text("×", color = Color(0xFFA63824), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
        }

        // v0.1.79: per-item lifecycle status line. An active download wins
        // (it may be retrying); otherwise show the failure reason — the
        // headline add, since a fatal error previously showed only a red ✕
        // with no explanation; otherwise, with nothing on disk yet, it's
        // still syncing to the server / queued to download.
        when {
            downloading -> {
                Spacer(Modifier.height(10.dp))
                DownloadProgressStrip(progress!!)
            }
            failReason != null -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Couldn't download: $failReason",
                    color = Color(0xFFA63824), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
            playbackFailReason != null -> {
                // v0.1.80: downloaded fine, but the device can't play it
                // (the watchdog skipped it). Show the reason + format.
                Spacer(Modifier.height(8.dp))
                Text(
                    playbackFailReason,
                    color = Color(0xFFA63824), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
            !cached -> {
                Spacer(Modifier.height(6.dp))
                Text("Syncing to server…", color = Muted, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Status badge for the leading edge of each playlist row. Three states
 * mapped to one shape so the rows scan cleanly down the list:
 *   • **Cached** → solid green disc with a tick
 *   • **Downloading** (or not yet attempted) → spinner
 *   • **Failed** → solid red disc with an ✕
 *
 * State priority is failed > cached > downloading. A video that failed
 * once but has bytes on disk from a previous successful download still
 * reads as cached — but if it last failed, the operator sees the X
 * even if the file is technically present (probably truncated).
 */
@Composable
private fun DownloadStatusBadge(
    downloading: Boolean,
    cached: Boolean,
    failed: Boolean,
) {
    val size = 24.dp
    val ok    = Color(0xFF1E7A3D)   // green
    val err   = Color(0xFFA63824)   // same red as the × button
    val ring  = BoneSoft
    Box(
        Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed -> Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(err),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            cached -> Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(ok),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            else -> {
                // Downloading OR pending — both render as a spinner.
                CircularProgressIndicator(
                    modifier = Modifier.size(size - 4.dp),
                    strokeWidth = 2.dp,
                    color = Ink,
                    trackColor = ring,
                )
            }
        }
    }
}

/** Square pill-style button used for ↑ / ↓ reorder controls. Disabled at
 *  the ends of the list rather than hidden so the row geometry stays stable. */
@Composable
private fun ReorderButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) BoneSoft else BoneSoft.copy(alpha = 0.5f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Ink else Color(0xFFB5B0A2),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Inline progress strip — shows under a row whose video is mid-download.
 * Renders: filled bar (% of total), percentage label, "5.6 / 12.4 MB",
 * and current speed in MB/s.
 */
@Composable
private fun DownloadProgressStrip(p: PlayerRepository.DownloadProgress) {
    val fraction = p.fraction ?: 0f
    val mbDone = p.bytes / 1_000_000.0
    val mbTotal = p.totalBytes?.let { it / 1_000_000.0 }
    val mbPerSec = p.bytesPerSec / 1_000_000.0

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(BoneSoft),
        ) {
            // Indeterminate-ish bar when we don't know total: fill to 100%
            // with a muted colour so the user still sees motion via byte count.
            val width = if (p.totalBytes == null) 1f else fraction
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(width)
                    .background(Color(0xFF2D5BFF))
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Percentage (or "Downloading…" when total unknown)
            Text(
                if (p.totalBytes != null) "${(fraction * 100).toInt()}%"
                else "Downloading…",
                color = Color(0xFF2D5BFF), fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(56.dp),
            )
            Text(
                if (mbTotal != null) "%.1f / %.1f MB".format(mbDone, mbTotal)
                else "%.1f MB".format(mbDone),
                color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            if (mbPerSec > 0.05) {
                Text(
                    "%.1f MB/s".format(mbPerSec),
                    color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

// Toggle styled for the dark rail. Bone-on-Ink rather than Ink-on-Bone.
@Composable
private fun DarkToggle(on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    // v0.1.33: visually distinct disabled state so the operator can
    // see at a glance that the toggle is locked. Used by the Mix
    // splash row when the screen is in a sync group — without the
    // dimming the toggle looked clickable but flicked back the
    // moment they touched it.
    val trackAlpha = if (enabled) 1.0f else 0.35f
    Box(
        Modifier
            .width(48.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                (if (on) Bone else Color(0x33FFFFFF))
                    .copy(alpha = trackAlpha)
            )
            .clickable(enabled = enabled) { onChange(!on) },
    ) {
        Box(
            Modifier
                .padding(start = if (on) 24.dp else 4.dp, top = 4.dp)
                .width(20.dp)
                .height(20.dp)
                .clip(CircleShape)
                .background((if (on) Ink else Bone).copy(alpha = trackAlpha))
        )
    }
}
