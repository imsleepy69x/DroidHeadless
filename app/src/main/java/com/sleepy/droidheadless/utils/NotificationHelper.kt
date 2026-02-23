// File: app/src/main/java/com/sleepy/droidheadless/utils/NotificationHelper.kt
package com.sleepy.droidheadless.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sleepy.droidheadless.MainActivity
import com.sleepy.droidheadless.R

/**
 * Manages the persistent foreground notification for the headless browser service.
 * 
 * The notification shows live stats: port number, request count, and data transferred.
 * It updates in real-time as network traffic flows through the browser.
 */
object NotificationHelper {

    const val CHANNEL_ID = "droidheadless_service"
    const val NOTIFICATION_ID = 1001

    /**
     * Creates the notification channel (required for Android 8.0+).
     * Called once during service startup.
     */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW // Low = no sound, but visible
        ).apply {
            description = context.getString(R.string.channel_description)
            setShowBadge(false)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * Builds the foreground service notification with current stats.
     *
     * @param port The CDP server port number
     * @param requestCount Number of network requests intercepted
     * @param bytesTransferred Total bytes transferred (formatted as human-readable)
     */
    fun buildNotification(
        context: Context,
        port: Int,
        requestCount: Int = 0,
        bytesTransferred: Long = 0L
    ): Notification {
        // Tapping the notification opens the main activity
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val bytesText = formatBytes(bytesTransferred)
        val contentText = context.getString(
            R.string.notification_text_template,
            port,
            requestCount,
            bytesText
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Can't be swiped away
            .setOnlyAlertOnce(true) // Don't buzz on every update
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Updates the notification with new stats without recreating the service binding.
     */
    fun updateNotification(
        context: Context,
        port: Int,
        requestCount: Int,
        bytesTransferred: Long
    ) {
        val notification = buildNotification(context, port, requestCount, bytesTransferred)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Formats byte count into human-readable string.
     * Examples: "0 B", "1.5 KB", "23.4 MB", "1.2 GB"
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.1f GB".format(gb)
    }
}
