package com.smartech.screens.data

import com.smartech.screens.util.LogBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Pulls the library (brands + videos) from the live demo server's
 * `/api/library`. The on-tablet staff overlay subscribes to [state] so its
 * brand picker and video picker reflect what the CMS shows.
 *
 * Refresh strategy: polled on demand from [refresh]; called from the live
 * sync loop in [PlayerRepository] every few ticks. Last good response is
 * cached in memory and served when the network is flaky.
 */
class RemoteLibrary(
    private val httpClient: OkHttpClient,
) {
    @Serializable
    data class Library(
        val brands: List<RemoteBrand> = emptyList(),
        val videos: List<RemoteVideo> = emptyList(),
    )

    @Serializable
    data class RemoteBrand(
        val id: String,
        val name: String,
        val videos: Int = 0,
        val products: List<String> = emptyList(),
        // v0.1.63: tm:rw brand logo, merged server-side into /api/library.
        // Carried through now so it's available when the tablet brand grid
        // gains logo rendering (Phase 2 content-library rework). Null when
        // tm:rw has no logo for the brand or no API key is configured.
        val logoUrl: String? = null,
    )

    @Serializable
    data class RemoteVideo(
        val id: String,
        val title: String,
        val brand: String? = null,
        val product: String? = null,
        // mediaUrl is null for tm:rw "pending" videos (assigned in the
        // asset manager but not yet in the Drive folder) — they can't be
        // pushed until the file lands, so the picker shows them disabled.
        val mediaUrl: String? = null,
        val sizeMb: Double? = null,
        val filename: String? = null,
        val durationSec: Double? = null,
        val width: Int? = null,
        val height: Int? = null,
        // v0.1.67: tm:rw asset-manager tags (server merges these into
        // /api/library). productLine groups videos in the picker;
        // tmrwActive = registered + live; tmrwAssigned = the asset
        // manager knows this file (else it's an "orphan" in the Drive
        // folder); pendingSync = assigned but no streamable file yet.
        // Defaults keep fallback/legacy payloads behaving normally.
        val productLine: String? = null,
        val tmrwActive: Boolean = false,
        val tmrwAssigned: Boolean = true,
        val pendingSync: Boolean = false,
        // v0.1.69: "brand" → Brand global videos; "family"/"product" →
        // grouped under their product; null on orphan/fallback.
        val tmrwScope: String? = null,
        // v0.1.71: extra asset-manager columns surfaced in the picker's
        // list view. sku = the product SKU; tmrwOrientation/tmrwResolution
        // are tm:rw's own values, used as a fallback for pending videos
        // that have no scanned width/height yet.
        val sku: String? = null,
        val tmrwOrientation: String? = null,
        val tmrwResolution: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val _state = MutableStateFlow(Library())
    val state: StateFlow<Library> = _state

    suspend fun refresh(serverUrl: String?) {
        if (serverUrl.isNullOrBlank()) return
        val base = serverUrl.trimEnd('/')
        runCatching {
            val req = Request.Builder().url("$base/api/library").build()
            httpClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                val raw = r.body?.string() ?: ""
                val lib = json.decodeFromString<Library>(raw)
                // Mirror absolute URLs so tablets can fetch directly.
                // v0.1.67: mediaUrl is now nullable (tm:rw "pending"
                // videos have none) — leave those null rather than
                // fabricating a URL.
                val items = lib.videos.map { v ->
                    val m = v.mediaUrl
                    val url = when {
                        m.isNullOrBlank() -> null
                        m.startsWith("http") -> m
                        m.startsWith("/") -> base + m
                        else -> "$base/$m"
                    }
                    v.copy(mediaUrl = url)
                }
                _state.value = lib.copy(videos = items)
                LogBuffer.i("RemoteLibrary", "Fetched ${items.size} videos across ${lib.brands.size} brands")
            }
        }.onFailure { LogBuffer.w("RemoteLibrary", "Refresh failed: ${it.message}") }
    }
}
