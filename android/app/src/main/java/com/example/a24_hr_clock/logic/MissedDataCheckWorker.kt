package com.example.a24_hr_clock.logic

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first

class MissedDataCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("MissedDataCheckWorker", "Running daily 10:00 PM check")
        val manager = EmpiricalEnergyManager(applicationContext)
        val helper = EmpiricalEnergyNotificationHelper(applicationContext)
        val settings = SettingsManager(applicationContext)
        val fitbit = FitbitManager(applicationContext)

        try {
            // 1. Refresh Fitbit sleep filters to ensure sleep intervals are updated
            try {
                val sleepLogs = fitbit.getLastSleepLogs()
                manager.runRetroactiveSleepFilter(sleepLogs)
            } catch (e: Exception) {
                Log.e("MissedDataCheckWorker", "Failed to update sleep filters", e)
            }

            // 2. Count missed logs outside of sleep hours
            val missedCount = manager.getMissedDataPointsCount()
            if (missedCount > 0) {
                Log.d("MissedDataCheckWorker", "Found $missedCount missed data points outside sleep. Notifying user.")
                helper.showMissedDataNotification(missedCount)
            } else {
                Log.d("MissedDataCheckWorker", "No missed data points outside sleep detected today.")
            }

            // 3. Auto-Export to Google Drive
            val modelSettings = settings.modelSettingsFlow.first()
            if (modelSettings.googleDriveUrl.isNotEmpty()) {
                Log.d("MissedDataCheckWorker", "Triggering daily auto-export to Google Drive")
                val (success, message) = manager.uploadToGoogleDrive(modelSettings.googleDriveUrl)
                if (success) {
                    Log.i("MissedDataCheckWorker", "Google Drive export succeeded: $message")
                } else {
                    Log.e("MissedDataCheckWorker", "Google Drive export failed: $message")
                }
            } else {
                Log.w("MissedDataCheckWorker", "Google Drive export skipped: Web App URL is empty")
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e("MissedDataCheckWorker", "Failed in daily missed data check worker", e)
            return Result.failure()
        }
    }
}
