package com.smartech.screens.util

import android.app.Activity
import android.content.Context
import android.view.Display
import android.view.WindowManager

/**
 * Helpers around [Display.Mode] — the API for picking a specific HDMI
 * output resolution / refresh rate on Android TV / signage boxes.
 *
 * Why this exists: cheap boxes like the TX3 Mini ship with HDMI output
 * fixed to 720p even when the panel and the box both support 1080p.
 * The previous heuristic ("trust whatever Display.getMode().physical*
 * says") faithfully reports the boot-time 720p mode as the device's
 * resolution, which is correct but unhelpful — the user wants to be
 * able to override it from the CMS.
 *
 * Android exposes this via [WindowManager.LayoutParams.preferredDisplayModeId]:
 * set it to a Display.Mode.modeId that the display advertises in
 * [Display.getSupportedModes], and on the next surface attach the
 * system asks the HDMI sink to switch to that mode. Requires API 23+
 * (everywhere we ship to).
 */
object DisplayModes {

    /** A trimmed-down [Display.Mode] suitable for JSON wire transport. */
    data class Snapshot(
        val id: Int,
        val width: Int,
        val height: Int,
        val refreshHz: Float,
    )

    /**
     * Enumerate every HDMI mode the attached display advertises.
     * Returns an empty list on devices that don't support the API,
     * which is fine — the CMS just falls back to "auto" and doesn't
     * render the picker.
     */
    fun supported(context: Context): List<Snapshot> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return emptyList()
        @Suppress("DEPRECATION")
        val display: Display = wm.defaultDisplay ?: return emptyList()
        val modes = try {
            display.supportedModes
        } catch (_: Throwable) {
            return emptyList()
        }
        return modes
            .map { Snapshot(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
            // Sort: bigger pixels first, then higher refresh. Makes the
            // CMS picker default-to-best at the top without re-sorting
            // there.
            .sortedWith(compareByDescending<Snapshot> { it.width.toLong() * it.height }
                .thenByDescending { it.refreshHz })
    }

    /** The modeId currently selected, or 0 if unknown. */
    fun active(context: Context): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return 0
        return try {
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.mode?.modeId ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * Apply [modeId] to [activity]'s window. Passing 0 (or a modeId
     * that isn't in supportedModes) clears the preference — Android
     * then falls back to the default mode the OS would have picked.
     *
     * Must be called on the UI thread; setting `attributes` from a
     * background thread throws.
     */
    fun apply(activity: Activity, modeId: Int) {
        val window = activity.window ?: return
        val current = window.attributes.preferredDisplayModeId
        if (current == modeId) return
        // Validate against the supported list — silently no-op rather
        // than poke the system with a mode it doesn't know about.
        val supported = supported(activity).map { it.id }.toSet()
        val target = if (modeId in supported) modeId else 0
        if (current == target) return
        val params = window.attributes
        params.preferredDisplayModeId = target
        window.attributes = params
        LogBuffer.i(
            "DisplayModes",
            if (target == 0) "Cleared preferredDisplayModeId (was $current)"
            else "preferredDisplayModeId → $target (was $current)",
        )
    }
}
