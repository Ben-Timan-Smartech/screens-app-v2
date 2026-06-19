package com.smartech.screens.data

import android.content.Context
import android.util.Log
import com.smartech.screens.util.DeviceInfo
import com.smartech.screens.util.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The seam between "what the server says" and "what the player should do right
 * now". Holds the current resolved playlist as a state flow, coordinates cache
 * reconciliation, and owns the registration / ping / settings loops.
 *
 * Single instance, constructed in [com.smartech.screens.ScreensApp].
 */
class PlayerRepository(
    private val appContext: Context,
    val store: DeviceStore,
    val api: DeviceApi,
    val cache: VideoCache,
    val httpClient: okhttp3.OkHttpClient,
) {
    /** Late-bound so ScreensApp can wire Repository ↔ Updater without
     *  a circular constructor dependency. The CMS "update" command
     *  delegates to this. Null = updater not set up yet (early
     *  process boot), in which case we silently no-op. */
    var updater: com.smartech.screens.update.Updater? = null
    /** Library mirror — pulled from the live server, surfaced to staff overlay. */
    val remoteLibrary: RemoteLibrary = RemoteLibrary(httpClient)
    private var libraryRefreshTickCounter = 0
    sealed class State {
        data object Registering : State()
        data class Playing(val items: List<LocalVideo>, val revision: String) : State()
        data class Empty(val reason: String) : State()
        data class Error(val throwable: Throwable) : State()
    }

    /** A [VideoItem] resolved to a local file path. */
    data class LocalVideo(val item: VideoItem, val file: java.io.File)

    private val _state = MutableStateFlow<State>(State.Registering)
    val state: StateFlow<State> = _state

    /** Cached in-memory copy of the last resolved playlist. */
    private var lastPlaylist: PlaylistResponse? = null

    /**
     * First-run registration. Safe to call on every launch — short-circuits if
     * we already have a device token. In demo mode (no real backend configured)
     * this is a no-op; the player uses the hardcoded demo playlist instead.
     */
    suspend fun ensureRegistered(joinCode: String) {
        if (DemoMode.isActive) {
            LogBuffer.i(TAG, "Demo mode active — skipping registration.")
            return
        }
        val existing = store.deviceToken.first()
        if (existing != null) {
            Log.d(TAG, "Device already registered — skipping.")
            return
        }
        val deviceId = store.ensureDeviceId()
        val info = DeviceInfo.snapshot(appContext)
        val location = LocationFields(
            region     = store.locRegion.first(),
            city       = store.locCity.first(),
            storeId    = store.locStoreId.first(),
            concept    = store.locConcept.first(),
            floor      = store.locFloor.first(),
            table      = store.locTable.first(),
            screenCode = store.locScreenCode.first(),
        )
        val req = RegisterRequest(
            joinCode = joinCode,
            deviceId = deviceId,
            orientation = info.orientation,
            ramMb = info.ramMb,
            width = info.widthPx,
            height = info.heightPx,
            location = location,
        )
        val resp = api.register(req)
        store.saveRegistration(resp.deviceToken, resp.screenId)
        LogBuffer.i(TAG, "Registered as screen ${resp.screenId}")
    }

    /**
     * Pull the current playlist from the server, download anything missing,
     * evict anything no longer referenced, and advance [state].
     */
    /** Last revision we successfully pulled from the live server. */
    private var lastLiveRevision: Int = -1

    suspend fun refreshPlaylist() {
        // Live LAN demo wins if a server URL is configured.
        val serverUrl = store.liveServerUrl.first()
        if (!serverUrl.isNullOrBlank()) {
            refreshLivePlaylist(serverUrl)
            return
        }
        if (DemoMode.isActive) {
            refreshDemoPlaylist()
            return
        }
        try {
            val etag = store.playlistEtag.first()
            val resp = api.playlist(etag)
            if (resp.code() == 304) {
                Log.d(TAG, "Playlist unchanged (304).")
                // Even if unchanged, make sure current state reflects cached files.
                lastPlaylist?.let { publish(it) }
                return
            }
            if (!resp.isSuccessful) {
                Log.w(TAG, "Playlist fetch failed: HTTP ${resp.code()}")
                return
            }
            val playlist = resp.body() ?: return
            resp.headers()["ETag"]?.let { store.savePlaylistEtag(it) }
            lastPlaylist = playlist

            // Prefetch all videos before publishing so the player never starts on
            // something it can't actually play. Progressive download happens below.
            for (video in playlist.items) {
                try {
                    cache.ensure(video)
                } catch (t: Throwable) {
                    Log.e(TAG, "Cache download failed for ${video.id}", t)
                    runCatching { api.logEvent(DeviceEvent("ERROR", video.id, t.message)) }
                }
            }

            val cap = store.cacheCapBytes.first()
            cache.reconcile(keep = playlist.items.map { it.id }.toSet(), capBytes = cap)

            publish(playlist)
        } catch (t: Throwable) {
            Log.e(TAG, "refreshPlaylist failed", t)
            if (_state.value !is State.Playing) _state.value = State.Error(t)
        }
    }

    /**
     * v0.1.73: drop a corrupt cached video and re-pull a clean copy.
     * Called by the watchdog when ExoPlayer reports a bad source file
     * (truncated/corrupt MP4 → ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE).
     * Purges the bytes, immediately re-publishes WITHOUT the video so the
     * player stops hammering the bad file (publish() drops anything not
     * in the cache), then re-downloads it on the live loop — once it
     * lands clean it rejoins the rotation on the next refresh.
     */
    fun invalidateCachedVideo(videoId: String) {
        liveScope.launch {
            cache.invalidate(videoId)
            _downloadFailures.update { it - videoId }
            // Re-publish current playlist sans the now-missing file.
            lastPlaylist?.let { publish(it) }
            runCatching { refreshPlaylist() }
                .onFailure { LogBuffer.w(TAG, "Re-pull after invalidate($videoId) failed: ${it.message}") }
        }
    }

    private fun publish(playlist: PlaylistResponse) {
        val local = playlist.items
            .mapNotNull { v -> if (cache.has(v.id)) LocalVideo(v, cache.file(v.id)) else null }
        _state.value = when {
            local.isEmpty() -> State.Empty("No cached videos yet")
            else -> State.Playing(local, playlist.revision)
        }
        // Persist a copy of every successful publish() with content so a
        // cold boot can rehydrate without waiting for the network. Saved
        // on the same liveScope to keep DataStore I/O off the caller's
        // coroutine.
        if (playlist.items.isNotEmpty()) {
            liveScope.launch {
                runCatching {
                    store.saveLastPlaylistJson(playlistJson.encodeToString(PlaylistResponse.serializer(), playlist))
                }.onFailure { LogBuffer.w(TAG, "saveLastPlaylistJson failed: ${it.message}") }
            }
        }
    }

    /** Strict Json instance for persisting the last-known playlist — sticks
     *  to defaults so the on-disk format is stable across builds. */
    private val playlistJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Restore the most recent successful playlist from DataStore. Called
     * once at app launch (from [com.smartech.screens.ScreensApp]) before
     * the first network round-trip lands.
     *
     * Only publishes if cached video files for the items still exist on
     * disk. If they don't (clear-cache action, eviction), we fall back
     * to the splash loop until the live poll completes.
     */
    suspend fun rehydrateFromCache() {
        val raw = runCatching { store.lastPlaylistJson() }.getOrNull() ?: return
        if (raw.isBlank()) return
        val playlist = runCatching {
            playlistJson.decodeFromString(PlaylistResponse.serializer(), raw)
        }.getOrElse {
            LogBuffer.w(TAG, "lastPlaylistJson decode failed: ${it.message}")
            return
        }
        // Restore lastLiveRevision so the empty-items guard in
        // refreshLivePlaylist can detect a backward jump (post-deploy wipe).
        val parsedRev = playlist.revision.removePrefix("live-").toIntOrNull()
        if (parsedRev != null) lastLiveRevision = parsedRev
        lastPlaylist = playlist
        publish(playlist)
        // v0.1.19: also hydrate the "intended" playlist flow. publish()
        // only sets the state.Playing items (what the player can
        // actually start), but the staff overlay reads its rows from
        // `intendedPlaylist` — what the server says SHOULD be on this
        // screen, even when some items are still downloading. Before
        // this fix, a cold boot played the cached loop correctly but
        // the admin panel showed "No videos in the playlist" until
        // the next live poll landed, forcing the operator to tap
        // Refresh just to see what's running.
        _intendedPlaylist.value = playlist.items
        val playingCount = (state.value as? State.Playing)?.items?.size ?: 0
        LogBuffer.i(TAG, "Rehydrated playlist from cache — $playingCount of ${playlist.items.size} items playable")

        // v0.1.26: trigger downloads for any rehydrated items that
        // aren't on disk yet. Before this, a cold boot with an empty
        // local cache would loop the splash until the live-sync
        // coroutine got around to its first refresh tick (~5 s of
        // dead air on average). Kicking the downloads off here can
        // shave several seconds off "first frame of real content" on
        // a fresh tablet because we don't wait for registration +
        // settings + the first state poll to land. Best-effort —
        // failures don't surface here; the live-sync loop will
        // retry on its next tick.
        if (playingCount < playlist.items.size) {
            liveScope.launch {
                for (v in playlist.items) {
                    if (cache.has(v.id)) continue
                    runCatching { cache.ensure(v) }
                    // Don't bail on a single failed download — try
                    // the next item, the live loop's redownload
                    // pass picks up retries.
                }
                // Force a re-publish so newly-downloaded items
                // transition into State.Playing without waiting for
                // a live poll.
                lastPlaylist?.let { publish(it) }
            }
        }
    }

    /**
     * v0.1.25: ship any new warnings + errors from LogBuffer to the
     * server. Called from the heartbeat loop. Stateful — keeps a
     * cursor of the last sequence number it shipped, ranges only
     * over entries newer than that. No-op when nothing new.
     *
     * This is the path that lets crash-adjacent warnings (decoder
     * fallback firing, drift-skip catches, "video skipped because
     * too heavy") reach `/api/logs` rather than dying inside the
     * tablet's local LogBuffer. The CrashReporter still catches
     * uncaught exceptions and ships them via `/api/crashes`; this
     * fills in everything below "process died" severity.
     */
    @Volatile
    private var lastShippedLogSeq: Long = 0L

    suspend fun shipRecentWarningsIfPending(deviceId: String) {
        val base = store.liveServerUrl.first()?.trimEnd('/') ?: return
        val (newCursor, entries) = LogBuffer.drainSinceSeq(
            sinceSeq = lastShippedLogSeq,
            minLevel = LogBuffer.Level.W,
        )
        if (entries.isEmpty()) {
            // Still advance the cursor so we don't keep re-scanning
            // the same tail. The drain returned `seq` even when
            // empty so this is monotonic.
            lastShippedLogSeq = newCursor
            return
        }
        val entriesJson = entries.joinToString(",") { e ->
            buildString {
                append("{")
                append("\"time\":${e.time},")
                append("\"level\":${q(e.level.name)},")
                append("\"tag\":${q(e.tag)},")
                append("\"message\":${q(e.message)}")
                if (e.cause != null) append(",\"cause\":${q(e.cause)}")
                append("}")
            }
        }
        val body = buildString {
            append("{")
            append("\"deviceId\":${q(deviceId)},")
            append("\"appVersion\":${q(com.smartech.screens.BuildConfig.VERSION_NAME)},")
            append("\"entries\":[")
            append(entriesJson)
            append("]")
            append("}")
        }
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$base/api/logs")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        lastShippedLogSeq = newCursor
                    }
                    // On failure: leave the cursor so the next tick
                    // retries the same range. Avoids losing entries
                    // to a temporary network blip.
                }
            }.onFailure {
                // Silently swallow — we don't want a log-ship failure
                // to itself generate a warning and create a feedback
                // loop on the next tick.
            }
        }
    }

    /**
     * v0.1.74: ship the ENTIRE current log buffer (all levels) on demand,
     * in response to the CMS "Request logs" (sendLogs) command. The
     * heartbeat shipper only sends W+ since a cursor; this uploads the
     * full recent buffer so an operator can pull a screen's latest logs
     * without waiting for a warning to occur. Reuses POST /api/logs, so
     * the entries land in the same per-device stream the CMS already
     * reads back via GET /api/logs?deviceId=.
     */
    suspend fun shipFullLogSnapshot() {
        val base = store.liveServerUrl.first()?.trimEnd('/') ?: run {
            LogBuffer.w(TAG, "shipFullLogSnapshot skipped — no liveServerUrl set"); return
        }
        val deviceId = store.ensureDeviceId()
        val entries = LogBuffer.snapshot()
        if (entries.isEmpty()) return
        val entriesJson = entries.joinToString(",") { e ->
            buildString {
                append("{")
                append("\"time\":${e.time},")
                append("\"level\":${q(e.level.name)},")
                append("\"tag\":${q(e.tag)},")
                append("\"message\":${q(e.message)}")
                if (e.cause != null) append(",\"cause\":${q(e.cause)}")
                append("}")
            }
        }
        val body = buildString {
            append("{")
            append("\"deviceId\":${q(deviceId)},")
            append("\"appVersion\":${q(com.smartech.screens.BuildConfig.VERSION_NAME)},")
            append("\"entries\":[$entriesJson]")
            append("}")
        }
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$base/api/logs")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { r ->
                    if (r.isSuccessful) LogBuffer.i(TAG, "Shipped ${entries.size} log entries on request")
                    else LogBuffer.w(TAG, "Log snapshot POST HTTP ${r.code}")
                }
            }.onFailure { LogBuffer.w(TAG, "Log snapshot ship failed: ${it.message}") }
        }
    }

    /**
     * v0.1.21: ship any crash reports the CrashReporter spooled to
     * disk on the previous run. Runs once on launch, after the
     * server URL is known. Each report is POSTed to /api/crashes;
     * on a 2xx the local file is deleted, on anything else we stop
     * and try again next launch (avoids stampedes when the server
     * is unreachable). The body is just the JSON the reporter
     * wrote — no transformation, the server validates on its end.
     */
    suspend fun drainCrashesIfPending() {
        val base = store.liveServerUrl.first()?.trimEnd('/') ?: return
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            com.smartech.screens.util.CrashReporter.drainTo { record ->
                runCatching {
                    val body = kotlinx.serialization.json.Json.encodeToString(
                        com.smartech.screens.util.CrashReporter.CrashRecord.serializer(),
                        record,
                    )
                    val req = Request.Builder()
                        .url("$base/api/crashes")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                    httpClient.newCall(req).execute().use { r -> r.isSuccessful }
                }.getOrElse { false }
            }
        }
    }

    /** Best-effort heartbeat. Silently swallows network errors. */
    suspend fun ping(appVersion: String) {
        runCatching {
            api.ping(
                PingRequest(
                    status = when (_state.value) {
                        is State.Playing -> "ONLINE"
                        is State.Registering -> "UPDATING"
                        is State.Empty -> "ONLINE"
                        is State.Error -> "ERROR"
                    },
                    appVersion = appVersion,
                    cachedVideos = cache.cachedIds(),
                    cacheBytes = cache.totalBytes(),
                    freeStorageBytes = appContext.filesDir.usableSpace,
                )
            )
        }.onFailure { Log.w(TAG, "ping failed: ${it.message}") }
    }

    suspend fun refreshSettings() {
        runCatching {
            val etag = store.settingsEtag.first()
            val resp = api.settings(etag)
            if (resp.code() == 304) return@runCatching
            val body = resp.body() ?: return@runCatching
            store.saveSettings(body, resp.headers()["ETag"])
        }.onFailure { Log.w(TAG, "settings fetch failed: ${it.message}") }
    }

    // ── Live LAN demo (laptop running serve.py) ──────────────────────────

    private val liveJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Serializable
    private data class LiveState(
        val revision: Int = 0,
        val items: List<LiveItem> = emptyList(),
        val mixSplash: Boolean = true,
        /** Screen-wide audio override. Default false = muted. When true,
         *  every video plays unmuted regardless of its own defaultUnmute. */
        val audioOn: Boolean = false,
        /** "fast" | "normal" | "slow" — drives the polling interval.
         *  Defaults to "normal" so tablets running against an older
         *  server that doesn't return this field still poll at a sane
         *  60-second cadence. */
        val pollMode: String = "normal",
        /** Legacy boolean kept so an older server that still emits
         *  `lowDataMode` rather than `pollMode` doesn't accidentally
         *  put the tablet into fast mode. Only read when [pollMode]
         *  is absent — see [PollMode.parse]. */
        val lowDataMode: Boolean? = null,
        /** Optional sync-group ID. When set, the server returns a
         *  [playback] block telling this tablet exactly where in the
         *  loop it should be — so every screen in the group stays
         *  aligned. */
        val syncGroup: String? = null,
        /** v0.1.35: every screen in this screen's sync group,
         *  including self. Empty when the screen isn't grouped.
         *  Surfaced in the Device admin "Sync group" card. */
        val syncGroupMembers: List<LiveGroupMember> = emptyList(),
        /** v0.1.36: every distinct sync group across the fleet, so
         *  the tablet's "Join a group" picker has something to render
         *  without making the operator type a group ID. */
        val availableSyncGroups: List<AvailableSyncGroup> = emptyList(),
        val playback: LivePlayback? = null,
        /** Server's wall-clock at response build time. We use it to
         *  correct for transit latency when seeking. */
        val serverNowMs: Long? = null,
        /** v0.1.14: per-screen HDMI mode override. Null = auto.
         *  Non-null is a modeId from this tablet's own supportedModes
         *  list (reported in heartbeat). MainActivity applies it to
         *  the Window's preferredDisplayModeId on change. */
        val displayMode: Int? = null,
        /** v0.1.15: wall-clock cutoff for the calibration overlay
         *  (giant ticking server-time clock). When this is in the
         *  future relative to `correctedNow()` the tablet renders
         *  the overlay on top of the player; otherwise it hides. */
        val calibrateUntilMs: Long? = null,
        val commands: List<LiveCommand> = emptyList(),
        val splashUrl: String? = null,
        val splashName: String? = null,
    )

    /** v0.1.35: a sibling in this screen's sync group. The Device
     *  admin "Sync group" card lists these with an online dot +
     *  the screen code so the operator can see who else is in
     *  step (or isn't). `isSelf` marks the local screen so the
     *  card can render it differently. */
    @Serializable
    data class LiveGroupMember(
        val deviceId: String = "",
        val name: String? = null,
        val online: Boolean = false,
        val screenCode: String? = null,
        val storeId: String? = null,
        val isSelf: Boolean = false,
    )

    /** v0.1.36: lightweight summary of a sync group, used to populate
     *  the "Join a group" picker on the tablet. The server emits one
     *  of these per distinct `syncGroup` value across the fleet. */
    @Serializable
    data class AvailableSyncGroup(
        val id: String = "",
        val memberCount: Int = 0,
        val onlineCount: Int = 0,
    )

    /** Server-emitted group sync info. As of v0.1.12 the only field
     *  the tablet actually uses is [loopStartedAtMs] + [groupId] —
     *  everything else (itemId, position, etc.) is legacy that older
     *  clients consumed before client-side sync math. The tablet
     *  computes the rest locally from the playlist it already has. */
    @Serializable
    data class LivePlayback(
        val itemId: String = "",
        val itemIndex: Int = 0,
        val positionMs: Long = 0,
        val itemStartedAtMs: Long = 0,
        val loopDurationSec: Double = 0.0,
        val groupId: String = "",
        /** Wall-clock ms when this group's loop started. Reset on every
         *  playlist revision bump. The only field needed for sync. */
        val loopStartedAtMs: Long = 0,
    )

    @Serializable
    private data class LiveCommand(
        val command: String,
        val at: Double = 0.0,
    )

    @Serializable
    private data class LiveItem(
        val id: String,
        val title: String,
        val brand: String? = null,
        val product: String? = null,
        val url: String,
        val durationSec: Int? = null,
        val sizeMb: Double? = null,
        /** Per-video "play with audio even if screen is muted" flag.
         *  Set in the CMS Content Library. */
        val defaultUnmute: Boolean = false,
    )

    /** Latest mix-splash flag from the server. Observable so the staff UI can
     *  reflect changes immediately after a toggle. PlayerController reads
     *  the snapshot via the StateFlow .value. */
    private val _mixSplash = MutableStateFlow(true)
    val mixSplashFlow: StateFlow<Boolean> = _mixSplash

    /** Latest screen-wide audio-on flag. False (default) = muted. The
     *  player applies this to ExoPlayer's volume on every item
     *  transition, OR'd with the per-video defaultUnmute flag. */
    private val _audioOn = MutableStateFlow(false)
    val audioOnFlow: StateFlow<Boolean> = _audioOn
    val audioOn: Boolean get() = _audioOn.value
    val mixSplash: Boolean get() = _mixSplash.value

    /** Poll cadence the server has assigned to this screen. The polling
     *  loop reads this on every tick. SLOW also skips the per-location
     *  splash download to save data. Cached videos are unaffected.
     *
     *  v0.1.37: SLOW dropped from 10 min → 5 min. 10 min meant pushed
     *  playlist changes could lag visibly for staff watching the wall;
     *  5 min is still slow enough to be cellular-friendly. Wire value
     *  unchanged so an older server still maps "slow" → SLOW. */
    enum class PollMode(val intervalMs: Long, val wire: String) {
        FAST(10_000L, "fast"),
        NORMAL(60_000L, "normal"),
        SLOW(300_000L, "slow");

        companion object {
            fun parse(wire: String?, legacyLowDataMode: Boolean?): PollMode {
                if (wire != null) {
                    values().firstOrNull { it.wire.equals(wire, ignoreCase = true) }
                        ?.let { return it }
                }
                // Pre-pollMode server response — derive from the boolean.
                return if (legacyLowDataMode == true) SLOW else NORMAL
            }
        }
    }

    // v0.1.37: legacy build starts in SLOW (5 min) by default. Legacy
    // targets old Android 6/7 hardware that's usually on stretched
    // wifi at events — fast polling for those is overkill. Modern
    // build keeps NORMAL (60 s). The server overrides on first
    // heartbeat for a never-seen device based on the same `appFlavor`
    // signal, so this stays in sync if the operator never touches
    // the CMS poll-mode picker.
    private val _pollMode = MutableStateFlow(
        if (com.smartech.screens.BuildConfig.FLAVOR == "legacy") PollMode.SLOW
        else PollMode.NORMAL
    )
    val pollModeFlow: StateFlow<PollMode> = _pollMode
    val pollMode: PollMode get() = _pollMode.value

    /** Latest sync-group ID for the staff UI to surface. Null = not
     *  in a group, so no sync corrections are applied. */
    private val _syncGroup = MutableStateFlow<String?>(null)
    val syncGroupFlow: StateFlow<String?> = _syncGroup

    /** v0.1.35: rest of the screens in this screen's sync group,
     *  with online state. Device admin renders this list. Empty
     *  when not grouped. */
    private val _syncGroupMembers = MutableStateFlow<List<LiveGroupMember>>(emptyList())
    val syncGroupMembersFlow: StateFlow<List<LiveGroupMember>> = _syncGroupMembers

    /** v0.1.36: every distinct sync group on the fleet. Drives the
     *  "Join a group" picker on the tablet's content + admin pages. */
    private val _availableSyncGroups = MutableStateFlow<List<AvailableSyncGroup>>(emptyList())
    val availableSyncGroupsFlow: StateFlow<List<AvailableSyncGroup>> = _availableSyncGroups

    /** v0.1.14: per-screen HDMI mode override pushed from the CMS.
     *  Null = auto (don't touch the box's mode). Non-null is a modeId
     *  that the host tablet previously reported in `supportedModes`
     *  on its heartbeat. MainActivity collects this flow and applies
     *  the modeId to its Window.LayoutParams.preferredDisplayModeId
     *  — Android then asks the HDMI sink to switch at the next
     *  surface attach. The TX3 Mini (boots at 720p but the panel
     *  supports 1080p) is the canonical case for needing this. */
    private val _displayMode = MutableStateFlow<Int?>(null)
    val displayModeFlow: StateFlow<Int?> = _displayMode

    /** v0.1.15: wall-clock cutoff for the calibration overlay. The
     *  PlayerScreen collects this + the corrected server clock and
     *  renders a full-screen giant-time overlay when this is in the
     *  future. Null or past = no overlay. */
    private val _calibrateUntilMs = MutableStateFlow<Long?>(null)
    val calibrateUntilMsFlow: StateFlow<Long?> = _calibrateUntilMs

    /** v0.1.15: latency-corrected server offset, surfaced so the
     *  calibration overlay can render correctedNow() = local + offset.
     *  Mirrors ClockSync.bestOffsetMs but as an observable. */
    private val _serverOffsetMs = MutableStateFlow(0L)
    val serverOffsetMsFlow: StateFlow<Long> = _serverOffsetMs

    /** Group sync anchor — just the loop's wall-clock epoch + the
     *  groupId. The tablet computes "which item should I be on?"
     *  locally using this + its own playlist + durations.
     *
     *  Replaces the v0.1.6-v0.1.11 server-driven `PlaybackSyncHint`
     *  approach (where the server computed the per-tablet "you
     *  should be at item X position Y" block on every poll). The
     *  client-side approach lets sync corrections happen ONLY at
     *  natural item transitions (invisible) instead of every poll
     *  (visible buffer flash). Server poll frequency no longer
     *  affects sync quality. */
    data class GroupSyncAnchor(
        val groupId: String,
        /** Loop epoch in wall-clock ms. Tablet computes its position
         *  in the loop as `(System.currentTimeMillis() + serverOffsetMs - loopStartedAtMs) mod totalDurationMs`. */
        val loopStartedAtMs: Long,
        /** Difference between server clock and local clock, in ms.
         *  Added to System.currentTimeMillis() to get "what time is
         *  it on the server right now." Updated on every poll. */
        val serverOffsetMs: Long,
    )
    private val _groupSync = MutableStateFlow<GroupSyncAnchor?>(null)
    val groupSyncFlow: StateFlow<GroupSyncAnchor?> = _groupSync

    /**
     * NTP-style clock synchronization. Every /api/state response gives
     * us three timestamps:
     *   t1 = local clock just before we send the request
     *   t3 = server clock when the response was built (`serverNowMs`)
     *   t4 = local clock just after the response body arrived
     * Round-trip time RTT = t4 - t1. Assuming the upstream and
     * downstream legs took roughly equal time, the server's clock at
     * the moment t4 happened was `t3 + RTT/2`, so:
     *   offset = (t3 + RTT/2) - t4
     * Adding this offset to `System.currentTimeMillis()` reconstructs
     * the server's wall-clock with the half-RTT bias removed.
     *
     * v0.1.12 used `serverOffsetMs = serverNow - localNow` — a single
     * sample with no latency correction at all. That attributed the
     * entire response transit to clock skew, so two tablets on
     * different network paths landed on different offsets and
     * disagreed about "where in the loop we should be" by the
     * difference of their one-way latencies. The symptom was the ~1 s
     * drift the user reported on v0.1.12.
     *
     * Best-of-N: we keep a small rolling window and use the sample
     * with the smallest RTT as the canonical offset. The classic NTP
     * heuristic — a low-RTT sample tightens the symmetric-latency
     * assumption (the worst-case error in the offset is bounded by
     * ±RTT/2), so the lowest-RTT recent sample is the one we trust.
     * Outlier samples (Wi-Fi retransmit, GC pause, captive-portal
     * redirect, etc.) get ignored automatically.
     */
    private class ClockSync(private val capacity: Int = 8) {
        private data class Sample(val offsetMs: Long, val rttMs: Long)
        private val samples = ArrayDeque<Sample>()

        @Volatile var bestOffsetMs: Long = 0L
            private set
        @Volatile var bestRttMs: Long = Long.MAX_VALUE
            private set
        @Volatile var sampleCount: Int = 0
            private set

        @Synchronized
        fun record(t1Local: Long, t3Server: Long, t4Local: Long): Long {
            // Sanity guards: clock went backwards mid-call, or the
            // server timestamp is obviously wrong (zero / pre-2000).
            if (t4Local < t1Local) return bestOffsetMs
            if (t3Server <= 0L) return bestOffsetMs
            val rtt = (t4Local - t1Local).coerceAtLeast(0L)
            val offset = t3Server + rtt / 2L - t4Local
            samples.addLast(Sample(offset, rtt))
            while (samples.size > capacity) samples.removeFirst()
            // Pick the sample with the smallest RTT in the window.
            var bestOffset = offset
            var bestRtt = rtt
            for (s in samples) {
                if (s.rttMs < bestRtt) {
                    bestRtt = s.rttMs
                    bestOffset = s.offsetMs
                }
            }
            bestOffsetMs = bestOffset
            bestRttMs = bestRtt
            sampleCount = samples.size
            return bestOffset
        }

        @Synchronized
        fun clear() {
            samples.clear()
            bestOffsetMs = 0L
            bestRttMs = Long.MAX_VALUE
            sampleCount = 0
        }
    }
    private val clockSync = ClockSync()

    /** Per-video download progress. Cleared when a download finishes. */
    data class DownloadProgress(
        val videoId: String,
        val bytes: Long,
        val totalBytes: Long?,         // null if Content-Length wasn't sent
        val bytesPerSec: Double,       // rolling average over ~last second
    ) {
        val fraction: Float? get() = totalBytes?.let { if (it > 0) (bytes.toFloat() / it).coerceIn(0f, 1f) else null }
    }
    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads

    /** Video IDs whose most recent download attempt threw. Cleared when
     *  the same ID later succeeds. Drives the red-X badge in the
     *  on-tablet staff playlist view so the operator can tell a real
     *  failure apart from a still-pending download at a glance. */
    private val _downloadFailures = MutableStateFlow<Set<String>>(emptySet())
    val downloadFailures: StateFlow<Set<String>> = _downloadFailures

    /**
     * The user's *intended* playlist — what the server says is on this
     * screen, even when some items are still downloading. Distinct from the
     * `state.Playing.items` flow, which only reports what the player can
     * actually start playing right now.
     */
    private val _intendedPlaylist = MutableStateFlow<List<VideoItem>>(emptyList())
    val intendedPlaylist: StateFlow<List<VideoItem>> = _intendedPlaylist

    /**
     * Local file for a remote splash chosen by the server (per-location
     * resolution). When null, [PlayerController] falls back to the bundled
     * `res/raw/splash.mp4`. PlayerScreen observes this and forwards to the
     * controller on change.
     */
    private val _remoteSplashFile = MutableStateFlow<java.io.File?>(null)
    val remoteSplashFile: StateFlow<java.io.File?> = _remoteSplashFile
    private var lastSplashUrl: String? = null

    /** Connection state for the live demo server. Surfaced to onboarding +
     *  device admin rails so the user can see at a glance whether they're
     *  reaching the laptop. */
    enum class ConnectionStatus { DISCONNECTED, CONNECTING, ONLINE, OFFLINE }
    private val _connection = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connection: StateFlow<ConnectionStatus> = _connection

    /**
     * Fetch /api/state?screenId=<deviceId> from the LAN server. Drains any
     * pending commands the CMS queued, downloads new media when revision
     * bumps, fires a rich heartbeat on every tick.
     */
    private suspend fun refreshLivePlaylist(serverUrl: String) {
        val base = serverUrl.trimEnd('/')
        val deviceId = store.ensureDeviceId()
        // Capture t1/t4 around the round-trip so we can debias the
        // server-clock offset for one-way latency. See [ClockSync].
        // t1 is taken as late as possible before the byte goes on the
        // wire; t4 as soon as possible after the body has been read.
        var t1Local = 0L
        var t4Local = 0L
        val resp = runCatching {
            val req = Request.Builder().url("$base/api/state?screenId=$deviceId").build()
            t1Local = System.currentTimeMillis()
            httpClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                val body = r.body?.string() ?: ""
                t4Local = System.currentTimeMillis()
                body
            }
        }.getOrElse {
            LogBuffer.w(TAG, "Live state fetch failed: ${it.message}")
            _connection.value = ConnectionStatus.OFFLINE
            return
        }
        val state = runCatching { liveJson.decodeFromString<LiveState>(resp) }
            .getOrElse {
                LogBuffer.w(TAG, "Live state parse failed: ${it.message}")
                _connection.value = ConnectionStatus.OFFLINE
                return
            }

        // Feed the round-trip into the clock-sync helper. Best-of-N
        // smoothing happens inside; we just hand it the three
        // timestamps and pull the latency-corrected offset out
        // wherever we need it (below, in the GroupSyncAnchor).
        state.serverNowMs?.let { t3 ->
            clockSync.record(t1Local = t1Local, t3Server = t3, t4Local = t4Local)
        }
        // Reachable + parseable. Flag online; the rest of this method may
        // do downloads but those don't change the "is the server reachable"
        // signal.
        _connection.value = ConnectionStatus.ONLINE

        // Update splash flag — observable so the staff UI reflects it instantly.
        if (state.mixSplash != _mixSplash.value) {
            _mixSplash.value = state.mixSplash
            LogBuffer.i(TAG, "Mix splash → ${state.mixSplash}")
            // Re-publish current playlist so the controller picks up the new flag.
            lastPlaylist?.let { publish(it) }
        }

        // Screen-wide audio flag. Updated silently — no re-publish
        // because PlayerController re-evaluates volume on each item
        // transition via the audioOn flow + the per-item flag, so a
        // change here picks up on the next video without restarting
        // playback.
        if (state.audioOn != _audioOn.value) {
            _audioOn.value = state.audioOn
            LogBuffer.i(TAG, "Audio → ${if (state.audioOn) "on" else "off"}")
        }

        // Poll mode. The polling loop reads this on every tick to pick
        // the sleep interval; SLOW also skips the per-location splash
        // download below. No re-publish — the change picks up on the
        // next tick.
        val nextPollMode = PollMode.parse(state.pollMode, state.lowDataMode)
        if (nextPollMode != _pollMode.value) {
            _pollMode.value = nextPollMode
            LogBuffer.i(TAG, "Poll mode → ${nextPollMode.wire} (${nextPollMode.intervalMs / 1000}s)")
        }

        // Sync group ID. Surface to the staff UI so admins can confirm
        // membership at a glance. Null = standalone playback.
        if (state.syncGroup != _syncGroup.value) {
            _syncGroup.value = state.syncGroup
            LogBuffer.i(TAG, "Sync group → ${state.syncGroup ?: "(none)"}")
        }
        // v0.1.35: full member list for the Device admin Sync group
        // card. Update unconditionally — list contents may shift even
        // when the group id doesn't (a sibling came online, etc.).
        _syncGroupMembers.value = state.syncGroupMembers
        // v0.1.36: fleet-wide list of groups for the Join picker.
        _availableSyncGroups.value = state.availableSyncGroups

        // Display mode override. MainActivity collects this flow and
        // applies preferredDisplayModeId on change. We only mutate
        // the flow on a real change so MainActivity's collector
        // doesn't churn the window attributes every poll.
        if (state.displayMode != _displayMode.value) {
            _displayMode.value = state.displayMode
            LogBuffer.i(TAG, "Display mode → ${state.displayMode ?: "(auto)"}")
        }

        // v0.1.15: calibration overlay window. Pushed straight through
        // — the PlayerScreen collects this flow + a ticker and hides
        // the overlay automatically the moment the wall-clock catches
        // up to the cutoff, so we don't need to write null back here.
        if (state.calibrateUntilMs != _calibrateUntilMs.value) {
            _calibrateUntilMs.value = state.calibrateUntilMs
            if (state.calibrateUntilMs != null) {
                LogBuffer.i(TAG, "Calibrate until ${state.calibrateUntilMs}")
            }
        }

        // Expose the latency-corrected offset so the calibration
        // overlay can render correctedNow(). Only updates when the
        // offset actually moves, which is rarely — keeps observers
        // cheap.
        val freshOffset = if (clockSync.sampleCount > 0) clockSync.bestOffsetMs else 0L
        if (freshOffset != _serverOffsetMs.value) {
            _serverOffsetMs.value = freshOffset
        }

        // Group sync anchor. The tablet does all "which item should I
        // be on right now?" math locally using this epoch + the items
        // it already has. The legacy `playback` block also carries an
        // explicit itemId/position from the server — we ignore that
        // here and rely on local computation triggered at media-item
        // transitions, which is when seeks are invisible.
        val playback = state.playback
        if (playback != null && state.syncGroup != null && playback.loopStartedAtMs > 0) {
            // Pull the latency-corrected offset out of the clock-sync
            // helper. `bestOffsetMs` is the smallest-RTT sample in the
            // recent window — the one whose symmetric-latency
            // assumption (the math assumes upstream and downstream
            // legs took equal time) is tightest. Falls back to a
            // single-sample compute when we don't have any clock-sync
            // history yet (very first poll after launch).
            val serverOffsetMs = if (clockSync.sampleCount > 0) {
                clockSync.bestOffsetMs
            } else {
                val sn = state.serverNowMs
                if (sn != null) sn - System.currentTimeMillis() else 0L
            }
            val prev = _groupSync.value
            val next = GroupSyncAnchor(
                groupId = playback.groupId.ifEmpty { state.syncGroup },
                loopStartedAtMs = playback.loopStartedAtMs,
                serverOffsetMs = serverOffsetMs,
            )
            _groupSync.value = next
            // One concise diagnostic line per poll so we can see if
            // sync is healthy without scraping logs. RTT under ~80 ms
            // on Wi-Fi is normal; if best-RTT stays high something is
            // off (CDN cold path, congested AP, etc.).
            if (prev == null || prev.serverOffsetMs != next.serverOffsetMs) {
                LogBuffer.i(
                    TAG,
                    "Clock sync — offset=${serverOffsetMs}ms, best-rtt=${clockSync.bestRttMs}ms, samples=${clockSync.sampleCount}",
                )
            }
        } else {
            _groupSync.value = null
        }

        // Per-location splash. Download (or clear) when the URL changes.
        // Skipped in SLOW poll mode — the per-location splash is ~70 MB
        // for the Smartech 4K asset; the APK-bundled splash is shown
        // instead so a cellular / metered install doesn't burn data on
        // it. FAST and NORMAL fetch as usual.
        if (_pollMode.value != PollMode.SLOW) {
            ensureRemoteSplash(base, state.splashUrl)
        }

        // Execute any pending commands the CMS queued. The server already
        // drained them in the GET response so we won't see them twice.
        for (cmd in state.commands) executeCommand(cmd.command)

        // No change to playlist? Bail — heartbeats are handled by the
        // dedicated [startHeartbeatLoop] coroutine, no need to fire one
        // from here.
        if (state.revision == lastLiveRevision && _state.value is State.Playing) {
            return
        }

        // v0.1.23: filter out videos that would overwhelm this device's
        // decoder. An uncompressed 202 MB / 30 s clip is ~54 Mbps — way
        // past what a 1 GB Amlogic box (TX3 Mini) can chew. Crashes the
        // activity hard. Better to skip the offending item with a
        // visible warning than die mid-playback.
        //
        // Filter ranges over LiveItem because that's where sizeMb +
        // durationSec come from the server. Items without size info
        // (legacy library entries, freshly-uploaded videos before the
        // probe runs) pass through — we'd rather attempt-and-watchdog
        // than refuse-and-blank.
        val deviceInfo = com.smartech.screens.util.DeviceInfo.snapshot(appContext)
        val safeMbps = deviceInfo.safeBitrateMbps.toDouble()
        val rawItems = state.items
        val safeRaw = mutableListOf<LiveItem>()
        val skipped = mutableListOf<Pair<LiveItem, Double>>()
        for (li in rawItems) {
            val sizeMb = li.sizeMb
            val durSec = li.durationSec?.toDouble()
            if (sizeMb == null || durSec == null || durSec <= 0.0) {
                safeRaw += li
                continue
            }
            // Bits per second = bytes-per-second × 8, and a "megabit"
            // is 1_000_000 bits (decimal); sizeMb here is decimal MB
            // (per scan-videos.py + the upload endpoint). So
            // bitrate_Mbps = sizeMb * 8 / durationSec exactly.
            val mbps = (sizeMb * 8.0) / durSec
            if (mbps <= safeMbps) {
                safeRaw += li
            } else {
                skipped += li to mbps
            }
        }
        if (skipped.isNotEmpty()) {
            for ((li, mbps) in skipped) {
                LogBuffer.w(
                    TAG,
                    "Skipped heavy video '${li.title}' — " +
                        "${"%.1f".format(mbps)} Mbps exceeds ${deviceInfo.safeBitrateMbps} Mbps " +
                        "safe ceiling for ${deviceInfo.decoderTier}-tier device " +
                        "(RAM ${deviceInfo.ramMb} MB). Compress the source or push to a higher-spec screen.",
                )
            }
        }

        val items = safeRaw.map { it.toVideoItem(base) }

        if (items.isEmpty()) {
            // Distinguish "the server has actively cleared this screen"
            // from "the server lost its state" (e.g. a Cloud Run redeploy
            // before /data/per_screen.json persistence shipped — see
            // serve.py's _load_state_from_disk). The first warrants
            // dropping to splash; the second should keep playing what we
            // have until the server confirms a real change.
            //
            // Heuristic: an empty playlist is only trusted when the
            // server's revision is BOTH >0 AND strictly greater than the
            // last revision we acted on. Revision 0 = fresh in-memory
            // state; a non-monotonic step backwards means state was lost.
            val trustsTheClear =
                state.revision > 0 && state.revision > lastLiveRevision
            if (trustsTheClear) {
                // Genuine clear — wipe the intent flow too so the staff
                // overlay shows the empty state.
                _intendedPlaylist.value = emptyList()
                _state.value = State.Empty("Waiting for content from the CMS")
                runCatching { store.clearLastPlaylistJson() }
                lastPlaylist = null
                lastLiveRevision = state.revision
            } else {
                LogBuffer.w(
                    TAG,
                    "Server returned empty items at rev ${state.revision} " +
                        "(local rev $lastLiveRevision) — keeping last good playlist",
                )
                // Re-publish whatever we last had so the player doesn't
                // accidentally fall through to splash on a subsequent
                // state-flow recomposition. Restore the intent flow too
                // — without this, the staff overlay's playlist view
                // would show empty even while the player keeps looping
                // the cached items, and the user could then accidentally
                // push that empty list back to the server.
                val cached = lastPlaylist
                if (cached != null) {
                    publish(cached)
                    _intendedPlaylist.value = cached.items
                }
            }
            return
        }

        // Non-empty response — surface to the staff overlay immediately
        // so the new playlist is visible before the bytes finish
        // downloading.
        _intendedPlaylist.value = items

        LogBuffer.i(TAG, "Live revision ${state.revision} — ${items.size} items")
        for (v in items) {
            try {
                if (!cache.has(v.id)) {
                    LogBuffer.i(TAG, "Downloading ${v.id} from ${v.url}")
                    val startNs = System.nanoTime()
                    var lastReportNs = startNs
                    var lastReportBytes = 0L
                    var rollingBps = 0.0
                    cache.ensure(v) { bytes, total ->
                        val now = System.nanoTime()
                        val elapsedSec = (now - lastReportNs) / 1e9
                        // Smooth the speed reading — instantaneous values
                        // bounce wildly with chunked reads. Update once a
                        // second and exponentially smooth.
                        if (elapsedSec >= 0.5) {
                            val instantBps = (bytes - lastReportBytes) / elapsedSec
                            rollingBps = if (rollingBps == 0.0) instantBps
                                         else rollingBps * 0.5 + instantBps * 0.5
                            lastReportNs = now
                            lastReportBytes = bytes
                        }
                        _downloads.update { map ->
                            map + (v.id to DownloadProgress(
                                videoId     = v.id,
                                bytes       = bytes,
                                totalBytes  = total,
                                bytesPerSec = rollingBps,
                            ))
                        }
                    }
                    // Drop progress entry once the file is on disk.
                    _downloads.update { it - v.id }
                    LogBuffer.i(TAG, "Cached ${v.id}")
                }
                // Cleared on success — drop any earlier failure flag.
                _downloadFailures.update { it - v.id }
            } catch (t: Throwable) {
                LogBuffer.w(TAG, "Live download failed for ${v.id}: ${t.message}")
                _downloads.update { it - v.id }
                _downloadFailures.update { it + v.id }
            }
        }
        val cap = store.cacheCapBytes.first()
        cache.reconcile(keep = items.map { it.id }.toSet(), capBytes = cap)

        val playlist = PlaylistResponse(
            screenId = store.locScreenCode.first() ?: "live",
            revision = "live-${state.revision}",
            tier = "MID_720P",
            items = items,
        )
        lastPlaylist = playlist
        publish(playlist)
        lastLiveRevision = state.revision
        // Heartbeats fire on their own 10 s loop (startHeartbeatLoop);
        // no need to piggy-back one here.
    }

    // ── Tablet → server: staff overlay actions ──────────────────────────

    /**
     * Replace the live server's playlist for THIS device with [items].
     * Used by the on-tablet staff overlay's playlist editor — delete a video,
     * reorder, etc. Body sent verbatim is the new full playlist.
     */
    suspend fun pushPlaylistToServer(items: List<VideoItem>, mode: String = "replace") {
        LogBuffer.i(TAG, "pushPlaylistToServer mode=$mode items=${items.size}")
        // v0.1.48: optimistic local update for the on-tablet Add-content
        // flow. As soon as the staff picks a video, surface it in the
        // playlist view with the existing download-progress badge —
        // staff get "added → downloading" feedback within one frame
        // instead of waiting up to a full poll interval for the server
        // round-trip + /api/state response. Replace mode mirrors the
        // server's contract (full overwrite). The next /api/state poll
        // reconciles either way; if the push failed server-side we just
        // re-render whatever the server says.
        if (mode == "append" && items.isNotEmpty()) {
            val existingIds = _intendedPlaylist.value.map { it.id }.toSet()
            val fresh = items.filter { it.id !in existingIds }
            if (fresh.isNotEmpty()) {
                _intendedPlaylist.value = _intendedPlaylist.value + fresh
                // Kick off the download immediately so the row's
                // progress badge starts ticking right away — same
                // behaviour as a server-driven playlist update. The
                // detailed progress reporting in refreshLivePlaylist
                // takes over once the server confirms; this is just
                // the head start so the staff don't watch a static
                // spinner for 60 s on Normal poll mode.
                for (v in fresh) {
                    liveScope.launch {
                        runCatching { cache.ensure(v) }
                            .onFailure {
                                LogBuffer.w(TAG, "Optimistic pre-cache failed for ${v.id}: ${it.message}")
                            }
                    }
                }
            }
        } else if (mode == "replace") {
            _intendedPlaylist.value = items
        }

        val base = store.liveServerUrl.first()?.trimEnd('/')
        if (base.isNullOrBlank()) {
            LogBuffer.w(TAG, "pushPlaylistToServer skipped — no liveServerUrl set")
            return
        }
        val deviceId = store.ensureDeviceId()
        val itemsJson = items.joinToString(",") { v ->
            buildString {
                append("{")
                append("\"id\":${q(v.id)},")
                append("\"title\":${q(v.title)},")
                v.brand?.let { append("\"brand\":${q(it)},") }
                v.product?.let { append("\"product\":${q(it)},") }
                v.durationSec?.let { append("\"durationSec\":$it,") }
                append("\"url\":${q(v.url)}")
                append("}")
            }
        }
        val body = """{"items":[$itemsJson],"mode":"$mode"}"""
        val url = "$base/api/screens/${urlEncode(deviceId)}/playlist"
        // Force IO — call sites are often Compose's main-thread scope, where
        // `execute()` would throw NetworkOnMainThreadException (with a null
        // message — that's the Android quirk that masked this earlier).
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        LogBuffer.w(TAG, "pushPlaylistToServer HTTP ${r.code} from $url")
                    } else {
                        LogBuffer.i(TAG, "pushPlaylistToServer OK")
                    }
                }
                lastLiveRevision = -1
            }.onFailure {
                LogBuffer.w(TAG, "pushPlaylistToServer failed: ${it.javaClass.simpleName}: ${it.message ?: "(no message)"}", it)
            }
        }
    }

    /** Toggle the per-screen splash flag on the server. */
    suspend fun setMixSplashOnServer(value: Boolean) {
        LogBuffer.i(TAG, "setMixSplashOnServer → $value")
        // Optimistic local update so the toggle's visual flips instantly,
        // before the next polling tick.
        _mixSplash.value = value
        lastPlaylist?.let { publish(it) }

        val base = store.liveServerUrl.first()?.trimEnd('/')
        if (base.isNullOrBlank()) {
            LogBuffer.w(TAG, "setMixSplashOnServer skipped — no liveServerUrl set")
            return
        }
        val deviceId = store.ensureDeviceId()
        val body = """{"mixSplash":$value}"""
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$base/api/screens/${urlEncode(deviceId)}/mix-splash")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) LogBuffer.w(TAG, "setMixSplashOnServer HTTP ${r.code}")
                    else LogBuffer.i(TAG, "setMixSplashOnServer OK")
                }
                lastLiveRevision = -1
            }.onFailure {
                LogBuffer.w(TAG, "setMixSplashOnServer failed: ${it.javaClass.simpleName}: ${it.message ?: "(no message)"}", it)
            }
        }
    }

    /** Toggle the per-screen audio mute/unmute flag on the server. The
     *  global flag overrides "default to mute"; if it's off the player
     *  falls back to the per-video defaultUnmute setting from the
     *  Content Library. */
    suspend fun setAudioOnServer(value: Boolean) {
        LogBuffer.i(TAG, "setAudioOnServer → $value")
        // Optimistic local update — UI + player react instantly.
        _audioOn.value = value
        lastPlaylist?.let { publish(it) }

        val base = store.liveServerUrl.first()?.trimEnd('/')
        if (base.isNullOrBlank()) {
            LogBuffer.w(TAG, "setAudioOnServer skipped — no liveServerUrl set")
            return
        }
        val deviceId = store.ensureDeviceId()
        val body = """{"audioOn":$value}"""
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$base/api/screens/${urlEncode(deviceId)}/audio")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) LogBuffer.w(TAG, "setAudioOnServer HTTP ${r.code}")
                    else LogBuffer.i(TAG, "setAudioOnServer OK")
                }
                lastLiveRevision = -1
            }.onFailure {
                LogBuffer.w(TAG, "setAudioOnServer failed: ${it.javaClass.simpleName}: ${it.message ?: "(no message)"}", it)
            }
        }
    }

    /** Set this screen's poll cadence on the server. Optimistic local
     *  update means the next tick already uses the new interval; the
     *  POST also persists the choice so it survives a reboot. */
    suspend fun setPollModeOnServer(mode: PollMode) {
        LogBuffer.i(TAG, "setPollModeOnServer → ${mode.wire}")
        _pollMode.value = mode

        val base = store.liveServerUrl.first()?.trimEnd('/')
        if (base.isNullOrBlank()) {
            LogBuffer.w(TAG, "setPollModeOnServer skipped — no liveServerUrl set")
            return
        }
        val deviceId = store.ensureDeviceId()
        val body = """{"pollMode":${q(mode.wire)}}"""
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$base/api/screens/${urlEncode(deviceId)}/poll-mode")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) LogBuffer.w(TAG, "setPollModeOnServer HTTP ${r.code}")
                    else LogBuffer.i(TAG, "setPollModeOnServer OK")
                }
                lastLiveRevision = -1
            }.onFailure {
                LogBuffer.w(TAG, "setPollModeOnServer failed: ${it.javaClass.simpleName}: ${it.message ?: "(no message)"}", it)
            }
        }
    }

    /** v0.1.38: pull custom stores from `/api/stores` and merge them
     *  into [LocationTaxonomy]. Called once on startLiveSync — failures
     *  leave the built-in list intact. The endpoint is unauthenticated
     *  so this works pre-login too. */
    private suspend fun refreshStoresFromServer(base: String) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("$base/api/stores").build()
                httpClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        LogBuffer.w(TAG, "refreshStoresFromServer HTTP ${r.code}")
                        return@use
                    }
                    val raw = r.body?.string().orEmpty()
                    val parsed = liveJson.decodeFromString(StoresEnvelope.serializer(), raw)
                    val mapped = parsed.stores.mapNotNull { s ->
                        val id = s.id ?: return@mapNotNull null
                        val name = s.name ?: return@mapNotNull null
                        val city = s.city ?: return@mapNotNull null
                        LocationTaxonomy.Store(
                            id = id,
                            name = name,
                            address = s.address.orEmpty(),
                            cityCode = city,
                        )
                    }
                    LocationTaxonomy.setCustomStores(mapped)
                    LogBuffer.i(TAG, "Loaded ${mapped.size} custom stores from server")
                }
            }.onFailure {
                LogBuffer.w(TAG, "refreshStoresFromServer failed: ${it.message}")
            }
        }
    }

    @Serializable
    private data class StoresEnvelope(val stores: List<RemoteStore> = emptyList())

    @Serializable
    private data class RemoteStore(
        val id: String? = null,
        val name: String? = null,
        val address: String? = null,
        val city: String? = null,
    )

    /** v0.1.29: tablet-driven calibration trigger for the on-device
     *  command palette. Hits the server's own
     *  `/api/sync-groups/<deviceId>/calibrate` endpoint as if from
     *  the CMS — the server treats a lone deviceId as a one-screen
     *  "group" and writes `calibrateUntilMs` straight onto the
     *  per-screen record. The next poll surfaces it in /api/state
     *  and [calibrateUntilMsFlow] flips. No new server code; this
     *  is just a tablet-local convenience caller.
     *
     *  Best-effort — failures log but don't throw, since this is
     *  fired from a UI button and the user can just press it again.
     */
    suspend fun triggerLocalCalibration(durationSec: Int) {
        val base = store.liveServerUrl.first()?.trimEnd('/') ?: return
        val deviceId = store.ensureDeviceId()
        val body = """{"durationSec":$durationSec}"""
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$base/api/sync-groups/${urlEncode(deviceId)}/calibrate")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        LogBuffer.w(TAG, "triggerLocalCalibration HTTP ${r.code}")
                    } else {
                        LogBuffer.i(TAG, "Local calibration triggered (${durationSec}s)")
                    }
                }
                // Bust the rev so the next refresh re-pulls /api/state
                // immediately instead of returning early on the
                // unchanged-revision short-circuit.
                lastLiveRevision = -1
            }.onFailure {
                LogBuffer.w(TAG, "triggerLocalCalibration failed: ${it.message}")
            }
        }
        // Force a poll right now so the new calibrateUntilMs lands
        // without waiting for the next scheduled tick. refreshNow
        // pumps the live-sync loop's coroutine.
        refreshNow()
    }

    /** Trigger an immediate playlist refresh from any UI surface
     *  (staff overlay's "Refresh now" button or the CMS-side refresh
     *  command). Fires off the live polling loop's queue so it doesn't
     *  wait for the next sleep tick — useful when the tablet is in
     *  Slow mode (5 min between regular polls) and someone wants to
     *  see their push land right now. */
    fun refreshNow() {
        LogBuffer.i(TAG, "refreshNow() requested")
        liveScope.launch {
            // Reset revision so the response is treated as new even
            // when the server returns the same one — useful for the
            // CMS-side "Refresh now" command flow where the change may
            // already have landed in a previous poll.
            lastLiveRevision = -1
            runCatching { refreshPlaylist() }
                .onFailure { LogBuffer.w(TAG, "refreshNow() tick failed: ${it.message}") }
        }
    }

    /** v0.1.66: manual content-library (brands + videos) re-pull, fired
     *  from the staff overlay's "Refresh content library" button. The
     *  same pull also runs automatically on every app startup (see
     *  [startLiveSync]) so a relaunch — including the one after an APK
     *  update — always lands the latest library. */
    fun refreshLibraryNow() {
        LogBuffer.i(TAG, "refreshLibraryNow() requested")
        liveScope.launch {
            val url = store.liveServerUrl.first()?.trimEnd('/')
            runCatching { remoteLibrary.refresh(url) }
                .onFailure { LogBuffer.w(TAG, "refreshLibraryNow() failed: ${it.message}") }
        }
    }

    /** Push a new HDMI mode override to the server. `null` clears
     *  the override (== auto). Non-null must match one of the modeIds
     *  this tablet reported in supportedModes — the server stores
     *  whatever it's given but the tablet itself validates against
     *  the current supportedModes list when applying. Optimistic
     *  local update means MainActivity's collector fires immediately,
     *  before the next poll round-trips. */
    suspend fun setDisplayModeOnServer(modeId: Int?) {
        LogBuffer.i(TAG, "setDisplayModeOnServer → ${modeId ?: "(auto)"}")
        _displayMode.value = modeId

        val base = store.liveServerUrl.first()?.trimEnd('/')
        if (base.isNullOrBlank()) {
            LogBuffer.w(TAG, "setDisplayModeOnServer skipped — no liveServerUrl set")
            return
        }
        val deviceId = store.ensureDeviceId()
        val bodyValue = modeId?.toString() ?: "null"
        val body = """{"displayMode":$bodyValue}"""
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$base/api/screens/${urlEncode(deviceId)}/display-mode")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) LogBuffer.w(TAG, "setDisplayModeOnServer HTTP ${r.code}")
                    else LogBuffer.i(TAG, "setDisplayModeOnServer OK")
                }
                lastLiveRevision = -1
            }.onFailure {
                LogBuffer.w(TAG, "setDisplayModeOnServer failed: ${it.javaClass.simpleName}: ${it.message ?: "(no message)"}", it)
            }
        }
    }

    /** Set this screen's sync-group membership on the server. Null or
     *  blank string detaches the screen from any group. */
    suspend fun setSyncGroupOnServer(value: String?) {
        val normalised = value?.trim()?.ifBlank { null }
        LogBuffer.i(TAG, "setSyncGroupOnServer → ${normalised ?: "(none)"}")
        _syncGroup.value = normalised

        val base = store.liveServerUrl.first()?.trimEnd('/')
        if (base.isNullOrBlank()) {
            LogBuffer.w(TAG, "setSyncGroupOnServer skipped — no liveServerUrl set")
            return
        }
        val deviceId = store.ensureDeviceId()
        // null encodes as JSON null so the server clears the field.
        val bodyValue = if (normalised == null) "null" else q(normalised)
        val body = """{"syncGroup":$bodyValue}"""
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$base/api/screens/${urlEncode(deviceId)}/sync-group")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) LogBuffer.w(TAG, "setSyncGroupOnServer HTTP ${r.code}")
                    else LogBuffer.i(TAG, "setSyncGroupOnServer OK")
                }
                lastLiveRevision = -1
            }.onFailure {
                LogBuffer.w(TAG, "setSyncGroupOnServer failed: ${it.javaClass.simpleName}: ${it.message ?: "(no message)"}", it)
            }
        }
    }

    private fun urlEncode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * Restart the player. Old implementation used AlarmManager.set +
     * killProcess, but on Android 11+ pending alarms get cancelled
     * when the scheduling app's process dies and the bundled-OS
     * "background activity launch" restrictions kept the alarm from
     * actually firing on some TV boxes. Result: the CMS Reboot
     * button looked like it killed the app but the app never came
     * back.
     *
     * New implementation: launch a fresh MainActivity with
     * CLEAR_TASK + NEW_TASK before the old one dies. The activity
     * stack is wiped, ExoPlayer / repository state is rebuilt; the
     * JVM keeps running so the relaunch is reliable. For a "true"
     * process restart, use Clear cache + Reboot.
     */
    /** Public form for the playback watchdog (and any other nuclear-
     *  option recovery path that needs to bounce the activity without
     *  killing the JVM). */
    fun scheduleSelfRestart() = doScheduleSelfRestart()

    private fun doScheduleSelfRestart() {
        val intent = android.content.Intent(appContext, com.smartech.screens.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        try {
            appContext.startActivity(intent)
        } catch (e: Throwable) {
            LogBuffer.w(TAG, "scheduleSelfRestart startActivity failed: ${e.message}", e)
        }
    }

    /**
     * Resolve the per-screen splash. Downloads to `<filesDir>/splash/<hash>.mp4`
     * when the URL changes. Setting [_remoteSplashFile] to null tells the
     * player to use the bundled splash instead.
     */
    private suspend fun ensureRemoteSplash(base: String, relativeOrAbsoluteUrl: String?) {
        val absUrl = relativeOrAbsoluteUrl?.let { url ->
            when {
                url.startsWith("http://") || url.startsWith("https://") -> url
                url.startsWith("/") -> base + url
                else -> "$base/$url"
            }
        }
        if (absUrl == lastSplashUrl) return
        lastSplashUrl = absUrl
        if (absUrl == null) {
            _remoteSplashFile.value = null
            LogBuffer.i(TAG, "Splash → bundled (no remote URL)")
            return
        }
        // Stable filename from URL hash so we can detect cache hits.
        val splashRoot = java.io.File(appContext.filesDir, "splash").apply { mkdirs() }
        val safeName = absUrl.hashCode().toUInt().toString(16) + ".mp4"
        val target = java.io.File(splashRoot, safeName)
        if (target.exists() && target.length() > 0) {
            _remoteSplashFile.value = target
            LogBuffer.i(TAG, "Splash → cached $safeName")
            return
        }
        // Download.
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(absUrl).build()
                httpClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    val body = r.body ?: throw IllegalStateException("Empty splash body")
                    val partial = java.io.File(splashRoot, "$safeName.part")
                    java.io.FileOutputStream(partial).use { out ->
                        body.byteStream().use { it.copyTo(out) }
                    }
                    if (!partial.renameTo(target)) {
                        partial.delete()
                        throw IllegalStateException("Could not save splash file")
                    }
                }
                _remoteSplashFile.value = target
                LogBuffer.i(TAG, "Splash → downloaded $safeName from $absUrl")
            }.onFailure {
                LogBuffer.w(TAG, "Splash download failed: ${it.message}", it)
                // Reset so next tick retries.
                lastSplashUrl = null
            }
        }
    }

    /** Apply a CMS-issued command. Best-effort; logs on success and failure. */
    private suspend fun executeCommand(command: String) {
        LogBuffer.w(TAG, "Command received: $command")
        when (command) {
            "reboot" -> {
                LogBuffer.w(TAG, "Reboot — restarting activity")
                // Let the heartbeat ack flush before we yank the
                // activity. 200ms is enough for the OkHttp call to
                // commit; longer than 200ms isn't worth a stuck UI.
                kotlinx.coroutines.delay(200)
                scheduleSelfRestart()
                // No more killProcess. On Android 11+ alarms scheduled
                // by the dying process get cancelled, so the old
                // "kill + relaunch via AlarmManager" pattern was leaving
                // tablets dark. CLEAR_TASK in the new activity intent
                // resets the back stack; the JVM continues, repository
                // singletons get garbage-collected when the activity
                // finishes, ExoPlayer is rebuilt fresh.
            }
            "clearCache" -> {
                cache.reconcile(keep = emptySet(), capBytes = 0L)
                lastLiveRevision = -1               // force a re-download next tick
                LogBuffer.i(TAG, "Cache cleared")
            }
            "unregister" -> {
                store.clearRegistration()
                store.setLocRegion(null); store.setLocCity(null); store.setLocStoreId(null)
                store.setLocConcept(null); store.setLocFloor(null); store.setLocTable(null)
                store.setLocScreenCode(null)
                store.setLiveServerUrl(null)
                LogBuffer.w(TAG, "Unregistered — onboarding will reappear")
            }
            "update" -> {
                // CMS asked us to self-update right now. Updater
                // handles "already up to date" silently — no overlay
                // flash if the latest release is what we're running.
                LogBuffer.i(TAG, "Update command received — checking for newer release")
                updater?.checkAndUpdate(surfaceFailures = true)
                    ?: LogBuffer.w(TAG, "Updater not wired — ignoring command")
            }
            "refresh" -> {
                // CMS pressed "Refresh now" — the latest /api/state we
                // just received already contained any newly-pushed
                // playlist, so the bulk of the work is already done.
                // Still call refreshNow() to be defensive: it resets
                // the last-known revision so any change we somehow
                // missed gets re-applied on the very next tick.
                LogBuffer.i(TAG, "Refresh command — kicking next poll")
                refreshNow()
            }
            "sendLogs" -> {
                // CMS "Request logs" — upload the full current log buffer
                // so the operator can read this screen's latest logs.
                LogBuffer.i(TAG, "Send-logs command — uploading current log buffer")
                shipFullLogSnapshot()
            }
            else -> LogBuffer.w(TAG, "Unknown command: $command")
        }
    }

    /** Build the structured location JSON snippet sent in heartbeat + register. */
    private suspend fun locationJson(): String {
        val region     = store.locRegion.first()
        val city       = store.locCity.first()
        val storeId    = store.locStoreId.first()
        val concept    = store.locConcept.first()
        val floor      = store.locFloor.first()
        val table      = store.locTable.first()
        val screenCode = store.locScreenCode.first()
        return buildString {
            append("{")
            val parts = mutableListOf<String>()
            region?.let     { parts += "\"region\":${q(it)}" }
            city?.let       { parts += "\"city\":${q(it)}" }
            storeId?.let    { parts += "\"storeId\":${q(it)}" }
            concept?.let    { parts += "\"concept\":${q(it)}" }
            floor?.let      { parts += "\"floor\":${q(it)}" }
            table?.let      { parts += "\"table\":${q(it)}" }
            screenCode?.let { parts += "\"screenCode\":${q(it)}" }
            append(parts.joinToString(","))
            append("}")
        }
    }

    private suspend fun sendHeartbeat(base: String, currentRevision: Int) {
        val deviceId = store.ensureDeviceId()
        val info = DeviceInfo.snapshot(appContext)
        val locationName = listOfNotNull(
            store.locStoreId.first(),
            store.locFloor.first(),
            store.locTable.first(),
            store.locScreenCode.first(),
        ).joinToString(" · ")
        val cachedIds = cache.cachedIds().joinToString(",") { "\"$it\"" }
        val tier = if (info.ramMb >= 3000) "1080p" else "720p"
        // Enumerate supported HDMI modes so the CMS resolution picker
        // has something to render. Returns [] on devices where the
        // Display API doesn't cooperate, which the CMS handles by
        // hiding the picker entirely (auto = no override).
        val modes = com.smartech.screens.util.DisplayModes.supported(appContext)
        val supportedModesJson = modes.joinToString(",") { m ->
            """{"id":${m.id},"w":${m.width},"h":${m.height},"hz":${m.refreshHz}}"""
        }
        val activeModeId = com.smartech.screens.util.DisplayModes.active(appContext)
        val body = """
            {
              "deviceId": "$deviceId",
              "name": ${q(locationName.ifBlank { "Demo tablet" })},
              "location": ${locationJson()},
              "currentRevision": $currentRevision,
              "status": "ONLINE",
              "deviceModel": ${q(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)},
              "ramMb": ${info.ramMb},
              "screenWidth": ${info.widthPx},
              "screenHeight": ${info.heightPx},
              "orientation": ${q(info.orientation)},
              "tier": ${q(tier)},
              "decoderTier": ${q(info.decoderTier)},
              "safeBitrateMbps": ${info.safeBitrateMbps},
              "appVersion": ${q(com.smartech.screens.BuildConfig.VERSION_NAME)},
              "appFlavor": ${q(com.smartech.screens.BuildConfig.FLAVOR)},
              "cacheBytes": ${cache.totalBytes()},
              "freeStorageBytes": ${appContext.filesDir.usableSpace},
              "cachedVideoIds": [${cachedIds}],
              "supportedModes": [${supportedModesJson}],
              "activeDisplayMode": ${activeModeId}
            }
        """.trimIndent()
        runCatching {
            val req = Request.Builder()
                .url("$base/api/screens/heartbeat")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().close()
        }.onFailure { LogBuffer.w(TAG, "Heartbeat failed: ${it.message}") }
    }

    /** First-time-seen handshake. Cheap to call repeatedly. */
    private suspend fun registerLive(base: String) {
        val deviceId = store.ensureDeviceId()
        val name = listOfNotNull(
            store.locStoreId.first(),
            store.locScreenCode.first(),
        ).joinToString(" · ").ifBlank { "Demo tablet" }
        val body = """
            {
              "deviceId": "$deviceId",
              "name": ${q(name)},
              "location": ${locationJson()},
              "appVersion": "${com.smartech.screens.BuildConfig.VERSION_NAME}",
              "appFlavor": "${com.smartech.screens.BuildConfig.FLAVOR}"
            }
        """.trimIndent()
        runCatching {
            val req = Request.Builder()
                .url("$base/api/screens/register")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().close()
            LogBuffer.i(TAG, "Registered against live server")
        }.onFailure { LogBuffer.w(TAG, "Register failed: ${it.message}") }
    }

    /**
     * Continuous polling loop. Started once from [com.smartech.screens.ScreensApp].
     * Sleep cadence reads from [pollMode] on every tick — values are
     * 10 s (FAST) / 60 s (NORMAL, default) / 600 s (SLOW). Library
     * refresh cadence scales with the poll interval so wall-clock
     * cadence stays roughly constant.
     *
     * The heartbeat is NOT fired from this loop — see
     * [startHeartbeatLoop] for that, decoupled so a long download
     * doesn't make the CMS think the screen has gone offline.
     */
    fun startLiveSync() {
        liveScope.launch {
            // v0.1.41: pre-seed the live server URL BEFORE the polling
            // loop reads it. Without this, a fresh first boot raced the
            // pre-seed coroutine in ScreensApp.onCreate — the polling
            // loop ran first, saw null, set ConnectionStatus to
            // DISCONNECTED, and the UI sat on "Not configured" until the
            // next tick (5 min on the legacy Slow default). Pre-seeding
            // here makes the state flow flip straight to CONNECTING and
            // then ONLINE on the first network round-trip.
            if (store.liveServerUrl.first().isNullOrBlank()) {
                val default = com.smartech.screens.BuildConfig.API_BASE.removeSuffix("/api")
                runCatching { store.setLiveServerUrl(default) }
                    .onSuccess { LogBuffer.i(TAG, "Pre-seeded liveServerUrl=$default") }
                    .onFailure { LogBuffer.w(TAG, "Pre-seed failed: ${it.message}") }
            }
            _connection.value = ConnectionStatus.CONNECTING

            // Register once (best-effort, logs on failure).
            store.liveServerUrl.first()?.let { registerLive(it.trimEnd('/')) }
            // v0.1.38: pull custom stores once on startup so any
            // additions from the CMS land in the on-tablet picker
            // without needing an APK update. Fire-and-forget.
            store.liveServerUrl.first()?.let { refreshStoresFromServer(it.trimEnd('/')) }
            // v0.1.66: pull the content library once on every startup —
            // covers a normal relaunch AND the relaunch after an APK
            // update, so the brand/video list is current immediately
            // rather than waiting for the periodic tick (which, before
            // the fix below, never fired in slow mode). Best-effort.
            store.liveServerUrl.first()?.let {
                runCatching { remoteLibrary.refresh(it.trimEnd('/')) }
                    .onFailure { e -> LogBuffer.w(TAG, "Startup library refresh failed: ${e.message}") }
            }
            while (true) {
                val url = store.liveServerUrl.first()
                if (url.isNullOrBlank()) {
                    _connection.value = ConnectionStatus.DISCONNECTED
                } else if (_connection.value == ConnectionStatus.DISCONNECTED) {
                    // First poll after a URL was set — show connecting until the
                    // tick below confirms reachability.
                    _connection.value = ConnectionStatus.CONNECTING
                }
                runCatching { refreshPlaylist() }
                    .onFailure { LogBuffer.w(TAG, "Live tick failed: ${it.message}") }

                // Library refresh once every ~5 minutes of wall-clock,
                // independent of the polling cadence. With a 10 s tick
                // that's every 30 ticks; with a 60 s tick every 5;
                // with a 600 s tick every 1.
                // v0.1.66: was `% libraryEvery == 1`, which NEVER fired
                // when libraryEvery == 1 (slow mode) — so slow-poll
                // tablets never refreshed the library after startup.
                // `== 0` fires correctly for every libraryEvery, and the
                // startup pull above already covers the first interval.
                libraryRefreshTickCounter++
                val effectiveInterval = _pollMode.value.intervalMs
                val libraryEvery = (5L * 60_000L / effectiveInterval).coerceAtLeast(1).toInt()
                if (libraryRefreshTickCounter % libraryEvery == 0) {
                    runCatching { remoteLibrary.refresh(store.liveServerUrl.first()) }
                }
                delay(effectiveInterval)
            }
        }
    }

    /**
     * Heartbeat loop — decoupled from [startLiveSync] so a long
     * download in [refreshLivePlaylist] doesn't block the heartbeat
     * and flip the screen to "offline" in the CMS. Fires every
     * [HEARTBEAT_INTERVAL_MS] regardless of poll mode.
     */
    fun startHeartbeatLoop() {
        liveScope.launch {
            // Same warmup as the playlist loop — give first-launch
            // registration a moment to land.
            delay(2_000L)
            while (true) {
                val base = store.liveServerUrl.first()?.trimEnd('/')
                if (!base.isNullOrBlank()) {
                    runCatching { sendHeartbeat(base, lastLiveRevision) }
                        .onFailure { LogBuffer.w(TAG, "Heartbeat tick failed: ${it.message}") }
                    // v0.1.25: piggyback the log-shipper on the
                    // heartbeat cadence. Sends warnings + errors
                    // accumulated since the last shipped sequence
                    // number; no-op if there's nothing new. Failure
                    // path is silent — see the body for why.
                    val deviceId = runCatching { store.ensureDeviceId() }.getOrNull()
                    if (deviceId != null) {
                        runCatching { shipRecentWarningsIfPending(deviceId) }
                    }
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun LiveItem.toVideoItem(base: String): VideoItem {
        val absoluteUrl = if (url.startsWith("http://") || url.startsWith("https://")) url
        else if (url.startsWith("/")) base + url
        else "$base/$url"
        return VideoItem(
            id = id, title = title, brand = brand, product = product,
            url = absoluteUrl, durationSec = durationSec,
            defaultUnmute = defaultUnmute,
        )
    }

    /** Cheap JSON-string quoter — avoids pulling kotlinx.serialization for trivial bodies. */
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ── Demo mode (Cloudflare sample clips) ──────────────────────────────

    /**
     * No-backend smoke test: fetch three small public sample videos, cache
     * them, publish. Lets you `adb install` the APK and immediately confirm
     * end-to-end playback (download → cache → ExoPlayer) works on the tablet.
     */
    private suspend fun refreshDemoPlaylist() {
        LogBuffer.i(TAG, "Demo playlist refresh — ${DemoMode.items.size} items")
        val playlist = PlaylistResponse(
            screenId = "demo",
            revision = "demo-1",
            tier = "MID_720P",
            items = DemoMode.items,
        )
        lastPlaylist = playlist
        for (video in playlist.items) {
            runCatching {
                if (!cache.has(video.id)) {
                    LogBuffer.i(TAG, "Downloading ${video.id}")
                    cache.ensure(video)
                    LogBuffer.i(TAG, "Cached ${video.id}")
                }
            }.onFailure { LogBuffer.w(TAG, "Demo download failed for ${video.id}", it) }
        }
        val cap = store.cacheCapBytes.first()
        cache.reconcile(keep = playlist.items.map { it.id }.toSet(), capBytes = cap)
        publish(playlist)
    }

    companion object {
        private const val TAG = "PlayerRepository"

        /** Heartbeat fires on its own coroutine independent of the
         *  playlist-refresh loop, so the CMS can see the screen as
         *  online even mid-download. 10 s is well under the server's
         *  15 s "online" window. */
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
    }
}

/**
 * Demo mode is active whenever [com.smartech.screens.BuildConfig.API_BASE] is
 * still pointing at the placeholder host. Override it at build time:
 *   ./gradlew assembleDebug -PapiBase=https://api.smartech.group/api
 */
object DemoMode {
    val isActive: Boolean get() = com.smartech.screens.BuildConfig.API_BASE.contains("example.com")

    val items: List<VideoItem> = listOf(
        VideoItem(
            id = "demo-arc",
            title = "Arc Ultra — hero reveal",
            brand = "SONOS",
            product = "Arc",
            durationSec = 15,
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
        ),
        VideoItem(
            id = "demo-era",
            title = "Era 300 — spatial demo",
            brand = "SONOS",
            product = "Era 300",
            durationSec = 15,
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
        ),
        VideoItem(
            id = "demo-move",
            title = "Move 2 — outdoor lifestyle",
            brand = "SONOS",
            product = "Move",
            durationSec = 15,
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4",
        ),
    )
}
