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
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WalkingTimerService : Service() {
    private val stateLock = Any()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var phaseTransitionJob: Job? = null
    private var phaseAnnouncementJob: Job? = null
    private lateinit var phaseAudioPlayer: PhaseAudioPlayer

    private var sessionStartElapsedRealtime = 0L
    private var accumulatedPauseMillis = 0L
    private var pauseStartedElapsedRealtime = 0L
    private var fastDurationMillis = durationMillisFromSeconds(DEFAULT_PHASE_DURATION_SECONDS)
    private var slowDurationMillis = durationMillisFromSeconds(DEFAULT_PHASE_DURATION_SECONDS)
    private var startPhase = WalkingPhase.Fast
    private var announcementVolume = DEFAULT_ANNOUNCEMENT_VOLUME
    private var isVibrationEnabled = true
    private var isRunning = false
    private var isPaused = false
    @Volatile
    private var hasForegroundNotification = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        phaseAudioPlayer = PhaseAudioPlayer(applicationContext)
        wakeLock = createWakeLock()

        val initialState = restorePersistedState() ?: TimerUiState()
        if (initialState.isRunning) {
            acquireWakeLockIfNeeded()
        }
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
            ACTION_UPDATE_ANNOUNCEMENT_VOLUME -> updateAnnouncementVolume(intent)
            ACTION_RESTORE, null -> restoreActiveSession()
            else -> currentUiState()
        }
        return if (state?.isActive == true) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelPhaseTransitionJob("service destroy")
        cancelPhaseAnnouncementJob("service destroy")
        val state = currentUiState()
        if (state.isActive) {
            persistState()
        }
        releaseWakeLockIfHeld()
        phaseAudioPlayer.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startOrResumeTimer(): TimerUiState {
        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        if (isTimerRunning()) {
            val state = currentUiState(nowElapsedRealtime)
            showNotification(state, promoteToForeground = !hasForegroundNotification)
            publishAndPersistState(state)
            scheduleNextPhaseTransition(nowElapsedRealtime)
            return state
        }

        if (!isTimerPaused()) {
            restoreConfiguredPhaseDurations()
            synchronized(stateLock) {
                sessionStartElapsedRealtime = nowElapsedRealtime
                accumulatedPauseMillis = 0L
                pauseStartedElapsedRealtime = 0L
                startPhase = WalkingPhase.Fast
                isRunning = true
                isPaused = false
            }
        } else {
            synchronized(stateLock) {
                accumulatedPauseMillis +=
                    (nowElapsedRealtime - pauseStartedElapsedRealtime).coerceAtLeast(0L)
                pauseStartedElapsedRealtime = 0L
                isRunning = true
                isPaused = false
            }
        }

        acquireWakeLockIfNeeded()
        val state = currentUiState(nowElapsedRealtime)
        showNotification(state, promoteToForeground = true)
        publishAndPersistState(state)
        scheduleNextPhaseTransition(nowElapsedRealtime)
        return state
    }

    private fun pauseTimer(): TimerUiState {
        if (!isTimerRunning()) {
            val state = currentUiState()
            if (state.isActive) {
                showNotification(state, promoteToForeground = !hasForegroundNotification)
                publishAndPersistState(state)
            } else {
                publishState(state)
            }
            return state
        }

        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        synchronized(stateLock) {
            pauseStartedElapsedRealtime = nowElapsedRealtime
            isRunning = false
            isPaused = true
        }
        cancelPhaseTransitionJob("pause")
        cancelPhaseAnnouncementJob("pause")
        releaseWakeLockIfHeld()

        val pausedState = currentUiState(nowElapsedRealtime)
        showNotification(pausedState, promoteToForeground = !hasForegroundNotification)
        publishAndPersistState(pausedState)
        return pausedState
    }

    private fun stopTimer() {
        synchronized(stateLock) {
            isRunning = false
            isPaused = false
        }
        cancelPhaseTransitionJob("stop")
        cancelPhaseAnnouncementJob("stop")
        releaseWakeLockIfHeld()
        phaseAudioPlayer.stop()
        resetTimerProgressState()
        persistState()

        val stoppedState = currentUiState()
        hasForegroundNotification = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        publishState(stoppedState)
        stopSelf()
    }

    private fun scheduleNextPhaseTransition(nowElapsedRealtime: Long = SystemClock.elapsedRealtime()) {
        cancelPhaseTransitionJob("reschedule phase transition")
        val delayMillis = synchronized(stateLock) {
            if (!isRunning) {
                null
            } else {
                calculateTimerSessionSnapshot(
                    nowElapsedRealtime = nowElapsedRealtime,
                    sessionStartElapsedRealtime = sessionStartElapsedRealtime,
                    accumulatedPauseMillis = accumulatedPauseMillis,
                    pauseStartedElapsedRealtime = pauseStartedElapsedRealtime,
                    fastDurationMillis = fastDurationMillis,
                    slowDurationMillis = slowDurationMillis,
                    startPhase = startPhase,
                    isRunning = isRunning,
                    isPaused = isPaused,
                ).remainingPhaseMillis.coerceAtLeast(1L)
            }
        } ?: return

        phaseTransitionJob = serviceScope.launch {
            try {
                Log.i(TAG, "Scheduling next phase transition in ${delayMillis}ms")
                delay(delayMillis)
                handleScheduledPhaseTransition()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Scheduled phase transition failed", error)
                scheduleNextPhaseTransition()
            }
        }
    }

    private fun handleScheduledPhaseTransition() {
        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        val state = currentUiState(nowElapsedRealtime)
        if (!state.isRunning) {
            return
        }

        publishAndPersistState(state)
        enqueuePhaseAnnouncement(state.currentPhase, source = "phase transition")
        scheduleNextPhaseTransition(nowElapsedRealtime)
    }

    private fun publishCurrentState() {
        publishState(currentUiState())
    }

    private fun publishAndPersistState(state: TimerUiState) {
        publishState(state)
        persistState()
    }

    private fun publishState(state: TimerUiState) {
        WalkingTimerController.publishState(state)
    }

    private fun announcePhaseTransition(phase: WalkingPhase) {
        val currentAnnouncementVolume = synchronized(stateLock) { announcementVolume }
        phaseAudioPlayer.setAnnouncementVolume(currentAnnouncementVolume)
        phaseAudioPlayer.play(phase)
        if (!isVibrationEnabled()) {
            return
        }
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
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                getString(
                    if (state.isPaused) {
                        R.string.notification_paused
                    } else {
                        R.string.notification_running
                    },
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
            acquireWakeLockIfNeeded()
            scheduleNextPhaseTransition()
        } else {
            cancelPhaseTransitionJob("restore inactive session")
            cancelPhaseAnnouncementJob("restore inactive session")
            releaseWakeLockIfHeld()
        }
        return restoredState
    }

    private fun restorePersistedState(): TimerUiState? {
        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        val persistedState = WalkingTimerStateStore.load(this)?.sanitized(nowElapsedRealtime) ?: return null

        synchronized(stateLock) {
            sessionStartElapsedRealtime =
                if (persistedState.isRunning || persistedState.isPaused) {
                    persistedState.sessionStartElapsedRealtime
                } else {
                    0L
                }
            accumulatedPauseMillis =
                if (persistedState.isRunning || persistedState.isPaused) {
                    persistedState.accumulatedPauseMillis
                } else {
                    0L
                }
            pauseStartedElapsedRealtime =
                if (persistedState.isPaused) persistedState.pauseStartedElapsedRealtime else 0L
            fastDurationMillis = persistedState.fastDurationMillis
            slowDurationMillis = persistedState.slowDurationMillis
            startPhase = persistedState.startPhase
            isRunning = persistedState.isRunning
            isPaused = persistedState.isPaused
            announcementVolume = persistedState.announcementVolume
            isVibrationEnabled = persistedState.isVibrationEnabled
        }
        phaseAudioPlayer.setAnnouncementVolume(persistedState.announcementVolume)

        return persistedState.toUiState(nowElapsedRealtime)
    }

    private fun restoreConfiguredPhaseDurations() {
        val persistedState = WalkingTimerStateStore.load(this) ?: return
        val restoredAnnouncementVolume = normalizeAnnouncementVolume(persistedState.announcementVolume)
        synchronized(stateLock) {
            fastDurationMillis = persistedState.fastDurationMillis
            slowDurationMillis = persistedState.slowDurationMillis
            announcementVolume = restoredAnnouncementVolume
            isVibrationEnabled = persistedState.isVibrationEnabled
        }
        phaseAudioPlayer.setAnnouncementVolume(restoredAnnouncementVolume)
    }

    private fun persistState() {
        val persistedState = synchronized(stateLock) {
            PersistedTimerState(
                sessionStartElapsedRealtime =
                    if (isRunning || isPaused) sessionStartElapsedRealtime else 0L,
                accumulatedPauseMillis = if (isRunning || isPaused) accumulatedPauseMillis else 0L,
                pauseStartedElapsedRealtime = if (isPaused) pauseStartedElapsedRealtime else 0L,
                fastDurationMillis = fastDurationMillis,
                slowDurationMillis = slowDurationMillis,
                startPhase = startPhase,
                isRunning = isRunning,
                isPaused = isPaused,
                announcementVolume = announcementVolume,
                isVibrationEnabled = isVibrationEnabled,
            )
        }
        WalkingTimerStateStore.save(this, persistedState)
    }

    private fun currentUiState(nowElapsedRealtime: Long = SystemClock.elapsedRealtime()): TimerUiState {
        val baseState = synchronized(stateLock) { baseUiStateLocked() }
        return baseState.resolveAt(nowElapsedRealtime)
    }

    private fun baseUiStateLocked(): TimerUiState {
        return TimerUiState(
            fastPhaseDurationSeconds = durationSecondsFromMillis(fastDurationMillis),
            slowPhaseDurationSeconds = durationSecondsFromMillis(slowDurationMillis),
            announcementVolume = announcementVolume,
            isVibrationEnabled = isVibrationEnabled,
            isRunning = isRunning,
            isPaused = isPaused,
            sessionStartElapsedRealtime =
                if (isRunning || isPaused) sessionStartElapsedRealtime else 0L,
            accumulatedPauseMillis = if (isRunning || isPaused) accumulatedPauseMillis else 0L,
            pauseStartedElapsedRealtime = if (isPaused) pauseStartedElapsedRealtime else 0L,
            startPhase = startPhase,
        )
    }

    private fun resetTimerProgressState() {
        synchronized(stateLock) {
            sessionStartElapsedRealtime = 0L
            accumulatedPauseMillis = 0L
            pauseStartedElapsedRealtime = 0L
            startPhase = WalkingPhase.Fast
            isRunning = false
            isPaused = false
        }
    }

    private fun isTimerRunning(): Boolean {
        return synchronized(stateLock) { isRunning }
    }

    private fun isTimerPaused(): Boolean {
        return synchronized(stateLock) { isPaused }
    }

    private fun isVibrationEnabled(): Boolean {
        return synchronized(stateLock) { isVibrationEnabled }
    }

    @Synchronized
    private fun enqueuePhaseAnnouncement(phase: WalkingPhase, source: String) {
        phaseAnnouncementJob?.cancel()
        phaseAnnouncementJob = serviceScope.launch {
            try {
                Log.i(TAG, "Dispatching phase announcement for ${phase.name} source=$source")
                announcePhaseTransition(phase)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Phase announcement failed for ${phase.name} source=$source", error)
            }
        }
    }

    @Synchronized
    private fun cancelPhaseTransitionJob(reason: String) {
        phaseTransitionJob?.let { job ->
            Log.i(TAG, "Cancelling phase transition job reason=$reason")
            job.cancel()
        }
        phaseTransitionJob = null
    }

    @Synchronized
    private fun cancelPhaseAnnouncementJob(reason: String) {
        phaseAnnouncementJob?.let { job ->
            Log.i(TAG, "Cancelling phase announcement job reason=$reason")
            job.cancel()
        }
        phaseAnnouncementJob = null
    }

    private fun createWakeLock(): PowerManager.WakeLock? {
        val powerManager = getSystemService(PowerManager::class.java) ?: run {
            Log.w(TAG, "PowerManager was unavailable, WakeLock was not created")
            return null
        }
        return powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:WalkingTimerService",
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun acquireWakeLockIfNeeded() {
        val lock = wakeLock ?: createWakeLock()?.also { wakeLock = it } ?: return
        if (lock.isHeld) {
            return
        }
        runCatching {
            lock.acquire()
            Log.i(TAG, "WakeLock acquired")
        }.onFailure { error ->
            Log.e(TAG, "Failed to acquire WakeLock", error)
        }
    }

    private fun releaseWakeLockIfHeld() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) {
            return
        }
        runCatching {
            lock.release()
            Log.i(TAG, "WakeLock released")
        }.onFailure { error ->
            Log.e(TAG, "Failed to release WakeLock", error)
        }
    }

    private fun updateAnnouncementVolume(intent: Intent?): TimerUiState {
        val requestedVolume = intent?.getFloatExtra(EXTRA_ANNOUNCEMENT_VOLUME, announcementVolume)
            ?: announcementVolume
        val normalizedVolume = normalizeAnnouncementVolume(requestedVolume)
        synchronized(stateLock) {
            announcementVolume = normalizedVolume
        }
        phaseAudioPlayer.setAnnouncementVolume(normalizedVolume)

        val state = currentUiState()
        publishAndPersistState(state)
        return state
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
        const val ACTION_UPDATE_ANNOUNCEMENT_VOLUME = "com.goenc.dailymotiontimer.action.UPDATE_ANNOUNCEMENT_VOLUME"

        private const val NOTIFICATION_CHANNEL_ID = "walking_timer"
        private const val NOTIFICATION_ID = 1001
        private const val TRANSITION_VIBRATION_DURATION_MILLIS = 200L
        private const val REQUEST_CODE_PAUSE = 1
        private const val REQUEST_CODE_RESUME = 2
        private const val REQUEST_CODE_STOP = 3
        private const val EXTRA_ANNOUNCEMENT_VOLUME = "extra_announcement_volume"
        private const val TAG = "WalkingTimerService"

        fun createIntent(context: Context, action: String): Intent {
            return Intent(context, WalkingTimerService::class.java).apply {
                this.action = action
            }
        }

        fun createAnnouncementVolumeIntent(context: Context, announcementVolume: Float): Intent {
            return createIntent(context, ACTION_UPDATE_ANNOUNCEMENT_VOLUME).apply {
                putExtra(EXTRA_ANNOUNCEMENT_VOLUME, announcementVolume)
            }
        }
    }
}
