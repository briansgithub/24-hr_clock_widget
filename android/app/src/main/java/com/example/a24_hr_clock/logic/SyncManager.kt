package com.example.a24_hr_clock.logic

import android.content.Context
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

class SyncManager(private val context: Context) {

    fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            15, TimeUnit.MINUTES, // Minimum interval allowed
            5, TimeUnit.MINUTES   // Flex interval
        )
            .setConstraints(constraints)
            .addTag(SYNC_WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
            syncRequest
        )
    }

    fun scheduleEmpiricalEnergyWorker() {
        val surveyRequest = PeriodicWorkRequestBuilder<EmpiricalEnergyWorker>(
            30, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES
        )
            .addTag("empirical_energy_work_tag")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "empirical_energy_work",
            ExistingPeriodicWorkPolicy.KEEP,
            surveyRequest
        )
    }

    fun scheduleMissedDataCheckWorker() {
        val currentDate = Calendar.getInstance()
        val dueDate = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 22) // 10:00 PM
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        if (dueDate.before(currentDate)) {
            dueDate.add(Calendar.HOUR_OF_DAY, 24)
        }
        val initialDelay = dueDate.timeInMillis - currentDate.timeInMillis

        val checkRequest = PeriodicWorkRequestBuilder<MissedDataCheckWorker>(
            24, TimeUnit.HOURS
        )
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .addTag("missed_data_check_tag")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "missed_data_check_work",
            ExistingPeriodicWorkPolicy.KEEP,
            checkRequest
        )
    }

    fun cancelPeriodicSync() {
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork("empirical_energy_work")
        WorkManager.getInstance(context).cancelUniqueWork("missed_data_check_work")
    }

    companion object {
        private const val SYNC_WORK_NAME = "fitbit_sync_work"
        private const val SYNC_WORK_TAG = "fitbit_sync"
    }
}
