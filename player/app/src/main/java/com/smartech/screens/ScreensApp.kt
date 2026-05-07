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

        // Boot-time URL maintenance. Two jobs:
        //
        //   1. Pre-seed the live server URL with BuildConfig.API_BASE
        //      (minus the /api convention suffix) on first launch so a
        //      fresh tablet starts polling the production server with
        //      no "type the URL" step.
        //
        //   2. Re-normalise any URL that's already stored. Older builds
        //      (and humans typing into the admin panel) sometimes saved
        //      http://… for a public host — Cloud Run's HTTP→HTTPS 301
        //      then silently corrupts POSTs (heartbeat/register), so the
        //      tablet polls fine but never registers. setLiveServerUrl
        //      now upgrades http→https for non-LAN hosts; running the
        //      stored value back through it on boot auto-fixes legacy
        //      installs without making the user touch the admin panel.
        //
        // Both run on the same coroutine since they're cheap and only
        // touch DataStore. Only fires once per launch.
        scope.launch {
            runCatching {
                val current = store.liveServerUrl.first()
                if (current.isNullOrBlank()) {
                    val default = BuildConfig.API_BASE.removeSuffix("/api")
                    store.setLiveServerUrl(default)
                    Log.i("ScreensApp", "Pre-seeded liveServerUrl=$default")
                } else {
                    // Re-save through the setter to apply normalisation
                    // (http→https upgrade, trailing-slash strip). The
                    // setter is a no-op when nothing changes; cheap and
                    // safe to run unconditionally on every boot.
                    store.setLiveServerUrl(current)
                    val after = store.liveServerUrl.first()
                    if (after != current) {
                        Log.i("ScreensApp", "Normalised liveServerUrl: $current → $after")
                    }
                }
            }.onFailure { Log.w("ScreensApp", "URL maintenance skipped", it) }
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
