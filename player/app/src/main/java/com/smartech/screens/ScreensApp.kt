package com.smartech.screens

import android.app.Application
import android.util.Log
import com.smartech.screens.data.ApiClient
import com.smartech.screens.data.DeviceStore
import com.smartech.screens.data.PlayerRepository
import com.smartech.screens.data.VideoCache
import com.smartech.screens.sync.HeartbeatWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application-wide singletons. Plain manual DI — we don't need Hilt for six
 * collaborators and it keeps the APK small.
 */
class ScreensApp : Application() {

    lateinit var store: DeviceStore
        private set
    lateinit var repository: PlayerRepository
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        store = DeviceStore(this)
        val client = ApiClient(tokenProvider = { store.tokenBlocking() })
        val cache = VideoCache(this, client.http)
        repository = PlayerRepository(this, store, client.api, cache, client.http)

        runCatching { HeartbeatWorker.schedule(this) }
            .onFailure { Log.e("ScreensApp", "HeartbeatWorker schedule failed", it) }

        // First-launch bootstrap of the live server URL. We pre-seed
        // DataStore with the build's API_BASE (minus the /api convention
        // suffix, since the stored field is the bare URL) so a fresh
        // tablet starts polling the production server immediately — no
        // per-device "type the URL" step in onboarding. Only fires when
        // the value is null/blank so it never clobbers a URL the user
        // explicitly entered later. To swap servers across the fleet,
        // ship a build with a new -PapiBase= and reinstall.
        scope.launch {
            runCatching {
                val current = store.liveServerUrl.first()
                if (current.isNullOrBlank()) {
                    val default = BuildConfig.API_BASE.removeSuffix("/api")
                    store.setLiveServerUrl(default)
                    Log.i("ScreensApp", "Pre-seeded liveServerUrl=$default")
                }
            }.onFailure { Log.w("ScreensApp", "URL pre-seed skipped", it) }
        }

        // Live LAN demo: continuously poll /api/state and fire heartbeats.
        // No-op when no liveServerUrl is configured — the loop just calls
        // refreshPlaylist which falls through to demo mode.
        repository.startLiveSync()

        // Kick off registration + first playlist fetch off the main thread.
        scope.launch {
            runCatching {
                repository.ensureRegistered(BuildConfig.JOIN_CODE)
                repository.refreshSettings()
                repository.refreshPlaylist()
            }.onFailure { Log.e("ScreensApp", "Initial sync failed", it) }
        }
    }
}
