package com.smartech.screens.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body for POST /device/register. */
@Serializable
data class RegisterRequest(
    val joinCode: String,
    val deviceId: String,
    val orientation: String,   // LANDSCAPE | PORTRAIT
    val ramMb: Int,
    val width: Int,
    val height: Int,
    val location: LocationFields? = null,
)

/** Structured location captured at first-run onboarding. Mirrors the CMS taxonomy. */
@Serializable
data class LocationFields(
    val region: String? = null,
    val city: String? = null,
    val storeId: String? = null,
    val concept: String? = null,
    val floor: String? = null,
    val table: String? = null,
    val screenCode: String? = null,
)

@Serializable
data class RegisterResponse(
    val deviceToken: String,
    val screenId: String,
)

/** A single video the tablet should cache and play. */
@Serializable
data class VideoItem(
    val id: String,
    val title: String,
    @SerialName("durationSec") val durationSec: Int? = null,
    /** Pre-signed or public R2 URL for the rendition matching this screen's tier. */
    val url: String,
    /** Hash / etag used for cache invalidation. Optional. */
    val hash: String? = null,
    val brand: String? = null,
    val product: String? = null,
)

/** Response of GET /device/playlist. Server resolves schedules before returning. */
@Serializable
data class PlaylistResponse(
    val screenId: String,
    val revision: String,             // bumps every time playlist changes
    val items: List<VideoItem>,
    /** Tier the backend is currently serving. */
    val tier: String = "MID_720P",
)

/** Response of GET /device/settings. */
@Serializable
data class SettingsResponse(
    val orientation: String? = null,  // null = auto
    val pollIntervalSec: Int = 60,
    val cacheCapBytes: Long = 8L * 1024 * 1024 * 1024,
    val staffPinHash: String? = null, // server-side bcrypt/sha; client sends a check
)

/** Body for POST /device/ping. */
@Serializable
data class PingRequest(
    val status: String,       // ONLINE | UPDATING | ERROR
    val appVersion: String,
    val cachedVideos: List<String>,
    val cacheBytes: Long,
    val freeStorageBytes: Long,
)

/** Body for POST /device/fcm-token. */
@Serializable
data class FcmTokenRequest(val fcmToken: String)

/** Body for POST /device/event. */
@Serializable
data class DeviceEvent(
    val type: String,         // PLAYBACK_STARTED | PLAYBACK_STALLED | CACHE_EVICTED | ERROR
    val videoId: String? = null,
    val message: String? = null,
    val at: Long = System.currentTimeMillis(),
)
