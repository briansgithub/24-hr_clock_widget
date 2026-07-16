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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class BedtimeNotificationManager(private val context: Context) {

    companion object {
        const val REMINDER_CHANNEL_ID = "bedtime_reminder_channel"
        const val COUNTDOWN_CHANNEL_ID = "bedtime_countdown_channel"
        private const val TAG = "BedtimeNotifManager"
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

        val mainSleep = logs.filter { it.isMainSleep }.maxByOrNull { it.dateOfSleep }
        if (mainSleep == null) {
            Log.d(TAG, "No main sleep found, skipping notification update")
            return
        }

        val bedtimeMillis = calculateBedtimeMillis(mainSleep.startTime) ?: return
        
        Log.d(TAG, "Calculated bedtime: ${LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(bedtimeMillis), ZoneId.systemDefault())}")

        scheduleReminder(bedtimeMillis)
        startCountdownService(bedtimeMillis)
    }

    private fun calculateBedtimeMillis(startTimeIso: String): Long? {
        return try {
            // Parse ISO string
            val startTime = LocalDateTime.parse(startTimeIso.replace("Z", ""))
            
            // Subtract 1.5 hours
            var bedtime = startTime.minusMinutes(90)
            
            // Round to nearest 5 minutes
            val minutes = bedtime.minute
            val roundedMinutes = ((minutes + 2) / 5) * 5
            bedtime = bedtime.withMinute(0).plusMinutes(roundedMinutes.toLong()).withSecond(0).withNano(0)

            // We want this time TODAY or TOMORROW (whenever the next occurrence is)
            val now = LocalDateTime.now()
            var target = bedtime.withYear(now.year).withMonth(now.monthValue).withDayOfMonth(now.dayOfMonth)
            
            // If the calculated time has already passed today, assume it's for the next cycle
            // But bedtime is usually late at night. If it's 5 AM and bedtime was 11 PM yesterday, target is in the past.
            // If it's 10 PM and bedtime is 11 PM, target is in the future.
            if (target.isBefore(now)) {
                target = target.plusDays(1)
            }

            target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating bedtime", e)
            null
        }
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
