package com.smartech.screens.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Device-side REST API. Mirrors the endpoints in 02-engineering-brief.md.
 *
 * Auth: every call except `register` carries `Authorization: Bearer <deviceToken>`.
 * The auth header is attached centrally by the OkHttp interceptor — don't add it
 * here per-method, we only need [Header] for conditional requests (ETag).
 */
interface DeviceApi {

    @POST("device/register")
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    @POST("device/ping")
    suspend fun ping(@Body body: PingRequest)

    /**
     * Conditional fetch. Pass the last ETag as `If-None-Match`; a 304 means the
     * cached playlist is still current. Saves bandwidth on flaky store WiFi.
     */
    @GET("device/playlist")
    suspend fun playlist(
        @Header("If-None-Match") etag: String? = null
    ): Response<PlaylistResponse>

    @GET("device/settings")
    suspend fun settings(
        @Header("If-None-Match") etag: String? = null
    ): Response<SettingsResponse>

    @POST("device/fcm-token")
    suspend fun updateFcmToken(@Body body: FcmTokenRequest)

    @POST("device/event")
    suspend fun logEvent(@Body body: DeviceEvent)

    @POST("device/finished")
    suspend fun finished(@Body body: DeviceEvent)
}
