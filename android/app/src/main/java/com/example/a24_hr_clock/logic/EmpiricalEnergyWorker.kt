package com.example.a24_hr_clock.logic

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class EmpiricalEnergyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("EmpiricalEnergyWorker", "Triggering 30-minute survey check")
        val manager = EmpiricalEnergyManager(applicationContext)
        val helper = EmpiricalEnergyNotificationHelper(applicationContext)
        val fitbit = FitbitManager(applicationContext)

        return try {
            val nowMs = System.currentTimeMillis()

            // 1. Initialize this 30-minute interval as MISSED (empty) if not already logged
            val alignedTs = manager.alignTo30MinInterval(nowMs)
            val logs = manager.loadLogs()
            val existing = logs.find { it.timestamp == alignedTs }
            
            // 2. Fetch the cached Fitbit sleep logs and run the retroactive sleep filter
            try {
                val sleepLogs = fitbit.getLastSleepLogs()
                manager.runRetroactiveSleepFilter(sleepLogs)
            } catch (e: Exception) {
                Log.e("EmpiricalEnergyWorker", "Failed to run retroactive sleep filter", e)
            }

            // Reload logs after sleep filter updates
            val updatedLogs = manager.loadLogs()
            val isCurrentlySleeping = updatedLogs.any { it.timestamp == alignedTs && it.status == "SLEEP_EXCLUDED" }

            // 3. Trigger survey notification only if NOT currently in a sleep window
            if (!isCurrentlySleeping) {
                if (existing == null) {
                    manager.logEnergy(nowMs, null, uploadDrive = false) // MISSED seed; Drive on user entry
                }
                helper.showSurveyNotification()
            } else {
                Log.d("EmpiricalEnergyWorker", "Survey notification skipped because user is in a sleep window")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("EmpiricalEnergyWorker", "Failed in survey check worker", e)
            Result.failure()
        }
    }
}
