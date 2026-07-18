package com.example.a24_hr_clock.logic

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.example.a24_hr_clock.MainActivity
import com.example.a24_hr_clock.R
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BedtimeCountdownService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentBedtimeMillis: Long = 0
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (currentBedtimeMillis != 0L) {
                updateNotification()
                handler.postDelayed(this, 60000) // Refresh every minute
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bedtimeMillis = intent?.getLongExtra("bedtime_millis", 0) ?: 0L
        if (bedtimeMillis != 0L) {
            currentBedtimeMillis = bedtimeMillis
            startForeground(3002, createNotification())
            handler.removeCallbacks(updateRunnable)
            handler.postDelayed(updateRunnable, 60000)
        } else {
            stopSelf()
        }
        return START_STICKY
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(3002, createNotification())
    }

    private fun createNotification(): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            200,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val now = System.currentTimeMillis()
        
        // Ensure currentBedtimeMillis is in the future.
        // If it passes during the service lifecycle, flip it to tomorrow.
        if (now >= currentBedtimeMillis) {
            currentBedtimeMillis += (24 * 60 * 60 * 1000L)
        }

        val bedtime = LocalDateTime.ofInstant(Instant.ofEpochMilli(currentBedtimeMillis), ZoneId.systemDefault())
        val timeStr = bedtime.format(DateTimeFormatter.ofPattern("h:mm a"))
        
        // If the calculated next bedtime is roughly 24 hours away, 
        // check if the bedtime that just passed was within the last 6 hours.
        val lastBedtimeMillis = currentBedtimeMillis - (24 * 60 * 60 * 1000L)
        val isOverdue = now >= lastBedtimeMillis && now < lastBedtimeMillis + (6 * 60 * 60 * 1000L)
        
        val minutesRemaining = (currentBedtimeMillis - now) / 60000

        // Color Interpolation: White (#FFFFFF) to Gray (#888888) over 90 minutes
        val color = if (isOverdue) {
            Color.parseColor("#888888")
        } else if (minutesRemaining > 90) {
            Color.WHITE
        } else {
            // progress: 0 (at 90 mins) to 1 (at 0 mins)
            val progress = (90 - minutesRemaining).toFloat() / 90f
            val r = (255 - (255 - 136) * progress).toInt()
            val g = (255 - (255 - 136) * progress).toInt()
            val b = (255 - (255 - 136) * progress).toInt()
            Color.rgb(r, g, b)
        }

        val remoteViews = RemoteViews(packageName, R.layout.notification_bedtime).apply {
            setChronometerCountDown(R.id.bedtime_chronometer, true)
            if (isOverdue) {
                setViewVisibility(R.id.bedtime_chronometer, View.GONE)
                setViewVisibility(R.id.bedtime_overdue_text, View.VISIBLE)
                setTextColor(R.id.bedtime_overdue_text, color)
            } else {
                setViewVisibility(R.id.bedtime_chronometer, View.VISIBLE)
                setViewVisibility(R.id.bedtime_overdue_text, View.GONE)
                val elapsedRealtimeBase = android.os.SystemClock.elapsedRealtime() + (currentBedtimeMillis - now)
                setChronometer(R.id.bedtime_chronometer, elapsedRealtimeBase, null, true)
                setTextColor(R.id.bedtime_chronometer, color)
            }
        }

        return NotificationCompat.Builder(this, BedtimeNotificationManager.COUNTDOWN_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setGroup("com.example.a24_hr_clock.BEDTIME_COUNTDOWN")
            .setContentTitle("Bedtime")
            .setSubText("Target: $timeStr")
            .setCustomContentView(remoteViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        super.onDestroy()
    }
}
