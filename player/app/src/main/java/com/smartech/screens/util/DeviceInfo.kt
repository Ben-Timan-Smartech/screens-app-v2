package com.smartech.screens.util

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics

/** Snapshot of the host device used for registration and tier selection. */
data class DeviceSnapshot(
    val ramMb: Int,
    val widthPx: Int,
    val heightPx: Int,
    val orientation: String, // LANDSCAPE | PORTRAIT
)

object DeviceInfo {
    fun snapshot(context: Context): DeviceSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val metrics: DisplayMetrics = context.resources.displayMetrics
        val orientation = when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
            else -> "LANDSCAPE"
        }
        return DeviceSnapshot(
            ramMb = (mem.totalMem / (1024 * 1024)).toInt(),
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            orientation = orientation,
        )
    }
}
