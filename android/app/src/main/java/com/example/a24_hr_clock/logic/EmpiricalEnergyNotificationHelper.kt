package com.example.a24_hr_clock.logic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.a24_hr_clock.MainActivity

class EmpiricalEnergyNotificationHelper(private val context: Context) {
    private val channelId = "empirical_energy_notifications"
    private val surveyNotificationId = 2001
    private val missedNotificationId = 2002

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Empirical Energy Logging"
            val descriptionText = "Prompts for periodic energy level logging and reminders"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250) // Double buzz
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showSurveyNotification() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("action", "log_energy")
        }

        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setGroup("com.example.a24_hr_clock.ALERTNESS_PROMPTS")
            .setContentTitle("Current Alertness Check")
            .setContentText("How energetic are you feeling right now? Tap to log (0-100).")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(context)) {
            try {
                notify(surveyNotificationId, builder.build())
            } catch (e: SecurityException) {
                // Ignore missing POST_NOTIFICATIONS permission
            }
        }
    }

    fun showMissedDataNotification(missedCount: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("action", "log_history")
        }

        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context, 2, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setGroup("com.example.a24_hr_clock.ALERTNESS_PROMPTS")
            .setContentTitle("Missed Energy Logs Detected")
            .setContentText("You have $missedCount missed data points today. Tap to fill them in.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(context)) {
            try {
                notify(missedNotificationId, builder.build())
            } catch (e: SecurityException) {
                // Ignore missing POST_NOTIFICATIONS permission
            }
        }
    }
}
