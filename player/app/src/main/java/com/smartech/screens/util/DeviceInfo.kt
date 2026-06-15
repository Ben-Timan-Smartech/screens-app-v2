package com.smartech.screens.util

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager

/** Snapshot of the host device used for registration and tier selection. */
data class DeviceSnapshot(
    val ramMb: Int,
    val widthPx: Int,
    val heightPx: Int,
    val orientation: String, // LANDSCAPE | PORTRAIT
    /** v0.1.23: rough decoder-class tier derived from RAM. Used by
     *  [PlayerController] to skip videos whose bitrate would blow up
     *  the decoder (e.g. an uncompressed 200 MB clip on a TX3 Mini),
     *  and reported in the heartbeat so the CMS can warn at push time.
     *  Values: "low" | "medium" | "high" — see [decoderTierFor]. */
    val decoderTier: String,
    /** v0.1.23: safe per-item bitrate ceiling for this device, in
     *  megabits per second. PlayerController filters items whose
     *  `(sizeMb * 8 / durationSec)` exceeds this. Derived directly
     *  from [decoderTier] so the CMS doesn't have to know the
     *  numbers. */
    val safeBitrateMbps: Int,
)

object DeviceInfo {
    /**
     * v0.1.23: bucket the device into a rough decoder-class tier.
     *
     * RAM is the cheapest reliable proxy. The TX3 Mini ships with 1 GB
     * (and we crashed playing a 202 MB / ~50 Mbps clip on it); generic
     * 2 GB Android tablets sit between cheap signage boxes and real
     * hardware; anything 3 GB+ tends to have a competent decoder.
     *
     * Per-tier safe bitrate ceilings:
     *   low    → 10 Mbps  (covers compressed 1080p H.264; rejects
     *                      lightly-compressed or uncompressed clips)
     *   medium → 25 Mbps  (covers most 1080p high-bitrate sources)
     *   high   → 80 Mbps  (covers 4K + visually-lossless masters,
     *                      rejects truly uncompressed clips that
     *                      would saturate any consumer decoder)
     */
    fun decoderTierFor(ramMb: Int): String = when {
        ramMb < 1500 -> "low"
        ramMb < 3000 -> "medium"
        else -> "high"
    }

    fun safeBitrateForTier(tier: String): Int = when (tier) {
        "low" -> 10
        "medium" -> 25
        else -> 80   // "high" and anything we don't recognise
    }

    fun snapshot(context: Context): DeviceSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        // On Android TV / signage boxes the value of
        // resources.displayMetrics.widthPixels often reflects the box's
        // *internal rendering surface* — e.g. 1920×1080 even when the
        // attached panel is 4K and the box outputs 4K over HDMI. That
        // surface size is interesting to the renderer but useless for
        // tier selection; what matters for "what resolution is the
        // customer actually seeing" is the physical HDMI mode.
        //
        // Display.getMode() returns the active mode, which on Android
        // TV / Fire TV / signage boxes corresponds to the HDMI output
        // resolution. Falling back through real metrics → display
        // metrics keeps us honest on devices where mode lookup throws.
        val (widthPx, heightPx) = resolvePhysicalSize(context)

        val orientation = when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
            else -> "LANDSCAPE"
        }
        val ramMb = (mem.totalMem / (1024 * 1024)).toInt()
        val decoderTier = decoderTierFor(ramMb)
        return DeviceSnapshot(
            ramMb = ramMb,
            widthPx = widthPx,
            heightPx = heightPx,
            orientation = orientation,
            decoderTier = decoderTier,
            safeBitrateMbps = safeBitrateForTier(decoderTier),
        )
    }

    /**
     * Best-effort physical screen resolution.
     *
     * Order of preference, each step falling through to the next on error:
     *   1. Display.getMode().physicalWidth/Height — the active HDMI mode.
     *      Most accurate on TV-class devices (API 23+).
     *   2. Display.getRealMetrics() — total display area including system
     *      bars. Logical pixels but un-clipped by app window.
     *   3. resources.displayMetrics — the app's available area. Almost
     *      always smaller than the screen on phones (excludes status bar)
     *      but on full-bleed TV apps usually matches.
     */
    private fun resolvePhysicalSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        // 1. Display.getMode() — the active HDMI mode.
        try {
            @Suppress("DEPRECATION")
            val display = wm?.defaultDisplay
            val mode = display?.mode
            if (mode != null && mode.physicalWidth > 0 && mode.physicalHeight > 0) {
                return mode.physicalWidth to mode.physicalHeight
            }
        } catch (_: Throwable) {
            // fall through
        }

        // 2. getRealMetrics — un-clipped display size.
        try {
            @Suppress("DEPRECATION")
            val display = wm?.defaultDisplay
            if (display != null) {
                val real = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(real)
                if (real.widthPixels > 0 && real.heightPixels > 0) {
                    return real.widthPixels to real.heightPixels
                }
            }
        } catch (_: Throwable) {
            // fall through
        }

        // 3. App-window metrics — last resort.
        val app = context.resources.displayMetrics
        return app.widthPixels to app.heightPixels
    }
}
