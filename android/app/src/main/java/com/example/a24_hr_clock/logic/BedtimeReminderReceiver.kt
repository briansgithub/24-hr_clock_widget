package com.example.a24_hr_clock.logic

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.a24_hr_clock.MainActivity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BedtimeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bedtimeMillis = intent.getLongExtra("bedtime_millis", 0)
        if (bedtimeMillis == 0L) return

        val bedtime = LocalDateTime.ofInstant(Instant.ofEpochMilli(bedtimeMillis), ZoneId.systemDefault())
        val timeStr = bedtime.format(DateTimeFormatter.ofPattern("h:mm a"))

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val mainIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            100,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, BedtimeNotificationManager.REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Time for bed")
            .setContentText("Calculated bedtime: $timeStr")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(3001, notification)
    }
}
