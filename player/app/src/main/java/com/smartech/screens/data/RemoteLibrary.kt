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
        val mediaUrl: String,
        val sizeMb: Double? = null,
        val filename: String? = null,
        val durationSec: Double? = null,
        val width: Int? = null,
        val height: Int? = null,
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
                val items = lib.videos.map { v ->
                    val url = if (v.mediaUrl.startsWith("http")) v.mediaUrl
                              else if (v.mediaUrl.startsWith("/")) base + v.mediaUrl
                              else "$base/${v.mediaUrl}"
                    v.copy(mediaUrl = url)
                }
                _state.value = lib.copy(videos = items)
                LogBuffer.i("RemoteLibrary", "Fetched ${items.size} videos across ${lib.brands.size} brands")
            }
        }.onFailure { LogBuffer.w("RemoteLibrary", "Refresh failed: ${it.message}") }
    }
}
