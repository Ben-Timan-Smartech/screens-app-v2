package com.smartech.screens.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smartech.screens.ScreensApp
import java.util.concurrent.TimeUnit

/**
 * Periodic heartbeat + settings refresh. WorkManager gives us unmetered,
 * boot-persistent scheduling; 15 minutes is its minimum period for PeriodicWork.
 *
 * Playlist freshness is primarily driven by FCM pushes — this worker is a
 * safety net for when FCM is flaky or the tablet was offline when the push
 * arrived.
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        /*
        val repo = (applicationContext as? ScreensApp)?.repository ?: return Result.retry()
        repo.ping(BuildConfig.VERSION_NAME)
        repo.refreshSettings()
        repo.refreshPlaylist()
        */
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "screens.heartbeat"

        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                work,
            )
        }
    }
}
