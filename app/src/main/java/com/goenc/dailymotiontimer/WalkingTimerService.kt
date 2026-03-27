package com.goenc.dailymotiontimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class WalkingTimerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var tickerJob: Job? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTextToSpeechReady = false

    private var currentPhase = WalkingPhase.Fast
    private var totalElapsedBeforeRunSeconds = 0
    private var phaseElapsedBeforeRunSeconds = 0
    private var runStartedAtElapsedRealtime = 0L
    private var phaseStartedAtElapsedRealtime = 0L
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeTextToSpeech()
        publishCurrentState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_OR_RESUME -> startOrResumeTimer()
            ACTION_PAUSE -> pauseTimer()
            ACTION_STOP -> stopTimer()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isTextToSpeechReady = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startOrResumeTimer() {
        if (isRunning) {
            publishCurrentState()
            return
        }

        val now = SystemClock.elapsedRealtime()
        runStartedAtElapsedRealtime = now
        phaseStartedAtElapsedRealtime = now
        isRunning = true

        val state = calculateSnapshot(now, announceTransitions = false)
        startForeground(NOTIFICATION_ID, buildNotification(state))
        publishState(state)
        startTicker()
    }

    private fun pauseTimer() {
        if (!isRunning) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            publishCurrentState()
            return
        }

        val state = calculateSnapshot(
            now = SystemClock.elapsedRealtime(),
            announceTransitions = true,
        )
        totalElapsedBeforeRunSeconds = state.elapsedSeconds
        phaseElapsedBeforeRunSeconds = PHASE_DURATION_SECONDS - state.remainingSeconds
        isRunning = false
        tickerJob?.cancel()
        tickerJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        publishState(state.copy(isRunning = false))
    }

    private fun stopTimer() {
        tickerJob?.cancel()
        tickerJob = null
        isRunning = false
        totalElapsedBeforeRunSeconds = 0
        phaseElapsedBeforeRunSeconds = 0
        runStartedAtElapsedRealtime = 0L
        phaseStartedAtElapsedRealtime = 0L
        currentPhase = WalkingPhase.Fast
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        publishCurrentState()
        stopSelf()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive && isRunning) {
                val state = calculateSnapshot(
                    now = SystemClock.elapsedRealtime(),
                    announceTransitions = true,
                )
                publishState(state)
                NotificationManagerCompat.from(this@WalkingTimerService)
                    .notify(NOTIFICATION_ID, buildNotification(state))
                delay(TICK_INTERVAL_MILLIS)
            }
        }
    }

    private fun calculateSnapshot(now: Long, announceTransitions: Boolean): TimerUiState {
        if (!isRunning) {
            return TimerUiState(
                currentPhase = currentPhase,
                remainingSeconds = PHASE_DURATION_SECONDS - phaseElapsedBeforeRunSeconds,
                elapsedSeconds = totalElapsedBeforeRunSeconds,
                isRunning = false,
            )
        }

        val totalElapsedSeconds =
            totalElapsedBeforeRunSeconds + ((now - runStartedAtElapsedRealtime) / 1_000L).toInt()
        var phaseElapsedSeconds =
            phaseElapsedBeforeRunSeconds + ((now - phaseStartedAtElapsedRealtime) / 1_000L).toInt()

        while (phaseElapsedSeconds >= PHASE_DURATION_SECONDS) {
            phaseElapsedSeconds -= PHASE_DURATION_SECONDS
            currentPhase = currentPhase.next()
            phaseElapsedBeforeRunSeconds = phaseElapsedSeconds
            phaseStartedAtElapsedRealtime = now - (phaseElapsedSeconds * 1_000L)
            if (announceTransitions) {
                speakSafely(currentPhase.announcement)
            }
        }

        return TimerUiState(
            currentPhase = currentPhase,
            remainingSeconds = PHASE_DURATION_SECONDS - phaseElapsedSeconds,
            elapsedSeconds = totalElapsedSeconds,
            isRunning = true,
        )
    }

    private fun publishCurrentState() {
        publishState(
            TimerUiState(
                currentPhase = currentPhase,
                remainingSeconds = PHASE_DURATION_SECONDS - phaseElapsedBeforeRunSeconds,
                elapsedSeconds = totalElapsedBeforeRunSeconds,
                isRunning = isRunning,
            ),
        )
    }

    private fun publishState(state: TimerUiState) {
        WalkingTimerController.publishState(state)
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                isTextToSpeechReady = false
                return@TextToSpeech
            }

            val result = textToSpeech?.setLanguage(Locale.JAPANESE)
            isTextToSpeechReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun speakSafely(message: String) {
        val tts = textToSpeech ?: return
        if (!isTextToSpeechReady) {
            return
        }
        runCatching {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "walking-phase")
        }
    }

    private fun buildNotification(state: TimerUiState): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(state.currentPhase.label)
            .setContentText(
                getString(
                    R.string.notification_status,
                    state.formattedRemainingTime,
                    state.formattedElapsedTime,
                ),
            )
            .setContentIntent(createContentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.timer_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.timer_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START_OR_RESUME = "com.goenc.dailymotiontimer.action.START_OR_RESUME"
        const val ACTION_PAUSE = "com.goenc.dailymotiontimer.action.PAUSE"
        const val ACTION_STOP = "com.goenc.dailymotiontimer.action.STOP"

        private const val NOTIFICATION_CHANNEL_ID = "walking_timer"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MILLIS = 1_000L

        fun createIntent(context: Context, action: String): Intent {
            return Intent(context, WalkingTimerService::class.java).apply {
                this.action = action
            }
        }
    }
}
