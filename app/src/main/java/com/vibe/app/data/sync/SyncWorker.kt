package com.vibe.app.data.sync

import android.content.Context
import androidx.work.*
import com.vibe.app.VibeApplication
import com.vibe.app.core.VibeResult
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that triggers a Firestore refresh when the device
 * reconnects. Firestore persistence handles offline sync natively, so this
 * worker simply refreshes cached data from the server.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as VibeApplication).container
        try {
            container.refreshAll()
            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("vibe-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
