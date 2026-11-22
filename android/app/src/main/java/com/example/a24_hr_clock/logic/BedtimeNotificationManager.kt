package com.example.a24_hr_clock.logic

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.example.a24_hr_clock.MainActivity
import com.example.a24_hr_clock.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BedtimeNotificationManager(private val context: Context) {

    companion object {
        const val REMINDER_CHANNEL_ID = "bedtime_reminder_channel"
        const val COUNTDOWN_CHANNEL_ID = "bedtime_countdown_channel"
        const val REMINDER_NOTIFICATION_ID = 3001
        const val COUNTDOWN_NOTIFICATION_ID = 3002
        private const val TAG = "BedtimeNotifManager"
        private const val STATE_PREFS_NAME = "bedtime_notification_state"
        private const val KEY_LAST_WAKE_END_TIME = "last_wake_end_time"

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

        clearStaleReminderIfWakeChanged(logs)
        scheduleReminder(bedtimeMillis)
        postCountdownNotification(bedtimeMillis)
    }

    /**
     * A previously posted "Time for bed" reminder was computed from the prior wake-up time.
     * If today's main sleep now reports a different wake-up (endTime), that posted notification
     * no longer reflects the current bedtime target, so clear it away.
     */
    private fun clearStaleReminderIfWakeChanged(logs: List<SleepLogEntry>) {
        val mainSleep = logs.filter { it.isMainSleep }.maxByOrNull { it.dateOfSleep } ?: return
        val today = LocalDate.now().toString()
        if (mainSleep.dateOfSleep != today) return

        val prefs = context.getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
        val lastKnownWakeEndTime = prefs.getString(KEY_LAST_WAKE_END_TIME, null)
        if (lastKnownWakeEndTime != null && lastKnownWakeEndTime != mainSleep.endTime) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(REMINDER_NOTIFICATION_ID)
            Log.d(TAG, "New wake-up time detected for today ($lastKnownWakeEndTime -> ${mainSleep.endTime}); cleared stale bedtime reminder")
        }
        prefs.edit().putString(KEY_LAST_WAKE_END_TIME, mainSleep.endTime).apply()
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

        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bedtimeMillis, pendingIntent)
    }

    private fun postCountdownNotification(bedtimeMillis: Long) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        try {
            notificationManager.notify(COUNTDOWN_NOTIFICATION_ID, countdownNotification(bedtimeMillis))
        } catch (e: SecurityException) {
            Log.w(TAG, "Countdown notification was not posted", e)
        }
    }

    private fun countdownNotification(bedtimeMillis: Long): android.app.Notification {
        var targetMillis = bedtimeMillis
        val now = System.currentTimeMillis()
        if (now >= targetMillis) targetMillis += 24L * 60L * 60L * 1000L

        val lastBedtimeMillis = targetMillis - 24L * 60L * 60L * 1000L
        val isOverdue = now >= lastBedtimeMillis && now < lastBedtimeMillis + 6L * 60L * 60L * 1000L
        val minutesRemaining = (targetMillis - now) / 60000
        val color = when {
            isOverdue -> Color.parseColor("#888888")
            minutesRemaining > 90 -> Color.WHITE
            else -> {
                val progress = (90 - minutesRemaining).toFloat() / 90f
                val channel = (255 - (255 - 136) * progress).toInt()
                Color.rgb(channel, channel, channel)
            }
        }
        val timeStr = LocalDateTime.ofInstant(Instant.ofEpochMilli(targetMillis), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a"))
        val remoteViews = RemoteViews(context.packageName, R.layout.notification_bedtime).apply {
            setChronometerCountDown(R.id.bedtime_chronometer, true)
            if (isOverdue) {
                setViewVisibility(R.id.bedtime_chronometer, View.GONE)
                setViewVisibility(R.id.bedtime_overdue_text, View.VISIBLE)
                setTextColor(R.id.bedtime_overdue_text, color)
            } else {
                setViewVisibility(R.id.bedtime_chronometer, View.VISIBLE)
                setViewVisibility(R.id.bedtime_overdue_text, View.GONE)
                val elapsedRealtimeBase = android.os.SystemClock.elapsedRealtime() + (targetMillis - now)
                setChronometer(R.id.bedtime_chronometer, elapsedRealtimeBase, null, true)
                setTextColor(R.id.bedtime_chronometer, color)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            200,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, COUNTDOWN_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Bedtime")
            .setSubText("Target: $timeStr")
            .setCustomContentView(remoteViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }
}
