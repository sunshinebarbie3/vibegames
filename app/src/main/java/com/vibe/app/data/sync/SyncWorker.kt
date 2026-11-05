package com.vibe.app.data.sync

import android.content.Context
import androidx.work.*
import com.vibe.app.VibeApplication
import com.vibe.app.core.VibeResult
import com.vibe.app.data.remote.ApiErrorKind
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as VibeApplication).container
        return when (val result = container.syncManager.syncNow()) {
            is VibeResult.Ok -> Result.success()
            is VibeResult.Problem -> if (result.kind == ApiErrorKind.NETWORK || result.kind == ApiErrorKind.SERVER) Result.retry() else Result.success()
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
