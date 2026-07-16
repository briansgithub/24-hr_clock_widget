package com.example.a24_hr_clock.logic

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "Starting background sync for Fitbit data")
        val fitbitManager = FitbitManager(applicationContext)
        
        return try {
            fitbitManager.refreshMetrics()
            Log.d("SyncWorker", "Background sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Background sync failed", e)
            Result.retry()
        }
    }
}
