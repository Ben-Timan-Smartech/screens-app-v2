package com.smartech.screens.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Runtime detection of the kind of device the player is running on.
 *
 * The same APK ships on retail tablets (touch) and on TV-class devices —
 * Android TV, Fire TV sticks, signage boxes — that have only a D-pad
 * remote. We need to know which input modality is available so that:
 *
 *   • The four-corner-tap staff unlock only fires on touch devices (it's
 *     useless on a remote-only device anyway).
 *   • A D-pad equivalent — up, up, down, down — unlocks the staff UI on
 *     TV-class devices.
 *
 * Detection rules, in order of authority:
 *
 *   1. If [UiModeManager] reports a UI_MODE_TYPE_TELEVISION — it's a TV.
 *      Most Android TV / Fire TV / signage boxes set this correctly.
 *   2. If the device declares no [PackageManager.FEATURE_TOUCHSCREEN]
 *      and the configuration's touchscreen field is NOTOUCH — treat as TV.
 *   3. Otherwise assume touch is available.
 *
 * The combination matters because some TV devices report a no-op
 * touchscreen feature (legacy Android 5 sticks), and some tablets
 * occasionally enter a "leanback" UI mode mid-session.
 */
object InputMode {

    /** True if the device has a real touchscreen we can rely on. */
    fun hasTouch(context: Context): Boolean {
        // Some TVs report touchscreen feature for compatibility; the
        // configuration field is the more reliable signal there.
        val configTouch = context.resources.configuration.touchscreen
        if (configTouch == Configuration.TOUCHSCREEN_NOTOUCH) return false

        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return false

        // Even if both above pass, an explicit TV uiMode flips us to no-touch.
        if (isTelevision(context)) return false

        return true
    }

    /** True for Android TV / Fire TV / leanback devices. */
    fun isTelevision(context: Context): Boolean {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            ?: return false
        return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /**
     * Convenience: anything that should drive the "TV-style" UX. Either
     * the OS reports leanback, or there's no touchscreen at all.
     */
    fun isTvLike(context: Context): Boolean = isTelevision(context) || !hasTouch(context)
}
