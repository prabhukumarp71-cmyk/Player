package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity

class PlayerRecordingService : Service() {

    private var isNotificationDismissedByUser = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_DISMISS_NOTIFICATION) {
            isNotificationDismissedByUser = true
            // Swiping away the notification removes it permanently without interrupting recording
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.cancel(NOTIFICATION_ID)
            // Return START_STICKY so service and video recording continue running uninterrupted!
            return START_STICKY
        }

        if (action == ACTION_STOP_SERVICE) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        val quality = intent?.getStringExtra(EXTRA_QUALITY) ?: "1080p FHD"
        val isFarOnly = intent?.getBooleanExtra(EXTRA_FAR_ONLY, false) ?: false

        isNotificationDismissedByUser = false
        val notification = buildRecordingNotification(quality, isFarOnly)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun buildRecordingNotification(quality: String, isFarOnly: Boolean): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingLaunch = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, PlayerRecordingService::class.java).apply {
            action = ACTION_DISMISS_NOTIFICATION
        }
        val pendingDismiss = PendingIntent.getService(
            this,
            1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeLabel = if (isFarOnly) " [Far Only Mode]" else ""
        val content = "Recording in foreground • $quality$modeLabel (Swipe to dismiss)"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Player - Video Recording Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(false) // Allowed to be swiped away by user
            .setAutoCancel(true) // Automatically clears on interaction
            .setContentIntent(pendingLaunch)
            .setDeleteIntent(pendingDismiss) // Triggered when swiped away
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Player Foreground Recording",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows active foreground recording status for Player"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "player_recording_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START_SERVICE = "com.example.service.START_RECORDING"
        const val ACTION_STOP_SERVICE = "com.example.service.STOP_RECORDING"
        const val ACTION_DISMISS_NOTIFICATION = "com.example.service.DISMISS_NOTIFICATION"
        const val EXTRA_QUALITY = "extra_quality"
        const val EXTRA_FAR_ONLY = "extra_far_only"

        fun start(context: Context, quality: String, isFarOnly: Boolean) {
            val intent = Intent(context, PlayerRecordingService::class.java).apply {
                action = ACTION_START_SERVICE
                putExtra(EXTRA_QUALITY, quality)
                putExtra(EXTRA_FAR_ONLY, isFarOnly)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PlayerRecordingService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
