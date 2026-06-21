package com.goenc.dailymotiontimer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal class WalkingTimerNotificationController(
    private val service: Service,
) {
    var isForeground: Boolean = false
        private set

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.timer_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = service.getString(R.string.timer_notification_channel_description)
        }
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    fun show(state: TimerUiState, promoteToForeground: Boolean) {
        val notification = build(state)
        if (promoteToForeground || !isForeground) {
            service.startForeground(NOTIFICATION_ID, notification)
            isForeground = true
        } else if (canPostNotifications()) {
            NotificationManagerCompat.from(service).notify(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    fun update(state: TimerUiState) {
        if (!state.isActive || !isForeground || !canPostNotifications()) return
        NotificationManagerCompat.from(service).notify(NOTIFICATION_ID, build(state))
    }

    fun stop() {
        isForeground = false
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(service).cancel(NOTIFICATION_ID)
    }

    private fun build(state: TimerUiState): Notification {
        val statusText = service.getString(
            if (state.isPaused) R.string.notification_paused else R.string.notification_running,
        )
        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(service.getString(R.string.app_name))
            .setContentText("${state.currentPhase.label} ${state.formattedRemainingTime}")
            .setSubText(statusText)
            .setContentIntent(createContentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(state.isActive)

        if (state.isRunning) {
            builder.addAction(
                0,
                service.getString(R.string.pause),
                createServicePendingIntent(WalkingTimerService.ACTION_PAUSE, REQUEST_CODE_PAUSE),
            )
        } else if (state.isPaused) {
            builder.addAction(
                0,
                service.getString(R.string.resume),
                createServicePendingIntent(WalkingTimerService.ACTION_START_OR_RESUME, REQUEST_CODE_RESUME),
            )
        }

        if (state.isActive) {
            builder.addAction(
                0,
                service.getString(R.string.stop),
                createServicePendingIntent(WalkingTimerService.ACTION_STOP, REQUEST_CODE_STOP),
            )
        }
        return builder.build()
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(service, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            service,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createServicePendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            service,
            requestCode,
            WalkingTimerService.createIntent(service, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(service, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CHANNEL_ID = "walking_timer"
        const val NOTIFICATION_ID = 1001
        const val REQUEST_CODE_PAUSE = 1
        const val REQUEST_CODE_RESUME = 2
        const val REQUEST_CODE_STOP = 3
    }
}
