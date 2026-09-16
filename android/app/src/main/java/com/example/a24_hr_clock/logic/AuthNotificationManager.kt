package com.example.a24_hr_clock.logic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.a24_hr_clock.MainActivity

class AuthNotificationManager(private val context: Context) {
    private val channelId = "auth_notifications"
    private val notificationId = 1001

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Authentication Required"
            val descriptionText = "Notifications for when Fitbit or Calendar needs reconnection"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun cancel() { NotificationManagerCompat.from(context).cancel(notificationId) }

    fun showReauthNotification(requestId: String, provider: String = "Fitbit"): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // We can pass an extra to tell MainActivity to open the auth URL immediately
            putExtra("action", "reauth")
            putExtra(RECONNECT_REQUEST_ID, requestId)
        }
        
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("$provider Reconnection Needed")
            .setContentText("Tap to quickly reconnect and resume syncing sleep data.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .addAction(0, "Reconnect $provider", pendingIntent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(context)) {
            try {
                notify(notificationId, builder.build())
                return true
            } catch (e: SecurityException) {
                return false
            }
        }
    }

    companion object {
        const val RECONNECT_REQUEST_ID = "reconnect_request_id"
    }
}
