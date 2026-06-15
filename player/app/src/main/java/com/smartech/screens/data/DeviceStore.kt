package com.smartech.screens.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "screens_device")

/**
 * Persistent device-local state. Small — token, deviceId, last-applied settings,
 * playlist ETag. Anything bigger (videos, playlist JSON) lives on disk as files.
 */
class DeviceStore(private val context: Context) {

    private object Keys {
        val DeviceId = stringPreferencesKey("device_id")
        val DeviceToken = stringPreferencesKey("device_token")
        val ScreenId = stringPreferencesKey("screen_id")
        val PlaylistEtag = stringPreferencesKey("playlist_etag")
        val SettingsEtag = stringPreferencesKey("settings_etag")
        val LastFcmToken = stringPreferencesKey("last_fcm_token")
        val CacheCapBytes = longPreferencesKey("cache_cap_bytes")
        val PollIntervalSec = longPreferencesKey("poll_interval_sec")
        val OrientationOverride = stringPreferencesKey("orientation_override")

        // Structured location — set via the cascading dropdowns in Device admin.
        val LocRegion     = stringPreferencesKey("loc_region")       // "USA" | "UK" | "EU"
        val LocCity       = stringPreferencesKey("loc_city")         // "NYC" | "LDN" | …
        val LocStoreId    = stringPreferencesKey("loc_store_id")     // taxonomy store id
        val LocConcept    = stringPreferencesKey("loc_concept")      // "Smartech" | "Playhouse" | …
        val LocFloor      = stringPreferencesKey("loc_floor")        // "GF" | "MEZ" | "TF"
        val LocTable      = stringPreferencesKey("loc_table")        // "GF.A" | …
        val LocScreenCode = stringPreferencesKey("loc_screen_code")  // free text, e.g. "GF.A.1"

        // Live demo server (laptop running serve.py on the same LAN). When set,
        // the player polls /api/state from this URL instead of the demo Cloudflare
        // playlist. Settable via Device admin → Configuration.
        val LiveServerUrl = stringPreferencesKey("live_server_url")

        // Last-known-good playlist (JSON-encoded PlaylistResponse). Repopulated
        // on every successful publish() so we can rehydrate playback before
        // the first network round-trip completes — and so an offline launch
        // doesn't drop the tablet to the splash loop.
        val LastPlaylistJson = stringPreferencesKey("last_playlist_json")
    }

