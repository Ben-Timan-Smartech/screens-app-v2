package com.smartech.screens.fcm

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.smartech.screens.ScreensApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles push messages from the admin API.
 *
 * Payloads are tiny by design (per engineering brief): `{ "type": "playlist.updated" }`.
 * On any message we pull fresh state from the device API rather than trusting
 * the FCM payload to be the source of truth.
 */
class ScreensFcmService : FirebaseMessagingService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token refreshed")
        val repo = (application as? ScreensApp)?.repository ?: return
        val store = repo.store
        scope.launch {
            runCatching {
                store.saveFcmToken(token)
                repo.api.updateFcmToken(com.smartech.screens.data.FcmTokenRequest(token))
            }.onFailure { Log.w(TAG, "updateFcmToken failed", it) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"] ?: return
        Log.i(TAG, "Push received: type=$type")
        val repo = (application as? ScreensApp)?.repository ?: return
        scope.launch {
            when (type) {
                "playlist.updated" -> repo.refreshPlaylist()
                "settings.updated" -> repo.refreshSettings()
                "reboot" -> {
                    // Use the same safe path as the LAN "reboot" command
                    // (PlayerRepository.executeCommand): relaunch the activity
                    // via scheduleSelfRestart() instead of killing the process.
                    // killProcess left tablets dark — on Android 11+ there's no
                    // boot receiver / alarm to bring us back, so nothing
                    // relaunched. scheduleSelfRestart() bounces the activity
                    // with CLEAR_TASK while the JVM keeps running.
                    Log.i(TAG, "Reboot requested — restarting activity")
                    repo.scheduleSelfRestart()
                }
                "cache.clear" -> {
                    repo.cache.reconcile(keep = emptySet(), capBytes = 0L)
                    repo.refreshPlaylist()
                }
                else -> Log.w(TAG, "Unknown push type: $type")
            }
        }
    }

    companion object {
        private const val TAG = "ScreensFcm"
    }
}
