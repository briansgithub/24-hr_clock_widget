package com.example.a24_hr_clock.logic

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class BedtimeNotificationManager(private val context: Context) {

    companion object {
        const val REMINDER_CHANNEL_ID = "bedtime_reminder_channel"
        const val COUNTDOWN_CHANNEL_ID = "bedtime_countdown_channel"
        private const val TAG = "BedtimeNotifManager"

        fun resolveBedtimeMillis(logs: List<SleepLogEntry>): Long? {
            val mainSleep = logs.filter { it.isMainSleep }.maxByOrNull { it.dateOfSleep } ?: return null
            return calculateBedtimeMillis(mainSleep.startTime)
        }

        fun calculateBedtimeMillis(startTimeIso: String): Long? {
            return try {
                val startTime = LocalDateTime.parse(startTimeIso.replace("Z", ""))

                // Subtract 1.5 hours
                var bedtime = startTime.minusMinutes(90)

                // Round to nearest 5 minutes
                val minutes = bedtime.minute
                val roundedMinutes = ((minutes + 2) / 5) * 5
                bedtime = bedtime.withMinute(0).plusMinutes(roundedMinutes.toLong()).withSecond(0).withNano(0)

                // Never schedule bedtime earlier than 10:00 PM
                val earliestBedtime = LocalTime.of(22, 0)
                if (bedtime.toLocalTime().isBefore(earliestBedtime)) {
                    bedtime = bedtime.withHour(22).withMinute(0).withSecond(0).withNano(0)
                }

                val now = LocalDateTime.now()
                var target = bedtime.withYear(now.year).withMonth(now.monthValue).withDayOfMonth(now.dayOfMonth)

                // Always return the NEXT occurrence (strictly in the future)
                if (!target.isAfter(now)) {
                    target = target.plusDays(1)
                }

                target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e: Exception) {
                Log.e(TAG, "Error calculating bedtime", e)
                null
            }
        }
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Reminder Channel
            val reminderChannel = NotificationChannel(
                REMINDER_CHANNEL_ID,
                "Bedtime Reminder",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts 1.5 hours before calculated bedtime"
            }
            notificationManager.createNotificationChannel(reminderChannel)

            // Countdown Channel
            val countdownChannel = NotificationChannel(
                COUNTDOWN_CHANNEL_ID,
                "Bedtime Countdown",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent countdown to bedtime"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(countdownChannel)
        }
    }

    suspend fun updateNotifications() {
        val fitbitManager = FitbitManager(context)
        val logs = fitbitManager.getLastSleepLogs()
        if (logs.isEmpty()) {
            Log.d(TAG, "No sleep logs found, skipping notification update")
            return
        }

        val bedtimeMillis = resolveBedtimeMillis(logs) ?: run {
            Log.d(TAG, "No main sleep found, skipping notification update")
            return
        }

        Log.d(TAG, "Calculated bedtime: ${LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(bedtimeMillis), ZoneId.systemDefault())}")

        scheduleReminder(bedtimeMillis)
        startCountdownService(bedtimeMillis)
    }

    private fun scheduleReminder(bedtimeMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, BedtimeReminderReceiver::class.java).apply {
            putExtra("bedtime_millis", bedtimeMillis)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bedtimeMillis, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bedtimeMillis, pendingIntent)
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bedtimeMillis, pendingIntent)
        }
    }

    private fun startCountdownService(bedtimeMillis: Long) {
        val intent = Intent(context, BedtimeCountdownService::class.java).apply {
            putExtra("bedtime_millis", bedtimeMillis)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