    /** Ensures a stable per-install device id. Called exactly once at first launch. */
    suspend fun ensureDeviceId(): String {
        val existing = context.dataStore.data.map { it[Keys.DeviceId] }.first()
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.DeviceId] = id }
        return id
    }

    val deviceId: Flow<String?> = context.dataStore.data.map { it[Keys.DeviceId] }
    val screenId: Flow<String?> = context.dataStore.data.map { it[Keys.ScreenId] }
    val deviceToken: Flow<String?> = context.dataStore.data.map { it[Keys.DeviceToken] }
    val playlistEtag: Flow<String?> = context.dataStore.data.map { it[Keys.PlaylistEtag] }
    val settingsEtag: Flow<String?> = context.dataStore.data.map { it[Keys.SettingsEtag] }
    val orientationOverride: Flow<String?> = context.dataStore.data.map { it[Keys.OrientationOverride] }

    // Structured location flows
    val locRegion: Flow<String?>     = context.dataStore.data.map { it[Keys.LocRegion] }
    val locCity: Flow<String?>       = context.dataStore.data.map { it[Keys.LocCity] }
    val locStoreId: Flow<String?>    = context.dataStore.data.map { it[Keys.LocStoreId] }
    val locConcept: Flow<String?>    = context.dataStore.data.map { it[Keys.LocConcept] }
    val locFloor: Flow<String?>      = context.dataStore.data.map { it[Keys.LocFloor] }
    val locTable: Flow<String?>      = context.dataStore.data.map { it[Keys.LocTable] }
    val locScreenCode: Flow<String?> = context.dataStore.data.map { it[Keys.LocScreenCode] }
    val liveServerUrl: Flow<String?> = context.dataStore.data.map { it[Keys.LiveServerUrl] }

    /**
     * `true` once the screen has been through first-run setup. Required fields:
     * region, city, store, concept, and screen code. Floor and table are optional.
     * Used by [com.smartech.screens.MainActivity] to gate the onboarding screen.
     */
    val isOnboarded: Flow<Boolean> = combine(
        locRegion, locCity, locStoreId, locConcept, locScreenCode,
    ) { region, city, store, concept, code ->
        !region.isNullOrBlank() && !city.isNullOrBlank() &&
            !store.isNullOrBlank() && !concept.isNullOrBlank() &&
            !code.isNullOrBlank()
    }

    val cacheCapBytes: Flow<Long> =
        context.dataStore.data.map { it[Keys.CacheCapBytes] ?: (8L * 1024 * 1024 * 1024) }

    val pollIntervalSec: Flow<Long> =
        context.dataStore.data.map { it[Keys.PollIntervalSec] ?: 60 }

    suspend fun setOrientationOverride(value: String?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(Keys.OrientationOverride)
            else prefs[Keys.OrientationOverride] = value
        }
    }

    /**
     * Setters for each location level. When a parent level changes, descendant
     * levels that no longer make sense are cleared automatically. Callers don't
     * need to remember the cascade rules.
     */
    suspend fun setLocRegion(value: String?) {
        context.dataStore.edit { prefs ->
            putOrRemove(prefs, Keys.LocRegion, value)
            // Clear cascade — any city/store/etc. assumed under the old region.
            prefs.remove(Keys.LocCity); prefs.remove(Keys.LocStoreId)
        }
    }

    suspend fun setLocCity(value: String?) {
        context.dataStore.edit { prefs ->
            putOrRemove(prefs, Keys.LocCity, value)
            prefs.remove(Keys.LocStoreId)
        }
    }

    suspend fun setLocStoreId(value: String?)    { editStr(Keys.LocStoreId, value) }
    suspend fun setLocConcept(value: String?)    { editStr(Keys.LocConcept, value) }

    /**
     * Atomic store-pick. Picking a store implies its city and region; this
     * helper writes all three keys in a single DataStore transaction so the
     * cascade-clear logic in [setLocRegion] / [setLocCity] doesn't wipe the
     * child fields between writes. Optionally clears `concept` when the
     * picked store's city has no in-store concepts (BER, ROM).
     */
    suspend fun setLocStoreCascade(
        storeId: String?,
        cityCode: String?,
        regionName: String?,
        clearConcept: Boolean,
        clearFloorTable: Boolean = false,
    ) {
        context.dataStore.edit { prefs ->
            putOrRemove(prefs, Keys.LocStoreId, storeId)
            putOrRemove(prefs, Keys.LocCity, cityCode)
            putOrRemove(prefs, Keys.LocRegion, regionName)
            if (clearConcept) prefs.remove(Keys.LocConcept)
            if (clearFloorTable) {
                prefs.remove(Keys.LocFloor)
                prefs.remove(Keys.LocTable)
            }
        }
    }

    suspend fun setLocFloor(value: String?) {
        context.dataStore.edit { prefs ->
            putOrRemove(prefs, Keys.LocFloor, value)
            // Tables are namespaced by floor; clearing floor invalidates table.
            prefs.remove(Keys.LocTable)
        }
    }

    suspend fun setLocTable(value: String?)      { editStr(Keys.LocTable, value) }
    suspend fun setLocScreenCode(value: String?) { editStr(Keys.LocScreenCode, value) }

    suspend fun setLiveServerUrl(value: String?) {
        // Normalise — strip trailing slashes, prepend a scheme if missing,
        // and upgrade http:// to https:// for any host that isn't a private
        // LAN address. The reason: hosts behind Cloud Run / Cloudflare / any
        // managed TLS terminator force-redirect HTTP to HTTPS with a 301.
        // OkHttp follows the redirect but downgrades POST to GET — so the
        // tablet's heartbeat / register / playlist POSTs silently arrive as
        // GETs, fail with 404, and the screen never appears in the CMS.
        // We can't disable the platform redirect; safer to ensure we never
        // ship http:// to a public-looking host in the first place. LAN
        // addresses (private IPs, localhost) still allow http for dev rigs.
        val cleaned = value?.trim()?.takeIf { it.isNotBlank() }?.let {
            val withScheme = if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
            val trimmed = withScheme.trimEnd('/')
            if (trimmed.startsWith("http://") && !looksLikeLanHost(trimmed)) {
                "https://" + trimmed.removePrefix("http://")
            } else {
                trimmed
            }
        }
        editStr(Keys.LiveServerUrl, cleaned)
    }

    /**
     * Heuristic for "is this URL pointed at a LAN device where http is
     * fine?" — covers the common dev cases (private IPv4 ranges, loopback,
     * `.local` mDNS) and intentionally errs on the side of upgrading to
     * https. False positives mean a dev's LAN URL gets upgraded and fails;
     * false negatives mean a public URL stays on http and gets redirect-
     * downgraded. We optimise for the latter not happening.
     */
    private fun looksLikeLanHost(url: String): Boolean {
        val host = url.removePrefix("http://").substringBefore('/').substringBefore(':')
        if (host == "localhost" || host == "127.0.0.1" || host.endsWith(".local")) return true
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
        val (a, b, _, _) = parts.map { it.toInt() }.let { listOf(it[0], it[1], it[2], it[3]) }
        return when {
            a == 10 -> true                                    // 10.0.0.0/8
            a == 192 && b == 168 -> true                       // 192.168.0.0/16
            a == 172 && b in 16..31 -> true                    // 172.16.0.0/12
            a == 169 && b == 254 -> true                       // link-local
            else -> false
        }
    }

    private suspend fun editStr(
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        value: String?,
    ) {
        context.dataStore.edit { prefs -> putOrRemove(prefs, key, value) }
    }

    private fun putOrRemove(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        value: String?,
    ) {
        if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value
    }

    suspend fun setCacheCapBytes(bytes: Long) {
        context.dataStore.edit { it[Keys.CacheCapBytes] = bytes }
    }

    suspend fun setPollIntervalSec(sec: Long) {
        context.dataStore.edit { it[Keys.PollIntervalSec] = sec }
    }

    /** Wipe registration only — keeps cache + log. Used by "Re-register" admin action. */
    suspend fun clearRegistration() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.DeviceToken)
            prefs.remove(Keys.ScreenId)
            prefs.remove(Keys.PlaylistEtag)
            prefs.remove(Keys.SettingsEtag)
        }
    }

    suspend fun saveRegistration(token: String, screenId: String) {
        context.dataStore.edit {
            it[Keys.DeviceToken] = token
            it[Keys.ScreenId] = screenId
        }
    }

    suspend fun savePlaylistEtag(etag: String) {
        context.dataStore.edit { it[Keys.PlaylistEtag] = etag }
    }

    suspend fun saveSettings(settings: SettingsResponse, etag: String?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CacheCapBytes] = settings.cacheCapBytes
            prefs[Keys.PollIntervalSec] = settings.pollIntervalSec.toLong()
            if (settings.orientation != null) {
                prefs[Keys.OrientationOverride] = settings.orientation
            } else {
                prefs.remove(Keys.OrientationOverride)
            }
            if (etag != null) prefs[Keys.SettingsEtag] = etag
        }
    }

    suspend fun saveFcmToken(token: String) {
        context.dataStore.edit { it[Keys.LastFcmToken] = token }
    }

    suspend fun lastFcmToken(): String? =
        context.dataStore.data.map { it[Keys.LastFcmToken] }.first()

    /** JSON-encoded PlaylistResponse from the most recent successful
     *  publish(). Used to rehydrate playback on cold boot before any
     *  network round-trip completes — see [PlayerRepository.rehydrateFromCache]. */
    suspend fun saveLastPlaylistJson(json: String) {
        context.dataStore.edit { it[Keys.LastPlaylistJson] = json }
    }

    suspend fun lastPlaylistJson(): String? =
        context.dataStore.data.map { it[Keys.LastPlaylistJson] }.first()

    /** Drop the cached playlist. Called when the server has explicitly
     *  pushed an empty playlist (revision > 0 AND items == []) — i.e.
     *  "stop showing anything," distinct from "I happen to have no
     *  state for you yet." */
    suspend fun clearLastPlaylistJson() {
        context.dataStore.edit { it.remove(Keys.LastPlaylistJson) }
    }

    /** Bearer-token getter used by [ApiClient]'s interceptor. Blocking on purpose — runs once per request. */
    fun tokenBlocking(): String? = runCatching {
        kotlinx.coroutines.runBlocking { deviceToken.first() }
    }.getOrNull()

    /** v0.1.21: blocking getters for the crash reporter's
     *  uncaught-exception handler — that callback runs on the
     *  crashing thread and can't suspend. Same pattern as
     *  tokenBlocking, swallowing exceptions because the crash
     *  path must not throw. */
    fun deviceIdBlocking(): String? = runCatching {
        kotlinx.coroutines.runBlocking { deviceId.first() }
    }.getOrNull()

    fun locScreenCodeBlocking(): String? = runCatching {
        kotlinx.coroutines.runBlocking { locScreenCode.first() }
    }.getOrNull()
}
