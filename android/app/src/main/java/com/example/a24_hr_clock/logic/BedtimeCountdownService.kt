package com.example.a24_hr_clock.logic

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.a24_hr_clock.MainActivity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BedtimeCountdownService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bedtimeMillis = intent?.getLongExtra("bedtime_millis", 0) ?: 0L
        if (bedtimeMillis != 0L) {
            startForeground(3002, createNotification(bedtimeMillis))
        } else {
            stopSelf()
        }
        return START_STICKY
    }

    private fun createNotification(bedtimeMillis: Long): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            200,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bedtime = LocalDateTime.ofInstant(Instant.ofEpochMilli(bedtimeMillis), ZoneId.systemDefault())
        val timeStr = bedtime.format(DateTimeFormatter.ofPattern("h:mm a"))

        return NotificationCompat.Builder(this, BedtimeNotificationManager.COUNTDOWN_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Countdown to Bedtime")
            .setContentText("Target: $timeStr")
            .setOngoing(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(bedtimeMillis)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
