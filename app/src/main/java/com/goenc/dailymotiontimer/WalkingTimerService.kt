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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    private data class PreciseTimerSnapshot(
        val uiState: TimerUiState,
        val totalElapsedMillis: Long,
        val phaseElapsedMillis: Long,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var tickerJob: Job? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTextToSpeechReady = false

    private var currentPhase = WalkingPhase.Fast
    private var totalElapsedBeforeRunMillis = 0L
    private var phaseElapsedBeforeRunMillis = 0L
    private var fastPhaseDurationSeconds = DEFAULT_PHASE_DURATION_SECONDS
    private var slowPhaseDurationSeconds = DEFAULT_PHASE_DURATION_SECONDS
    private var runStartedAtElapsedRealtime = 0L
    private var phaseStartedAtElapsedRealtime = 0L
    private var isRunning = false
    private var isPaused = false
    private var hasForegroundNotification = false
    private var lastObservedPhase = WalkingPhase.Fast

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeTextToSpeech()
        val initialState = restorePersistedState() ?: TimerUiState()
        lastObservedPhase = initialState.currentPhase
        publishState(initialState)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = when (intent?.action) {
            ACTION_START_OR_RESUME -> startOrResumeTimer()
            ACTION_PAUSE -> pauseTimer()
            ACTION_STOP -> {
                stopTimer()
                null
            }
            ACTION_RESTORE, null -> restoreActiveSession()
            else -> currentUiState()
        }
        return if (state?.isActive == true) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        if (currentUiState().isActive) {
            persistState(currentUiState())
        }
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isTextToSpeechReady = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startOrResumeTimer(): TimerUiState {
        if (isRunning) {
            val state = currentUiState()
            lastObservedPhase = state.currentPhase
            showNotification(state, promoteToForeground = !hasForegroundNotification)
            publishAndPersistState(state)
            return state
        }

        if (!isPaused) {
            restoreConfiguredPhaseDurations()
            resetTimerProgressState()
        }

        val now = SystemClock.elapsedRealtime()
        runStartedAtElapsedRealtime = now
        phaseStartedAtElapsedRealtime = now
        isRunning = true
        isPaused = false

        val state = calculateSnapshot(now, announceTransitions = false)
        lastObservedPhase = state.currentPhase
        showNotification(state, promoteToForeground = true)
        publishAndPersistState(state)
        startTicker()
        return state
    }

    private fun pauseTimer(): TimerUiState {
        if (!isRunning) {
            val state = currentUiState()
            if (state.isActive) {
                showNotification(state, promoteToForeground = !hasForegroundNotification)
                publishAndPersistState(state)
            } else {
                publishState(state)
            }
            return state
        }

        val now = SystemClock.elapsedRealtime()
        val snapshot = calculatePreciseSnapshot(now)
        val state = snapshot.uiState
        if (state.currentPhase != lastObservedPhase) {
            announcePhaseTransition(state.currentPhase)
        }
        totalElapsedBeforeRunMillis = snapshot.totalElapsedMillis
        phaseElapsedBeforeRunMillis = snapshot.phaseElapsedMillis
        currentPhase = state.currentPhase
        isRunning = false
        isPaused = true
        runStartedAtElapsedRealtime = 0L
        phaseStartedAtElapsedRealtime = 0L
        tickerJob?.cancel()
        tickerJob = null
        val pausedState = state.copy(isRunning = false, isPaused = true)
        lastObservedPhase = pausedState.currentPhase
        showNotification(pausedState, promoteToForeground = !hasForegroundNotification)
        publishAndPersistState(pausedState)
        return pausedState
    }

    private fun stopTimer() {
        tickerJob?.cancel()
        tickerJob = null
        resetTimerProgressState()
        val stoppedState = currentUiState()
        persistState(stoppedState)
        hasForegroundNotification = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        publishState(stoppedState)
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
                publishAndPersistState(state)
                updateNotification(state)
                delay(TICK_INTERVAL_MILLIS)
            }
        }
    }

    private fun calculateSnapshot(now: Long, announceTransitions: Boolean): TimerUiState {
        val snapshot = calculatePreciseSnapshot(now)
        val state = snapshot.uiState
        if (announceTransitions && state.currentPhase != lastObservedPhase) {
            announcePhaseTransition(state.currentPhase)
        }
        lastObservedPhase = state.currentPhase
        return state
    }

    private fun calculatePreciseSnapshot(now: Long): PreciseTimerSnapshot {
        if (!isRunning) {
            return PreciseTimerSnapshot(
                uiState = TimerUiState(
                    currentPhase = currentPhase,
                    remainingSeconds = remainingSecondsForPhaseMillis(
                        phase = currentPhase,
                        phaseElapsedMillis = phaseElapsedBeforeRunMillis,
                        fastPhaseDurationSeconds = fastPhaseDurationSeconds,
                        slowPhaseDurationSeconds = slowPhaseDurationSeconds,
                    ),
                    elapsedSeconds = elapsedSecondsFromMillis(totalElapsedBeforeRunMillis),
                    fastPhaseDurationSeconds = fastPhaseDurationSeconds,
                    slowPhaseDurationSeconds = slowPhaseDurationSeconds,
                    isRunning = false,
                    isPaused = isPaused,
                ),
                totalElapsedMillis = totalElapsedBeforeRunMillis,
                phaseElapsedMillis = phaseElapsedBeforeRunMillis,
            )
        }

        val totalElapsedMillis =
            totalElapsedBeforeRunMillis + (now - runStartedAtElapsedRealtime).coerceAtLeast(0L)
        val phaseProgress = advancePhaseProgressMillis(
            startingPhase = currentPhase,
            startingPhaseElapsedMillis = phaseElapsedBeforeRunMillis,
            additionalElapsedMillis = (now - phaseStartedAtElapsedRealtime).coerceAtLeast(0L),
            fastPhaseDurationSeconds = fastPhaseDurationSeconds,
            slowPhaseDurationSeconds = slowPhaseDurationSeconds,
        )

        return PreciseTimerSnapshot(
            uiState = TimerUiState(
                currentPhase = phaseProgress.currentPhase,
                remainingSeconds = remainingSecondsForPhaseMillis(
                    phase = phaseProgress.currentPhase,
                    phaseElapsedMillis = phaseProgress.phaseElapsedMillis,
                    fastPhaseDurationSeconds = fastPhaseDurationSeconds,
                    slowPhaseDurationSeconds = slowPhaseDurationSeconds,
                ),
                elapsedSeconds = elapsedSecondsFromMillis(totalElapsedMillis),
                fastPhaseDurationSeconds = fastPhaseDurationSeconds,
                slowPhaseDurationSeconds = slowPhaseDurationSeconds,
                isRunning = true,
                isPaused = false,
            ),
            totalElapsedMillis = totalElapsedMillis,
            phaseElapsedMillis = phaseProgress.phaseElapsedMillis,
        )
    }

    private fun publishCurrentState() {
        publishState(currentUiState())
    }

    private fun publishAndPersistState(state: TimerUiState) {
        publishState(state)
        persistState(state)
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

    private fun announcePhaseTransition(phase: WalkingPhase) {
        speakSafely(phase.announcement)
        vibrateSafely()
    }

    private fun vibrateSafely() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        TRANSITION_VIBRATION_DURATION_MILLIS,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(TRANSITION_VIBRATION_DURATION_MILLIS)
            }
        }
    }

    private fun buildNotification(state: TimerUiState): Notification {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
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
            .setOngoing(state.isActive)

        if (state.isRunning) {
            builder.addAction(
                0,
                getString(R.string.pause),
                createServicePendingIntent(ACTION_PAUSE, REQUEST_CODE_PAUSE),
            )
        } else if (state.isPaused) {
            builder.addAction(
                0,
                getString(R.string.resume),
                createServicePendingIntent(ACTION_START_OR_RESUME, REQUEST_CODE_RESUME),
            )
        }

        if (state.isActive) {
            builder.addAction(
                0,
                getString(R.string.stop),
                createServicePendingIntent(ACTION_STOP, REQUEST_CODE_STOP),
            )
        }

        return builder.build()
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

    private fun createServicePendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            createIntent(this, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showNotification(state: TimerUiState, promoteToForeground: Boolean) {
        val notification = buildNotification(state)
        if (promoteToForeground || !hasForegroundNotification) {
            startForeground(NOTIFICATION_ID, notification)
            hasForegroundNotification = true
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(state: TimerUiState) {
        if (!state.isActive || !hasForegroundNotification) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun restoreActiveSession(): TimerUiState? {
        val restoredState = restorePersistedState() ?: run {
            publishCurrentState()
            stopSelf()
            return null
        }

        if (restoredState.isActive) {
            showNotification(restoredState, promoteToForeground = true)
        }

        publishAndPersistState(restoredState)
        if (restoredState.isRunning) {
            startTicker()
        } else {
            tickerJob?.cancel()
            tickerJob = null
        }
        return restoredState
    }

    private fun restorePersistedState(): TimerUiState? {
        val persistedState = WalkingTimerStateStore.load(this) ?: return null
        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        val restoredState = persistedState.toUiState(
            nowElapsedRealtime = nowElapsedRealtime,
            nowWallClockMillis = System.currentTimeMillis(),
        )

        fastPhaseDurationSeconds = restoredState.fastPhaseDurationSeconds
        slowPhaseDurationSeconds = restoredState.slowPhaseDurationSeconds
        isRunning = restoredState.isRunning
        isPaused = restoredState.isPaused
        lastObservedPhase = restoredState.currentPhase

        val canReuseStoredRealtimeBase =
            persistedState.isRunning &&
                persistedState.runStartedAtElapsedRealtime > 0L &&
                persistedState.phaseStartedAtElapsedRealtime > 0L &&
                nowElapsedRealtime >= persistedState.runStartedAtElapsedRealtime &&
                nowElapsedRealtime >= persistedState.phaseStartedAtElapsedRealtime

        if (canReuseStoredRealtimeBase) {
            currentPhase = persistedState.currentPhase
            totalElapsedBeforeRunMillis = persistedState.totalElapsedBeforeRunMillis
            phaseElapsedBeforeRunMillis = persistedState.phaseElapsedBeforeRunMillis
            runStartedAtElapsedRealtime = persistedState.runStartedAtElapsedRealtime
            phaseStartedAtElapsedRealtime = persistedState.phaseStartedAtElapsedRealtime
        } else {
            currentPhase = restoredState.currentPhase
            totalElapsedBeforeRunMillis = restoredState.elapsedSeconds.toLong() * 1_000L
            phaseElapsedBeforeRunMillis = elapsedMillisInPhase(
                phase = restoredState.currentPhase,
                remainingSeconds = restoredState.remainingSeconds,
                fastPhaseDurationSeconds = fastPhaseDurationSeconds,
                slowPhaseDurationSeconds = slowPhaseDurationSeconds,
            )
            if (restoredState.isRunning) {
                runStartedAtElapsedRealtime = nowElapsedRealtime
                phaseStartedAtElapsedRealtime = nowElapsedRealtime
            } else {
                runStartedAtElapsedRealtime = 0L
                phaseStartedAtElapsedRealtime = 0L
            }
        }

        if (!restoredState.isRunning) {
            runStartedAtElapsedRealtime = 0L
            phaseStartedAtElapsedRealtime = 0L
        }
        return restoredState
    }

    private fun restoreConfiguredPhaseDurations() {
        val persistedState = WalkingTimerStateStore.load(this) ?: return
        fastPhaseDurationSeconds = persistedState.fastPhaseDurationSeconds
        slowPhaseDurationSeconds = persistedState.slowPhaseDurationSeconds
    }

    private fun persistState(state: TimerUiState) {
        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        val nowWallClockMillis = System.currentTimeMillis()
        WalkingTimerStateStore.save(
            this,
            PersistedTimerState(
                currentPhase = currentPhase,
                totalElapsedBeforeRunMillis = totalElapsedBeforeRunMillis,
                phaseElapsedBeforeRunMillis = phaseElapsedBeforeRunMillis,
                fastPhaseDurationSeconds = fastPhaseDurationSeconds,
                slowPhaseDurationSeconds = slowPhaseDurationSeconds,
                runStartedAtElapsedRealtime = if (isRunning) runStartedAtElapsedRealtime else 0L,
                phaseStartedAtElapsedRealtime = if (isRunning) phaseStartedAtElapsedRealtime else 0L,
                persistedAtElapsedRealtime = nowElapsedRealtime,
                persistedAtWallClockMillis = nowWallClockMillis,
                isRunning = isRunning,
                isPaused = isPaused,
                notificationPhase = state.currentPhase,
                notificationRemainingSeconds = state.remainingSeconds,
                notificationElapsedSeconds = state.elapsedSeconds,
                notificationIsRunning = state.isRunning,
                notificationIsPaused = state.isPaused,
            ),
        )
    }

    private fun currentUiState(): TimerUiState {
        return calculateSnapshot(
            now = SystemClock.elapsedRealtime(),
            announceTransitions = false,
        )
    }

    private fun resetTimerProgressState() {
        currentPhase = WalkingPhase.Fast
        totalElapsedBeforeRunMillis = 0L
        phaseElapsedBeforeRunMillis = 0L
        runStartedAtElapsedRealtime = 0L
        phaseStartedAtElapsedRealtime = 0L
        isRunning = false
        isPaused = false
        lastObservedPhase = WalkingPhase.Fast
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
        const val ACTION_RESTORE = "com.goenc.dailymotiontimer.action.RESTORE"

        private const val NOTIFICATION_CHANNEL_ID = "walking_timer"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MILLIS = 1_000L
        private const val TRANSITION_VIBRATION_DURATION_MILLIS = 200L
        private const val REQUEST_CODE_PAUSE = 1
        private const val REQUEST_CODE_RESUME = 2
        private const val REQUEST_CODE_STOP = 3

        fun createIntent(context: Context, action: String): Intent {
            return Intent(context, WalkingTimerService::class.java).apply {
                this.action = action
            }
        }
    }
}
