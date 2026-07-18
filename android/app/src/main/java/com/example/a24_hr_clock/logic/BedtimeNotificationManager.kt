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

        /** Wind-down / countdown target is this many minutes before fall-asleep. */
        private const val BEDTIME_LEAD_MINUTES = 90L

        /** Inclusive fall-asleep window where the 10:00 PM clamp applies. */
        private val CLAMP_WINDOW_START = LocalTime.of(22, 0)
        private val CLAMP_WINDOW_END = LocalTime.of(23, 30)
        private val DEFAULT_BEDTIME = LocalTime.of(22, 0)

        /**
         * Resolves the next bedtime countdown/reminder target.
         * Uses the latest main sleep fall-asleep − 90 minutes (with scoped 10pm clamp);
         * falls back to the next 10:00 PM when no main sleep log exists.
         */
        fun resolveBedtimeMillis(
            logs: List<SleepLogEntry>,
            now: LocalDateTime = LocalDateTime.now()
        ): Long {
            val mainSleep = logs.filter { it.isMainSleep }.maxByOrNull { it.dateOfSleep }
            if (mainSleep != null) {
                calculateBedtimeMillis(mainSleep.startTime, now)?.let { return it }
            }
            return defaultBedtimeMillis(now)
        }

        fun defaultBedtimeMillis(now: LocalDateTime = LocalDateTime.now()): Long {
            return nextOccurrenceMillis(DEFAULT_BEDTIME, now)
        }

        fun calculateBedtimeMillis(
            startTimeIso: String,
            now: LocalDateTime = LocalDateTime.now()
        ): Long? {
            return try {
                val startTime = LocalDateTime.parse(startTimeIso.replace("Z", ""))

                // Subtract 1.5 hours
                var bedtime = startTime.minusMinutes(BEDTIME_LEAD_MINUTES)

                // Round to nearest 5 minutes
                val minutes = bedtime.minute
                val roundedMinutes = ((minutes + 2) / 5) * 5
                bedtime = bedtime.withMinute(0).plusMinutes(roundedMinutes.toLong()).withSecond(0).withNano(0)

                // Clamp to 10:00 PM only when fall-asleep is in [22:00, 23:30]
                val fallAsleepTime = startTime.toLocalTime()
                if (!fallAsleepTime.isBefore(CLAMP_WINDOW_START) && !fallAsleepTime.isAfter(CLAMP_WINDOW_END)) {
                    bedtime = bedtime.withHour(22).withMinute(0).withSecond(0).withNano(0)
                }

                nextOccurrenceMillis(bedtime.toLocalTime(), now)
            } catch (e: Exception) {
                Log.e(TAG, "Error calculating bedtime", e)
                null
            }
        }

        private fun nextOccurrenceMillis(timeOfDay: LocalTime, now: LocalDateTime): Long {
            var target = now
                .withHour(timeOfDay.hour)
                .withMinute(timeOfDay.minute)
                .withSecond(0)
                .withNano(0)
            if (!target.isAfter(now)) {
                target = target.plusDays(1)
            }
            return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
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
        val bedtimeMillis = resolveBedtimeMillis(logs)

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
