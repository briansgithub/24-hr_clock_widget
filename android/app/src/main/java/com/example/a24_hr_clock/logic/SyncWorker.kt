package com.example.a24_hr_clock.logic

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "Starting background sync for Fitbit and empirical data")
        val fitbitManager = FitbitManager(applicationContext)
        val bedtimeManager = BedtimeNotificationManager(applicationContext)
        val empiricalManager = EmpiricalEnergyManager(applicationContext)
        val settingsManager = SettingsManager(applicationContext)
        
        return try {
            val settings = settingsManager.modelSettingsFlow.first()
            
            fitbitManager.refreshMetrics()
            bedtimeManager.updateNotifications()
            
            // Run retroactive sleep filter on empirical logs
            val sleepLogs = fitbitManager.getLastSleepLogs()
            empiricalManager.runRetroactiveSleepFilter(sleepLogs)
            
            // Trigger auto-backup
            if (empiricalManager.autoBackup(settings)) {
                settingsManager.updateModelSettings(settings.copy(lastEmpiricalSync = System.currentTimeMillis()))
            }
            
            Log.d("SyncWorker", "Background sync and backup completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Background sync failed", e)
            Result.retry()
        }
    }
}
