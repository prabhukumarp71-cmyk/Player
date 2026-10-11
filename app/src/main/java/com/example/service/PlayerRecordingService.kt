package com.example.service

import android.annotation.SuppressLint
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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.camera.CameraRecorderManager

class PlayerRecordingService : Service() {

    private var isNotificationDismissedByUser = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Player:BackgroundRecordingWakeLock")
        } catch (_: Exception) {}
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
            CameraRecorderManager.getInstance(this).stopRecording()
            stopForegroundService()
            return START_NOT_STICKY
        }

        val quality = intent?.getStringExtra(EXTRA_QUALITY) ?: "1080p FHD"
        val isFarOnly = intent?.getBooleanExtra(EXTRA_FAR_ONLY, false) ?: false

        isNotificationDismissedByUser = false
        val notification = buildRecordingNotification(quality, isFarOnly)

        // Acquire WakeLock to keep CPU alive in background and while screen is off
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(3 * 60 * 60 * 1000L /* 3 hours max */)
            }
        } catch (_: Exception) {}

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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // When the app is swiped away from Recents / closed, do NOT stop recording!
        // The foreground service continues recording in background until explicitly stopped.
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

        val stopIntent = Intent(this, PlayerRecordingService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val pendingStop = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeLabel = if (isFarOnly) " [Far Only Mode]" else ""
        val content = "Recording in background • $quality$modeLabel"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Player - Video Recording Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(false) // Allowed to be swiped away by user
            .setAutoCancel(true) // Automatically clears on interaction
            .setContentIntent(pendingLaunch)
            .setDeleteIntent(pendingDismiss) // Triggered when swiped away
            .addAction(android.R.drawable.ic_media_pause, "Stop Recording", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun stopForegroundService() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}

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

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
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
