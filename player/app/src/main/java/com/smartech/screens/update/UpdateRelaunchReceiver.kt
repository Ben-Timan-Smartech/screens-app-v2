package com.smartech.screens.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smartech.screens.util.LogBuffer

/**
 * Brings the player back after it updates itself (v0.2.21).
 *
 * Installing an update kills the running app, and the system installer then
 * sits on "App installed — Done / Open" with nothing on the shop floor to tap
 * Open. Android sends ACTION_MY_PACKAGE_REPLACED to the newly installed
 * version, so this runs in the new code even when the update was started by an
 * older version.
 *
 * Starting an activity from here works on Android 9 and older. Android 10+
 * blocks background starts unless the app holds an exemption, and the bound
 * [InstallAutoConfirmService] is one, so the pending flag also lets that
 * service retry the start (and press Open) when it reconnects.
 */
class UpdateRelaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Warning level so it reaches /api/logs: the fleet check for this fix.
        LogBuffer.w("AutoUpdate", "Update installed; reopening the player")
        InstallAutoConfirm.markRelaunchPending()
        InstallAutoConfirm.relaunchIfPending(context, "package replaced")
    }
}
