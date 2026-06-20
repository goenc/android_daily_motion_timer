package com.goenc.dailymotiontimer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

class WalkingTimerService : Service() {
    private val stateLock = Any()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var phaseTransitionJob: Job? = null
    private var phaseAnnouncementJob: Job? = null
    private var phaseElapsedMilestoneJob: Job? = null
    private var phaseBeepJob: Job? = null
    private var countdownRefreshJob: Job? = null
    private lateinit var phaseAudioPlayer: PhaseAudioPlayer
    private lateinit var phaseOverlay: WalkingPhaseOverlay

    private var sessionStartElapsedRealtime = 0L
    private var accumulatedPauseMillis = 0L
    private var pauseStartedElapsedRealtime = 0L
    private var fastDurationMillis = durationMillisFromSeconds(DEFAULT_PHASE_DURATION_SECONDS)
    private var slowDurationMillis = durationMillisFromSeconds(DEFAULT_PHASE_DURATION_SECONDS)
    private var fastPhaseBeepIntervalSeconds = DEFAULT_FAST_BEEP_INTERVAL_SECONDS
    private var slowPhaseBeepIntervalSeconds = DEFAULT_SLOW_BEEP_INTERVAL_SECONDS
    private var fastPhaseBeepPitchPreset = BeepPitchPreset.Mid
    private var slowPhaseBeepPitchPreset = BeepPitchPreset.Mid
    private var setCount = DEFAULT_SET_COUNT
    private var startDelaySeconds = DEFAULT_START_DELAY_SECONDS
    private var startPhase = WalkingPhase.Fast
    private var announcementVolume = DEFAULT_ANNOUNCEMENT_VOLUME
    private var beepVolume = DEFAULT_BEEP_VOLUME
    private var isVibrationEnabled = true
    private var isRunning = false
    private var isPaused = false
    private var isAppVisible = true
    private var lastObservedPhaseStartNumber = UNINITIALIZED_PHASE_START_NUMBER
    private var lastAnnouncedPhaseStartNumber = UNINITIALIZED_PHASE_START_NUMBER
    @Volatile
    private var hasForegroundNotification = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        phaseAudioPlayer = PhaseAudioPlayer(applicationContext)
        phaseOverlay = WalkingPhaseOverlay(applicationContext)
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
            ACTION_SET_APP_VISIBLE -> updateAppVisibility(intent)
            ACTION_UPDATE_ANNOUNCEMENT_VOLUME -> updateAnnouncementVolume(intent)
            ACTION_UPDATE_BEEP_VOLUME -> updateBeepVolume(intent)
            ACTION_RESTORE, null -> restoreActiveSession()
            else -> currentUiState()
        }
        return if (state?.isActive == true) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelPhaseTransitionJob("service destroy")
        cancelPhaseAnnouncementJob("service destroy")
        cancelPhaseElapsedMilestoneJob("service destroy")
        cancelPhaseBeepJob("service destroy")
        cancelCountdownRefreshJob("service destroy")
        val state = currentUiState()
        if (state.isActive) {
            persistState()
        }
        releaseWakeLockIfHeld()
        phaseOverlay.hide()
        phaseAudioPlayer.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startOrResumeTimer(): TimerUiState {
        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        if (isTimerRunning()) {
            val monitoredState = currentPhaseMonitorState(nowElapsedRealtime)
            val state = monitoredState.state
            syncPhaseStartTracking(monitoredState.phaseStartNumber, markAsAnnounced = true)
            showNotification(state, promoteToForeground = !hasForegroundNotification)
            publishAndPersistState(state)
            scheduleNextPhaseTransition(nowElapsedRealtime)
            schedulePhaseElapsedMilestones(monitoredState)
            schedulePhaseBeepPlayback(monitoredState)
            return state
        }

        val isNewSession = !isTimerPaused()
        if (!isTimerPaused()) {
            restoreConfiguredPhaseDurations()
            synchronized(stateLock) {
                sessionStartElapsedRealtime = nowElapsedRealtime +
                    normalizeStartDelaySeconds(startDelaySeconds).toLong() * 1_000L
                accumulatedPauseMillis = 0L
                pauseStartedElapsedRealtime = 0L
                startPhase = WalkingPhase.Fast
                isRunning = true
                isPaused = false
            }
            resetPhaseStartTracking()
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
        val monitoredState = currentPhaseMonitorState(nowElapsedRealtime)
        val state = monitoredState.state
        showNotification(state, promoteToForeground = true)
        publishAndPersistState(state)
        val scheduledPhaseStartAnnouncement = isNewSession && !state.isPreparingStart
        if (scheduledPhaseStartAnnouncement) {
            enqueuePhaseStartAnnouncement(monitoredState, source = "new session")
        }
        syncPhaseStartTracking(monitoredState.phaseStartNumber, markAsAnnounced = true)
        scheduleNextPhaseTransition(nowElapsedRealtime)
        schedulePhaseElapsedMilestones(monitoredState)
        if (!scheduledPhaseStartAnnouncement) {
            schedulePhaseBeepPlayback(monitoredState)
        }
        scheduleCountdownRefresh()
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
        cancelPhaseElapsedMilestoneJob("pause")
        cancelPhaseBeepJob("pause")
        cancelCountdownRefreshJob("pause")
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
        cancelPhaseElapsedMilestoneJob("stop")
        cancelPhaseBeepJob("stop")
        cancelCountdownRefreshJob("stop")
        releaseWakeLockIfHeld()
        phaseAudioPlayer.stop()
        resetTimerProgressState()
        resetPhaseStartTracking()
        persistState()

        val stoppedState = currentUiState()
        hasForegroundNotification = false
        phaseOverlay.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        publishState(stoppedState)
        stopSelf()
    }

    private fun scheduleNextPhaseTransition(nowElapsedRealtime: Long = SystemClock.elapsedRealtime()) {
        cancelPhaseTransitionJob("reschedule phase transition")
        if (!isTimerRunning()) {
            return
        }

        phaseTransitionJob = serviceScope.launch {
            try {
                Log.i(TAG, "Starting phase transition monitor from ${nowElapsedRealtime}ms")
                while (true) {
                    val monitoredState =
                        handleScheduledPhaseTransition(SystemClock.elapsedRealtime()) ?: return@launch
                    val delayMillis =
                        nextMonitorDelayMillis(
                            nowElapsedRealtime = SystemClock.elapsedRealtime(),
                            nextPhaseTransitionElapsedRealtime =
                                monitoredState.nextPhaseTransitionElapsedRealtime,
                        )
                    if (delayMillis <= 0L) {
                        return@launch
                    }
                    delay(delayMillis)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Phase transition monitor failed", error)
                scheduleNextPhaseTransition()
            }
        }
    }

    private fun handleScheduledPhaseTransition(
        nowElapsedRealtime: Long = SystemClock.elapsedRealtime(),
    ): PhaseMonitorState? {
        val monitoredState = currentPhaseMonitorState(nowElapsedRealtime)
        val state = monitoredState.state
        if (!state.isRunning) {
            return null
        }

        publishState(state)
        updateNotification(state)

        val shouldAnnounce = synchronized(stateLock) {
            val previousObservedPhaseStartNumber = lastObservedPhaseStartNumber
            if (monitoredState.phaseStartNumber > previousObservedPhaseStartNumber) {
                lastObservedPhaseStartNumber = monitoredState.phaseStartNumber
            }
            if (
                monitoredState.phaseStartNumber > previousObservedPhaseStartNumber &&
                monitoredState.phaseStartNumber > lastAnnouncedPhaseStartNumber
            ) {
                lastAnnouncedPhaseStartNumber = monitoredState.phaseStartNumber
                true
            } else {
                false
            }
        }
        if (shouldAnnounce) {
            persistState()
            val enqueueElapsedRealtime = SystemClock.elapsedRealtime()
            val logEntryId =
                PhaseTransitionLogStore.recordTransition(
                    phase = state.currentPhase,
                    source = "phase transition",
                    theoreticalTransitionElapsedRealtime =
                        monitoredState.currentPhaseStartElapsedRealtime,
                    detectedElapsedRealtime = nowElapsedRealtime,
                    enqueuedElapsedRealtime = enqueueElapsedRealtime,
                )
            enqueuePhaseStartAnnouncement(
                monitoredState = monitoredState,
                source = "phase transition",
                logEntryId = logEntryId,
            )
            schedulePhaseElapsedMilestones(monitoredState)
        }
        return monitoredState
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
        updatePhaseOverlay(state)
    }

    private fun announcePhaseTransition(
        phase: WalkingPhase,
        logEntryId: Long? = null,
        onCompleted: (() -> Unit)? = null,
    ) {
        val currentAnnouncementVolume = synchronized(stateLock) { announcementVolume }
        phaseAudioPlayer.setAnnouncementVolume(currentAnnouncementVolume)
        phaseAudioPlayer.play(phase, logEntryId = logEntryId, onCompleted = onCompleted)
        if (!isVibrationEnabled()) {
            return
        }
        vibrateSafely()
    }

    private fun announcePhaseElapsedMilestone(phase: WalkingPhase, elapsedMinutes: Int) {
        val currentAnnouncementVolume = synchronized(stateLock) { announcementVolume }
        phaseAudioPlayer.setAnnouncementVolume(currentAnnouncementVolume)
        phaseAudioPlayer.playElapsedMilestone(phase, elapsedMinutes)
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
        val statusText =
            getString(
                if (state.isPaused) {
                    R.string.notification_paused
                } else {
                    R.string.notification_running
                },
            )
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("${state.currentPhase.label} ${state.formattedRemainingTime}")
            .setSubText(statusText)
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

    @SuppressLint("MissingPermission")
    private fun showNotification(state: TimerUiState, promoteToForeground: Boolean) {
        val notification = buildNotification(state)
        if (promoteToForeground || !hasForegroundNotification) {
            startForeground(NOTIFICATION_ID, notification)
            hasForegroundNotification = true
            return
        }
        if (canPostNotifications()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(state: TimerUiState) {
        if (!state.isActive || !hasForegroundNotification) {
            return
        }
        if (canPostNotifications()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun canPostNotifications(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun updatePhaseOverlay(state: TimerUiState) {
        if (state.isActive && !isAppVisible) {
            phaseOverlay.showOrUpdate(state)
        } else {
            phaseOverlay.hide()
        }
    }

    private fun restoreActiveSession(): TimerUiState? {
        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        val restoredState = restorePersistedState(nowElapsedRealtime) ?: run {
            publishCurrentState()
            stopSelf()
            return null
        }
        val monitoredState = currentPhaseMonitorState(nowElapsedRealtime)
        val state = monitoredState.state

        if (state.isActive) {
            showNotification(state, promoteToForeground = true)
        }

        publishAndPersistState(state)
        if (state.isActive) {
            syncPhaseStartTracking(monitoredState.phaseStartNumber, markAsAnnounced = true)
        } else {
            resetPhaseStartTracking()
        }
        if (state.isRunning) {
            acquireWakeLockIfNeeded()
            scheduleNextPhaseTransition(nowElapsedRealtime)
            schedulePhaseElapsedMilestones(monitoredState)
            schedulePhaseBeepPlayback(monitoredState)
            scheduleCountdownRefresh()
        } else {
            cancelPhaseTransitionJob("restore inactive session")
            cancelPhaseAnnouncementJob("restore inactive session")
            cancelPhaseElapsedMilestoneJob("restore inactive session")
            cancelPhaseBeepJob("restore inactive session")
            cancelCountdownRefreshJob("restore inactive session")
            releaseWakeLockIfHeld()
        }
        return state
    }

    private fun restorePersistedState(
        nowElapsedRealtime: Long = SystemClock.elapsedRealtime(),
    ): TimerUiState? {
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
            fastPhaseBeepIntervalSeconds = persistedState.fastPhaseBeepIntervalSeconds
            slowPhaseBeepIntervalSeconds = persistedState.slowPhaseBeepIntervalSeconds
            fastPhaseBeepPitchPreset = persistedState.fastPhaseBeepPitchPreset
            slowPhaseBeepPitchPreset = persistedState.slowPhaseBeepPitchPreset
            setCount = persistedState.setCount
            startDelaySeconds = persistedState.startDelaySeconds
            startPhase = persistedState.startPhase
            isRunning = persistedState.isRunning
            isPaused = persistedState.isPaused
            announcementVolume = persistedState.announcementVolume
            beepVolume = persistedState.beepVolume
            isVibrationEnabled = persistedState.isVibrationEnabled
        }
        phaseAudioPlayer.setAnnouncementVolume(persistedState.announcementVolume)
        phaseAudioPlayer.setBeepVolume(persistedState.beepVolume)

        return persistedState.toUiState(nowElapsedRealtime)
    }

    private fun restoreConfiguredPhaseDurations() {
        val persistedState = WalkingTimerStateStore.load(this) ?: return
        val restoredAnnouncementVolume = normalizeAnnouncementVolume(persistedState.announcementVolume)
        synchronized(stateLock) {
            fastDurationMillis = persistedState.fastDurationMillis
            slowDurationMillis = persistedState.slowDurationMillis
            fastPhaseBeepIntervalSeconds = persistedState.fastPhaseBeepIntervalSeconds
            slowPhaseBeepIntervalSeconds = persistedState.slowPhaseBeepIntervalSeconds
            fastPhaseBeepPitchPreset = persistedState.fastPhaseBeepPitchPreset
            slowPhaseBeepPitchPreset = persistedState.slowPhaseBeepPitchPreset
            setCount = persistedState.setCount
            startDelaySeconds = persistedState.startDelaySeconds
            announcementVolume = restoredAnnouncementVolume
            beepVolume = persistedState.beepVolume
            isVibrationEnabled = persistedState.isVibrationEnabled
        }
        phaseAudioPlayer.setAnnouncementVolume(restoredAnnouncementVolume)
        phaseAudioPlayer.setBeepVolume(persistedState.beepVolume)
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
                fastPhaseBeepIntervalSeconds = fastPhaseBeepIntervalSeconds,
                slowPhaseBeepIntervalSeconds = slowPhaseBeepIntervalSeconds,
                fastPhaseBeepPitchPreset = fastPhaseBeepPitchPreset,
                slowPhaseBeepPitchPreset = slowPhaseBeepPitchPreset,
                setCount = setCount,
                startDelaySeconds = startDelaySeconds,
                startPhase = startPhase,
                isRunning = isRunning,
                isPaused = isPaused,
                announcementVolume = announcementVolume,
                beepVolume = beepVolume,
                isVibrationEnabled = isVibrationEnabled,
            )
        }
        WalkingTimerStateStore.save(this, persistedState)
    }

    private fun currentUiState(nowElapsedRealtime: Long = SystemClock.elapsedRealtime()): TimerUiState {
        val baseState = synchronized(stateLock) { baseUiStateLocked() }
        return baseState.resolveAt(nowElapsedRealtime)
    }

    private fun currentPhaseMonitorState(
        nowElapsedRealtime: Long = SystemClock.elapsedRealtime(),
    ): PhaseMonitorState {
        val baseState = synchronized(stateLock) { baseUiStateLocked() }
        if (!baseState.isActive) {
            return PhaseMonitorState(
                state = baseState.resolveAt(nowElapsedRealtime),
                phaseStartNumber = UNINITIALIZED_PHASE_START_NUMBER,
                currentPhaseStartElapsedRealtime = 0L,
                nextPhaseTransitionElapsedRealtime = 0L,
                phaseElapsedMillis = 0L,
            )
        }
        if (baseState.sessionStartElapsedRealtime > nowElapsedRealtime) {
            val firstPhaseDurationMillis = phaseDurationMillis(
                phase = baseState.startPhase,
                fastDurationMillis = durationMillisFromSeconds(baseState.fastPhaseDurationSeconds),
                slowDurationMillis = durationMillisFromSeconds(baseState.slowPhaseDurationSeconds),
            )
            return PhaseMonitorState(
                state = baseState.resolveAt(nowElapsedRealtime),
                phaseStartNumber = UNINITIALIZED_PHASE_START_NUMBER,
                currentPhaseStartElapsedRealtime = baseState.sessionStartElapsedRealtime,
                nextPhaseTransitionElapsedRealtime =
                    baseState.sessionStartElapsedRealtime + firstPhaseDurationMillis,
                phaseElapsedMillis = 0L,
            )
        }
        val referenceElapsedRealtime = phaseReferenceElapsedRealtime(baseState, nowElapsedRealtime)
        val sessionSnapshot =
            calculateTimerSessionSnapshot(
                nowElapsedRealtime = nowElapsedRealtime,
                sessionStartElapsedRealtime = baseState.sessionStartElapsedRealtime,
                accumulatedPauseMillis = baseState.accumulatedPauseMillis,
                pauseStartedElapsedRealtime = baseState.pauseStartedElapsedRealtime,
                fastDurationMillis = durationMillisFromSeconds(baseState.fastPhaseDurationSeconds),
                slowDurationMillis = durationMillisFromSeconds(baseState.slowPhaseDurationSeconds),
                startPhase = baseState.startPhase,
                isRunning = baseState.isRunning,
                isPaused = baseState.isPaused,
            )
        return PhaseMonitorState(
            state = baseState.resolveAt(nowElapsedRealtime),
            phaseStartNumber = calculateCurrentPhaseStartNumber(baseState, nowElapsedRealtime),
            currentPhaseStartElapsedRealtime =
                (referenceElapsedRealtime - sessionSnapshot.phaseElapsedMillis).coerceAtLeast(0L),
            nextPhaseTransitionElapsedRealtime =
                referenceElapsedRealtime + sessionSnapshot.remainingPhaseMillis,
            phaseElapsedMillis = sessionSnapshot.phaseElapsedMillis,
        )
    }

    private fun baseUiStateLocked(): TimerUiState {
        return TimerUiState(
            fastPhaseDurationSeconds = durationSecondsFromMillis(fastDurationMillis),
            slowPhaseDurationSeconds = durationSecondsFromMillis(slowDurationMillis),
            fastPhaseBeepIntervalSeconds = fastPhaseBeepIntervalSeconds,
            slowPhaseBeepIntervalSeconds = slowPhaseBeepIntervalSeconds,
            fastPhaseBeepPitchPreset = fastPhaseBeepPitchPreset,
            slowPhaseBeepPitchPreset = slowPhaseBeepPitchPreset,
            setCount = setCount,
            startDelaySeconds = startDelaySeconds,
            announcementVolume = announcementVolume,
            beepVolume = beepVolume,
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

    private fun calculateCurrentPhaseStartNumber(
        baseState: TimerUiState,
        nowElapsedRealtime: Long,
    ): Long {
        if (!baseState.isActive) {
            return UNINITIALIZED_PHASE_START_NUMBER
        }
        if (baseState.sessionStartElapsedRealtime > nowElapsedRealtime) {
            return UNINITIALIZED_PHASE_START_NUMBER
        }
        val elapsedActiveMillis = calculateElapsedActiveMillis(
            nowElapsedRealtime = nowElapsedRealtime,
            sessionStartElapsedRealtime = baseState.sessionStartElapsedRealtime,
            accumulatedPauseMillis = baseState.accumulatedPauseMillis,
            pauseStartedElapsedRealtime = baseState.pauseStartedElapsedRealtime,
            isRunning = baseState.isRunning,
            isPaused = baseState.isPaused,
        )
        val fastPhaseDurationMillis = durationMillisFromSeconds(baseState.fastPhaseDurationSeconds)
        val slowPhaseDurationMillis = durationMillisFromSeconds(baseState.slowPhaseDurationSeconds)
        val firstPhaseDurationMillis = phaseDurationMillis(
            phase = baseState.startPhase,
            fastDurationMillis = fastPhaseDurationMillis,
            slowDurationMillis = slowPhaseDurationMillis,
        )
        val secondPhaseDurationMillis = phaseDurationMillis(
            phase = baseState.startPhase.next(),
            fastDurationMillis = fastPhaseDurationMillis,
            slowDurationMillis = slowPhaseDurationMillis,
        )
        val cycleDurationMillis = firstPhaseDurationMillis + secondPhaseDurationMillis
        if (cycleDurationMillis <= 0L) {
            return 0L
        }
        val completedCycles = elapsedActiveMillis / cycleDurationMillis
        val positionInCycleMillis = elapsedActiveMillis % cycleDurationMillis
        return if (positionInCycleMillis < firstPhaseDurationMillis) {
            completedCycles * 2L
        } else {
            completedCycles * 2L + 1L
        }
    }

    private fun syncPhaseStartTracking(phaseStartNumber: Long, markAsAnnounced: Boolean) {
        synchronized(stateLock) {
            lastObservedPhaseStartNumber = phaseStartNumber
            if (markAsAnnounced) {
                lastAnnouncedPhaseStartNumber = phaseStartNumber
            }
        }
    }

    private fun resetPhaseStartTracking() {
        synchronized(stateLock) {
            lastObservedPhaseStartNumber = UNINITIALIZED_PHASE_START_NUMBER
            lastAnnouncedPhaseStartNumber = UNINITIALIZED_PHASE_START_NUMBER
        }
    }

    private fun phaseReferenceElapsedRealtime(
        baseState: TimerUiState,
        nowElapsedRealtime: Long,
    ): Long {
        return when {
            baseState.isRunning -> nowElapsedRealtime
            baseState.isPaused && baseState.pauseStartedElapsedRealtime > 0L ->
                baseState.pauseStartedElapsedRealtime
            else -> nowElapsedRealtime
        }
    }

    private fun nextMonitorDelayMillis(
        nowElapsedRealtime: Long,
        nextPhaseTransitionElapsedRealtime: Long,
    ): Long {
        val remainingMillis =
            (nextPhaseTransitionElapsedRealtime - nowElapsedRealtime).coerceAtLeast(1L)
        return when {
            remainingMillis > 10_000L -> 5_000L
            remainingMillis > 5_000L -> 2_000L
            remainingMillis > 1_000L -> (remainingMillis - 1_000L).coerceAtLeast(250L)
            remainingMillis > 400L -> 200L
            remainingMillis > 200L -> 100L
            else -> remainingMillis
        }.coerceAtLeast(1L)
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
    private fun enqueuePhaseStartAnnouncement(
        monitoredState: PhaseMonitorState,
        source: String,
        logEntryId: Long? = null,
    ) {
        cancelPhaseBeepJob("defer phase beeps until announcement completes")
        phaseAnnouncementJob?.cancel()
        phaseAnnouncementJob = serviceScope.launch {
            val phase = monitoredState.state.currentPhase
            val phaseStartNumber = monitoredState.phaseStartNumber
            try {
                val announcementStartedElapsedRealtime = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "Dispatching phase announcement for ${phase.name} " +
                        "source=$source phaseStartNumber=$phaseStartNumber " +
                        "logEntryId=${logEntryId ?: "none"} at=$announcementStartedElapsedRealtime",
                )
                announcePhaseTransition(
                    phase = phase,
                    logEntryId = logEntryId,
                    onCompleted = {
                        handlePhaseStartAnnouncementCompleted(
                            phase = phase,
                            phaseStartNumber = phaseStartNumber,
                        )
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Phase announcement failed for ${phase.name} source=$source", error)
            }
        }
    }

    private fun handlePhaseStartAnnouncementCompleted(
        phase: WalkingPhase,
        phaseStartNumber: Long,
    ) {
        serviceScope.launch {
            val completedElapsedRealtime = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "Phase announcement completed callback phase=${phase.name} " +
                    "phaseStartNumber=$phaseStartNumber at=$completedElapsedRealtime",
            )
            val latestState = currentPhaseMonitorState(completedElapsedRealtime)
            if (
                !latestState.state.isRunning ||
                latestState.phaseStartNumber != phaseStartNumber ||
                latestState.state.currentPhase != phase
            ) {
                Log.i(
                    TAG,
                    "Skipping initial phase beep after announcement phase=${phase.name} " +
                        "phaseStartNumber=$phaseStartNumber latestRunning=${latestState.state.isRunning} " +
                        "latestPhase=${latestState.state.currentPhase.name} " +
                        "latestPhaseStartNumber=${latestState.phaseStartNumber}",
                )
                return@launch
            }

            Log.i(
                TAG,
                "Dispatching initial ${phase.name} phase beep after announcement " +
                    "phaseStartNumber=$phaseStartNumber at=$completedElapsedRealtime",
            )
            phaseAudioPlayer.playBeep(phaseBeepPitchPreset(phase))
            schedulePhaseBeepPlaybackAfterInitialCue(
                phase = phase,
                phaseStartNumber = phaseStartNumber,
            )
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

    @Synchronized
    private fun schedulePhaseElapsedMilestones(monitoredState: PhaseMonitorState) {
        cancelPhaseElapsedMilestoneJob("reschedule phase elapsed milestones")
        val state = monitoredState.state
        if (!state.isRunning || monitoredState.phaseStartNumber == UNINITIALIZED_PHASE_START_NUMBER) {
            return
        }

        phaseElapsedMilestoneJob = serviceScope.launch {
            val phaseStartNumber = monitoredState.phaseStartNumber
            val phase = state.currentPhase
            for (milestoneSeconds in PHASE_ELAPSED_MILESTONE_SECONDS) {
                val targetElapsedRealtime =
                    monitoredState.currentPhaseStartElapsedRealtime +
                        milestoneSeconds * 1_000L
                if (targetElapsedRealtime >= monitoredState.nextPhaseTransitionElapsedRealtime) {
                    continue
                }

                val delayMillis =
                    targetElapsedRealtime - SystemClock.elapsedRealtime()
                if (delayMillis <= 0L) {
                    continue
                }

                delay(delayMillis)
                val latestState = currentPhaseMonitorState(SystemClock.elapsedRealtime())
                if (
                    latestState.state.isRunning &&
                    latestState.phaseStartNumber == phaseStartNumber &&
                    latestState.state.currentPhase == phase
                ) {
                    val elapsedMinutes = milestoneSeconds / 60
                    Log.i(
                        TAG,
                        "Dispatching ${phase.name} elapsed milestone minutes=$elapsedMinutes",
                    )
                    announcePhaseElapsedMilestone(
                        phase = phase,
                        elapsedMinutes = elapsedMinutes,
                    )
                }
            }
        }
    }

    @Synchronized
    private fun cancelPhaseElapsedMilestoneJob(reason: String) {
        phaseElapsedMilestoneJob?.let { job ->
            Log.i(TAG, "Cancelling phase elapsed milestone job reason=$reason")
            job.cancel()
        }
        phaseElapsedMilestoneJob = null
    }

    @Synchronized
    private fun schedulePhaseBeepPlayback(monitoredState: PhaseMonitorState) {
        cancelPhaseBeepJob("reschedule phase beeps")
        val state = monitoredState.state
        if (!state.isRunning || monitoredState.phaseStartNumber == UNINITIALIZED_PHASE_START_NUMBER) {
            return
        }

        val phase = state.currentPhase
        val intervalSeconds = phaseBeepIntervalSeconds(phase)
        val intervalMillis = (intervalSeconds * 1_000f).roundToLong()
        if (intervalMillis <= 0L) {
            return
        }

        val initialDelayMillis = nextPhaseBeepDelayMillis(
            phaseElapsedMillis = monitoredState.phaseElapsedMillis,
            intervalSeconds = intervalSeconds,
        )
        Log.i(
            TAG,
            "Starting ${phase.name} phase beep schedule " +
                "phaseStartNumber=${monitoredState.phaseStartNumber} " +
                "initialDelayMillis=$initialDelayMillis intervalMillis=$intervalMillis " +
                "at=${SystemClock.elapsedRealtime()}",
        )
        phaseBeepJob = serviceScope.launch {
            val phaseStartNumber = monitoredState.phaseStartNumber
            var nextDelayMillis = initialDelayMillis
            while (true) {
                delay(nextDelayMillis)
                val latestState = currentPhaseMonitorState(SystemClock.elapsedRealtime())
                if (
                    !latestState.state.isRunning ||
                    latestState.phaseStartNumber != phaseStartNumber ||
                    latestState.state.currentPhase != phase
                ) {
                    return@launch
                }
                Log.i(
                    TAG,
                    "Dispatching ${phase.name} phase beep intervalSeconds=$intervalSeconds",
                )
                phaseAudioPlayer.playBeep(phaseBeepPitchPreset(phase))
                nextDelayMillis = intervalMillis
            }
        }
    }

    @Synchronized
    private fun schedulePhaseBeepPlaybackAfterInitialCue(
        phase: WalkingPhase,
        phaseStartNumber: Long,
    ) {
        cancelPhaseBeepJob("start phase beeps after initial post-announcement beep")
        val intervalSeconds = phaseBeepIntervalSeconds(phase)
        val intervalMillis = (intervalSeconds * 1_000f).roundToLong()
        if (intervalMillis <= 0L) {
            return
        }

        Log.i(
            TAG,
            "Starting ${phase.name} phase beep schedule after initial beep " +
                "phaseStartNumber=$phaseStartNumber intervalMillis=$intervalMillis " +
                "at=${SystemClock.elapsedRealtime()}",
        )
        phaseBeepJob = serviceScope.launch {
            var nextDelayMillis = intervalMillis
            while (true) {
                delay(nextDelayMillis)
                val latestState = currentPhaseMonitorState(SystemClock.elapsedRealtime())
                if (
                    !latestState.state.isRunning ||
                    latestState.phaseStartNumber != phaseStartNumber ||
                    latestState.state.currentPhase != phase
                ) {
                    return@launch
                }
                Log.i(
                    TAG,
                    "Dispatching ${phase.name} phase beep intervalSeconds=$intervalSeconds",
                )
                phaseAudioPlayer.playBeep(phaseBeepPitchPreset(phase))
                nextDelayMillis = intervalMillis
            }
        }
    }

    @Synchronized
    private fun cancelPhaseBeepJob(reason: String) {
        phaseBeepJob?.let { job ->
            Log.i(TAG, "Cancelling phase beep job reason=$reason")
            job.cancel()
        }
        phaseBeepJob = null
    }

    @Synchronized
    private fun scheduleCountdownRefresh() {
        cancelCountdownRefreshJob("reschedule countdown refresh")
        if (!isTimerRunning()) {
            return
        }
        countdownRefreshJob = serviceScope.launch {
            while (isTimerRunning()) {
                val state = currentUiState()
                publishState(state)
                updateNotification(state)
                delay(COUNTDOWN_REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    @Synchronized
    private fun cancelCountdownRefreshJob(reason: String) {
        countdownRefreshJob?.let { job ->
            Log.i(TAG, "Cancelling countdown refresh job reason=$reason")
            job.cancel()
        }
        countdownRefreshJob = null
    }

    private fun phaseBeepIntervalSeconds(phase: WalkingPhase): Float {
        return synchronized(stateLock) {
            if (phase == WalkingPhase.Fast) fastPhaseBeepIntervalSeconds else slowPhaseBeepIntervalSeconds
        }
    }

    private fun phaseBeepPitchPreset(phase: WalkingPhase): BeepPitchPreset {
        return synchronized(stateLock) {
            if (phase == WalkingPhase.Fast) fastPhaseBeepPitchPreset else slowPhaseBeepPitchPreset
        }
    }

    private fun nextPhaseBeepDelayMillis(phaseElapsedMillis: Long, intervalSeconds: Float): Long {
        val intervalMillis = (intervalSeconds * 1_000f).roundToLong().coerceAtLeast(0L)
        if (intervalMillis <= 0L) {
            return 0L
        }
        val remainder = phaseElapsedMillis % intervalMillis
        return if (remainder == 0L) intervalMillis else (intervalMillis - remainder).coerceAtLeast(1L)
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

    private fun updateBeepVolume(intent: Intent?): TimerUiState {
        val requestedVolume = intent?.getFloatExtra(EXTRA_BEEP_VOLUME, beepVolume)
            ?: beepVolume
        val normalizedVolume = normalizeBeepVolume(requestedVolume)
        synchronized(stateLock) {
            beepVolume = normalizedVolume
        }
        phaseAudioPlayer.setBeepVolume(normalizedVolume)

        val state = currentUiState()
        publishAndPersistState(state)
        return state
    }

    private fun updateAppVisibility(intent: Intent?): TimerUiState {
        isAppVisible = intent?.getBooleanExtra(EXTRA_APP_VISIBLE, true) ?: true
        val state = currentUiState()
        updatePhaseOverlay(state)
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
        const val ACTION_UPDATE_BEEP_VOLUME = "com.goenc.dailymotiontimer.action.UPDATE_BEEP_VOLUME"
        const val ACTION_SET_APP_VISIBLE = "com.goenc.dailymotiontimer.action.SET_APP_VISIBLE"

        private const val NOTIFICATION_CHANNEL_ID = "walking_timer"
        private const val NOTIFICATION_ID = 1001
        private const val TRANSITION_VIBRATION_DURATION_MILLIS = 200L
        private const val REQUEST_CODE_PAUSE = 1
        private const val REQUEST_CODE_RESUME = 2
        private const val REQUEST_CODE_STOP = 3
        private const val EXTRA_ANNOUNCEMENT_VOLUME = "extra_announcement_volume"
        private const val EXTRA_BEEP_VOLUME = "extra_beep_volume"
        private const val EXTRA_APP_VISIBLE = "extra_app_visible"
        private const val TAG = "WalkingTimerService"
        private const val UNINITIALIZED_PHASE_START_NUMBER = -1L
        private const val COUNTDOWN_REFRESH_INTERVAL_MILLIS = 1_000L
        private val PHASE_ELAPSED_MILESTONE_SECONDS = listOf(60, 120)

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

        fun createBeepVolumeIntent(context: Context, beepVolume: Float): Intent {
            return createIntent(context, ACTION_UPDATE_BEEP_VOLUME).apply {
                putExtra(EXTRA_BEEP_VOLUME, beepVolume)
            }
        }

        fun createAppVisibilityIntent(context: Context, isVisible: Boolean): Intent {
            return createIntent(context, ACTION_SET_APP_VISIBLE).apply {
                putExtra(EXTRA_APP_VISIBLE, isVisible)
            }
        }
    }

    private data class PhaseMonitorState(
        val state: TimerUiState,
        val phaseStartNumber: Long,
        val currentPhaseStartElapsedRealtime: Long,
        val nextPhaseTransitionElapsedRealtime: Long,
        val phaseElapsedMillis: Long,
    )
}
