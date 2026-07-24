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
    /** When true, this video plays with audio even if the screen is muted.
     *  Set per-video from the CMS Content Library; flows through here so
     *  the player can apply the right volume on each item transition. */
    @SerialName("defaultUnmute") val defaultUnmute: Boolean = false,
    /** Shopper-facing product-info-card fields. Populated per playlist item
     *  by the server; rendered as an on-screen card when the screen's
     *  top-level `productCard` flag is on. All optional — the card degrades
     *  gracefully when any are absent. */
    val description: String? = null,
    val descriptionLong: String? = null,
    val prices: Prices? = null,
    val packshotUrl: String? = null,
    val brandLogoUrl: String? = null,
    /** v0.2.8: when one video represents several products (a tm:rw family- or
     *  brand-scope video), the products it stands for — so the card can cycle
     *  through them. Null / <2 entries → the card shows this item's own single
     *  product (the fields above) exactly as before. */
    val products: List<ProductCard>? = null,
)

/** Region-keyed prices for the product-info card. Every field optional;
 *  the card resolves one by the screen's city (see ProductInfoCardOverlay). */
@Serializable
data class Prices(
    val gbp: Double? = null,
    val usd: Double? = null,
    val eur: Double? = null,
    val berlinEur: Double? = null,
    val romeEur: Double? = null,
)

/** v0.2.8: one product on the shopper card's cycle. Mirrors the single-product
 *  fields on [VideoItem]; the card rotates through a list of these when a video
 *  represents more than one product. All optional — a missing field just drops
 *  that row. */
@Serializable
data class ProductCard(
    val product: String? = null,
    val prices: Prices? = null,
    val description: String? = null,
    val descriptionLong: String? = null,
    val packshotUrl: String? = null,
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
