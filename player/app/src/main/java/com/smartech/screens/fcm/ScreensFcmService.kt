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
                    // Best we can do without device-owner privilege: relaunch self.
                    Log.i(TAG, "Reboot requested — exiting to be relaunched by LAUNCHER intent")
                    android.os.Process.killProcess(android.os.Process.myPid())
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
