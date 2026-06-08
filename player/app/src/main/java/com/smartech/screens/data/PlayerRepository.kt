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
        val playingCount = (state.value as? State.Playing)?.items?.size ?: 0
        LogBuffer.i(TAG, "Rehydrated playlist from cache — $playingCount of ${playlist.items.size} items playable")
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
        /** When true, the tablet slows its poll cadence and skips the
         *  remote splash download. See [LOW_DATA_POLL_MS] /
         *  [DEFAULT_POLL_MS]. */
        val lowDataMode: Boolean = false,
        val commands: List<LiveCommand> = emptyList(),
        val splashUrl: String? = null,
        val splashName: String? = null,
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

    /** Low-data mode. When true the tablet polls /api/state every
     *  [LOW_DATA_POLL_MS] (60s) instead of [DEFAULT_POLL_MS] (3s) and
     *  skips the remote per-location splash download (falling back to
     *  the APK-bundled splash). Cached videos already on disk are
     *  unaffected — once a clip is downloaded it never re-fetches. */
    private val _lowDataMode = MutableStateFlow(false)
    val lowDataModeFlow: StateFlow<Boolean> = _lowDataMode
    val lowDataMode: Boolean get() = _lowDataMode.value

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
        val resp = runCatching {
            val req = Request.Builder().url("$base/api/state?screenId=$deviceId").build()
            httpClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                r.body?.string() ?: ""
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

        // Low data mode flag. The polling loop reads this on every tick
        // and stretches the sleep interval when it's on; no re-publish.
        if (state.lowDataMode != _lowDataMode.value) {
            _lowDataMode.value = state.lowDataMode
            LogBuffer.i(TAG, "Low data mode → ${if (state.lowDataMode) "on" else "off"}")
        }

        // Per-location splash. Download (or clear) when the URL changes.
        // Skipped entirely in low-data mode — the per-location splash is
        // ~70MB for the Smartech 4K asset; the APK-bundled splash is
        // shown instead.
        if (!state.lowDataMode) {
            ensureRemoteSplash(base, state.splashUrl)
        }

        // Execute any pending commands the CMS queued. The server already
        // drained them in the GET response so we won't see them twice.
        for (cmd in state.commands) executeCommand(cmd.command)

        // No change to playlist? Still fire a heartbeat and return.
        if (state.revision == lastLiveRevision && _state.value is State.Playing) {
            sendHeartbeat(base, state.revision)
            return
        }

        val items = state.items.map { it.toVideoItem(base) }

        // Update intent flow first so the staff playlist view shows the new
        // item the moment the server confirms it — even before the bytes
        // start arriving on the tablet.
        _intendedPlaylist.value = items

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
                // state-flow recomposition.
                lastPlaylist?.let { publish(it) }
            }
            sendHeartbeat(base, state.revision)
            return
        }

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
        sendHeartbeat(base, state.revision)
    }

    // ── Tablet → server: staff overlay actions ──────────────────────────

    /**
     * Replace the live server's playlist for THIS device with [items].
     * Used by the on-tablet staff overlay's playlist editor — delete a video,
     * reorder, etc. Body sent verbatim is the new full playlist.
     */
    suspend fun pushPlaylistToServer(items: List<VideoItem>, mode: String = "replace") {
        LogBuffer.i(TAG, "pushPlaylistToServer mode=$mode items=${items.size}")
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

    /** Flip the low-data-mode flag on the server. Tablet picks the new
     *  value back up on the next state poll; the optimistic local
     *  update means the staff toggle reflects the new state instantly
     *  (and the next polling tick uses the new cadence). */
    suspend fun setLowDataModeOnServer(value: Boolean) {
        LogBuffer.i(TAG, "setLowDataModeOnServer → $value")
        _lowDataMode.value = value

        val base = store.liveServerUrl.first()?.trimEnd('/')
        if (base.isNullOrBlank()) {
            LogBuffer.w(TAG, "setLowDataModeOnServer skipped — no liveServerUrl set")
            return
        }
        val deviceId = store.ensureDeviceId()
        val body = """{"lowDataMode":$value}"""
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$base/api/screens/${urlEncode(deviceId)}/low-data-mode")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) LogBuffer.w(TAG, "setLowDataModeOnServer HTTP ${r.code}")
                    else LogBuffer.i(TAG, "setLowDataModeOnServer OK")
                }
                lastLiveRevision = -1
            }.onFailure {
                LogBuffer.w(TAG, "setLowDataModeOnServer failed: ${it.javaClass.simpleName}: ${it.message ?: "(no message)"}", it)
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
    private fun scheduleSelfRestart() {
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
              "appVersion": ${q(com.smartech.screens.BuildConfig.VERSION_NAME)},
              "cacheBytes": ${cache.totalBytes()},
              "freeStorageBytes": ${appContext.filesDir.usableSpace},
              "cachedVideoIds": [${cachedIds}]
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
              "appVersion": "${com.smartech.screens.BuildConfig.VERSION_NAME}"
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
     * Sleep cadence flips dynamically based on [lowDataMode] — see
     * [DEFAULT_POLL_MS] / [LOW_DATA_POLL_MS]. Library refresh cadence
     * scales with the poll interval so a low-data tablet still re-checks
     * the library roughly every 5 minutes.
     */
    fun startLiveSync() {
        liveScope.launch {
            // Register once (best-effort, logs on failure).
            store.liveServerUrl.first()?.let { registerLive(it.trimEnd('/')) }
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
                // independent of the polling cadence. With a 3s tick
                // that's every 100 ticks; with a 60s tick that's every 5.
                libraryRefreshTickCounter++
                val effectiveInterval = if (_lowDataMode.value) LOW_DATA_POLL_MS else DEFAULT_POLL_MS
                val libraryEvery = (5L * 60_000L / effectiveInterval).coerceAtLeast(1).toInt()
                if (libraryRefreshTickCounter % libraryEvery == 1) {
                    runCatching { remoteLibrary.refresh(store.liveServerUrl.first()) }
                }
                delay(effectiveInterval)
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

        /** Default live-state poll interval. The whole live workflow
         *  (revision pickup, command delivery, audio + splash toggles)
         *  hinges on this; ~3 s gives the CMS near-real-time control. */
        private const val DEFAULT_POLL_MS = 3_000L

        /** Slow poll cadence when low-data mode is on. One minute trades
         *  near-real-time CMS responsiveness for ~95% less idle traffic. */
        private const val LOW_DATA_POLL_MS = 60_000L
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
