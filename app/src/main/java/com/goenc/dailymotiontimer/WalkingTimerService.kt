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
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WalkingTimerService : Service() {
    private data class PreciseTimerSnapshot(
        val uiState: TimerUiState,
        val totalElapsedMillis: Long,
        val phaseElapsedMillis: Long,
    )

    private val stateLock = Any()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tickerDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "WalkingTimerTicker").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()
    private val tickerScope = CoroutineScope(SupervisorJob() + tickerDispatcher)

    private var tickerJob: Job? = null
    private var phaseAnnouncementJob: Job? = null
    private lateinit var phaseAudioPlayer: PhaseAudioPlayer

    private var currentPhase = WalkingPhase.Fast
    private var totalElapsedBeforeRunMillis = 0L
    private var phaseElapsedBeforeRunMillis = 0L
    private var fastPhaseDurationSeconds = DEFAULT_PHASE_DURATION_SECONDS
    private var slowPhaseDurationSeconds = DEFAULT_PHASE_DURATION_SECONDS
    private var isVibrationEnabled = true
    private var runStartedAtElapsedRealtime = 0L
    private var phaseStartedAtElapsedRealtime = 0L
    private var isRunning = false
    private var isPaused = false
    @Volatile
    private var hasForegroundNotification = false
    private var lastObservedPhase = WalkingPhase.Fast
    private var pendingRestoredAnnouncementPhase: WalkingPhase? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationTotalElapsedMillis = 0L
    private var notificationPhaseElapsedMillis = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        phaseAudioPlayer = PhaseAudioPlayer(applicationContext)
        wakeLock = createWakeLock()
        val initialState = restorePersistedState() ?: TimerUiState()
        if (initialState.isRunning) {
            acquireWakeLockIfNeeded()
        }
        setLastObservedPhase(initialState.currentPhase)
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
        cancelTickerJob("service destroy")
        cancelPhaseAnnouncementJob("service destroy")
        val state = currentUiState()
        if (state.isActive) {
            persistState(state)
        }
        releaseWakeLockIfHeld()
        phaseAudioPlayer.release()
        tickerScope.cancel()
        serviceScope.cancel()
        tickerDispatcher.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startOrResumeTimer(): TimerUiState {
        if (isTimerRunning()) {
            val state = currentUiState()
            setLastObservedPhase(state.currentPhase)
            showNotification(state, promoteToForeground = !hasForegroundNotification)
            publishAndPersistState(state)
            return state
        }

        if (!isTimerPaused()) {
            restoreConfiguredPhaseDurations()
            resetTimerProgressState()
        }

        val now = SystemClock.elapsedRealtime()
        synchronized(stateLock) {
            runStartedAtElapsedRealtime = now
            phaseStartedAtElapsedRealtime = now
            isRunning = true
            isPaused = false
        }
        acquireWakeLockIfNeeded()

        val state = calculateSnapshot(now)
        setLastObservedPhase(state.currentPhase)
        showNotification(state, promoteToForeground = true)
        publishAndPersistState(state)
        startTicker()
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

        val now = SystemClock.elapsedRealtime()
        val snapshot = calculatePreciseSnapshot(now)
        val state = snapshot.uiState
        val transitionedPhase = observePhaseTransition(state.currentPhase)
        synchronized(stateLock) {
            totalElapsedBeforeRunMillis = snapshot.totalElapsedMillis
            phaseElapsedBeforeRunMillis = snapshot.phaseElapsedMillis
            currentPhase = state.currentPhase
            isRunning = false
            isPaused = true
            runStartedAtElapsedRealtime = 0L
            phaseStartedAtElapsedRealtime = 0L
            rememberNotificationSnapshotLocked(snapshot)
        }
        cancelTickerJob("pause")
        cancelPhaseAnnouncementJob("pause")
        releaseWakeLockIfHeld()
        val pausedState = state.copy(isRunning = false, isPaused = true)
        setLastObservedPhase(pausedState.currentPhase)
        transitionedPhase?.let { phase ->
            enqueuePhaseAnnouncement(phase, source = "pause")
        }
        showNotification(pausedState, promoteToForeground = !hasForegroundNotification)
        publishAndPersistState(pausedState)
        return pausedState
    }

    private fun stopTimer() {
        synchronized(stateLock) {
            isRunning = false
            isPaused = false
            pendingRestoredAnnouncementPhase = null
        }
        cancelTickerJob("stop")
        cancelPhaseAnnouncementJob("stop")
        releaseWakeLockIfHeld()
        phaseAudioPlayer.stop()
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
        cancelTickerJob("restart ticker")
        tickerJob = tickerScope.launch {
            Log.i(TAG, "Ticker started on ${Thread.currentThread().name}")
            while (isActive) {
                try {
                    if (!isTimerRunning()) {
                        Log.i(TAG, "Ticker stopped because timer is not running")
                        break
                    }

                    val state = try {
                        calculateSnapshot(SystemClock.elapsedRealtime())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.e(TAG, "Ticker calculateSnapshot failed", error)
                        delay(TICK_INTERVAL_MILLIS)
                        continue
                    }

                    if (!isTimerRunning()) {
                        Log.i(TAG, "Ticker skipped publish because timer stopped during snapshot calculation")
                        break
                    }

                    val transitionedPhase = try {
                        observePhaseTransition(state.currentPhase)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.e(TAG, "Ticker phase transition detection failed", error)
                        null
                    }

                    try {
                        publishAndPersistState(state)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.e(TAG, "Ticker publishAndPersistState failed", error)
                    }

                    try {
                        updateNotification(state)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.e(TAG, "Ticker updateNotification failed", error)
                    }

                    if (transitionedPhase != null) {
                        try {
                            enqueuePhaseAnnouncement(transitionedPhase, source = "ticker")
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Log.e(
                                TAG,
                                "Ticker enqueuePhaseAnnouncement failed for ${transitionedPhase.name}",
                                error,
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "Ticker iteration failed unexpectedly and will continue", error)
                }

                delay(TICK_INTERVAL_MILLIS)
            }
            Log.i(TAG, "Ticker loop finished")
        }
    }

    private fun calculateSnapshot(now: Long): TimerUiState {
        val snapshot = calculatePreciseSnapshot(now)
        rememberNotificationSnapshot(snapshot)
        return snapshot.uiState
    }

    private fun rememberNotificationSnapshot(snapshot: PreciseTimerSnapshot) {
        synchronized(stateLock) {
            rememberNotificationSnapshotLocked(snapshot)
        }
    }

    private fun calculatePreciseSnapshot(now: Long): PreciseTimerSnapshot {
        return synchronized(stateLock) {
            if (!isRunning) {
                return@synchronized PreciseTimerSnapshot(
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
                        isVibrationEnabled = isVibrationEnabled,
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

            PreciseTimerSnapshot(
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
                    isVibrationEnabled = isVibrationEnabled,
                    isRunning = true,
                    isPaused = false,
                ),
                totalElapsedMillis = totalElapsedMillis,
                phaseElapsedMillis = phaseProgress.phaseElapsedMillis,
            )
        }
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

    private fun announcePhaseTransition(phase: WalkingPhase) {
        phaseAudioPlayer.play(phase)
        if (!isVibrationEnabled()) {
            return
        }
        vibrateSafely()
    }

    private fun announcePendingRestoredPhaseTransition() {
        val phase = synchronized(stateLock) {
            pendingRestoredAnnouncementPhase.also {
                pendingRestoredAnnouncementPhase = null
            }
        } ?: return
        Log.i(TAG, "Queueing restored phase transition for ${phase.name}")
        enqueuePhaseAnnouncement(phase, source = "restore")
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
            announcePendingRestoredPhaseTransition()
        } else {
            cancelTickerJob("restore inactive session")
            cancelPhaseAnnouncementJob("restore inactive session")
        }
        return restoredState
    }

    private fun restorePersistedState(): TimerUiState? {
        val persistedState = WalkingTimerStateStore.load(this) ?: return null
        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        val provisionalRestoredState = persistedState.toUiState(
            nowElapsedRealtime = nowElapsedRealtime,
            nowWallClockMillis = System.currentTimeMillis(),
        )

        synchronized(stateLock) {
            fastPhaseDurationSeconds = provisionalRestoredState.fastPhaseDurationSeconds
            slowPhaseDurationSeconds = provisionalRestoredState.slowPhaseDurationSeconds
            isVibrationEnabled = provisionalRestoredState.isVibrationEnabled
            isRunning = provisionalRestoredState.isRunning
            isPaused = provisionalRestoredState.isPaused

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
                currentPhase = provisionalRestoredState.currentPhase
                totalElapsedBeforeRunMillis = persistedState.notificationTotalElapsedMillis()
                phaseElapsedBeforeRunMillis = persistedState.notificationPhaseElapsedMillis(
                    fastPhaseDurationSeconds = fastPhaseDurationSeconds,
                    slowPhaseDurationSeconds = slowPhaseDurationSeconds,
                )
                if (provisionalRestoredState.isRunning) {
                    runStartedAtElapsedRealtime = nowElapsedRealtime
                    phaseStartedAtElapsedRealtime = nowElapsedRealtime
                } else {
                    runStartedAtElapsedRealtime = 0L
                    phaseStartedAtElapsedRealtime = 0L
                }
            }

            if (!provisionalRestoredState.isRunning) {
                runStartedAtElapsedRealtime = 0L
                phaseStartedAtElapsedRealtime = 0L
            }
        }

        val restoredState = calculateSnapshot(nowElapsedRealtime)
        synchronized(stateLock) {
            pendingRestoredAnnouncementPhase =
                if (restoredState.isRunning && persistedState.notificationPhase != restoredState.currentPhase) {
                    restoredState.currentPhase
                } else {
                    null
                }
            lastObservedPhase = restoredState.currentPhase
        }
        return restoredState
    }

    private fun restoreConfiguredPhaseDurations() {
        val persistedState = WalkingTimerStateStore.load(this) ?: return
        synchronized(stateLock) {
            fastPhaseDurationSeconds = persistedState.fastPhaseDurationSeconds
            slowPhaseDurationSeconds = persistedState.slowPhaseDurationSeconds
            isVibrationEnabled = persistedState.isVibrationEnabled
        }
    }

    private fun persistState(state: TimerUiState) {
        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        val nowWallClockMillis = System.currentTimeMillis()
        val persistedState = synchronized(stateLock) {
            PersistedTimerState(
                currentPhase = currentPhase,
                totalElapsedBeforeRunMillis = totalElapsedBeforeRunMillis,
                phaseElapsedBeforeRunMillis = phaseElapsedBeforeRunMillis,
                fastPhaseDurationSeconds = fastPhaseDurationSeconds,
                slowPhaseDurationSeconds = slowPhaseDurationSeconds,
                isVibrationEnabled = isVibrationEnabled,
                runStartedAtElapsedRealtime = if (isRunning) runStartedAtElapsedRealtime else 0L,
                phaseStartedAtElapsedRealtime = if (isRunning) phaseStartedAtElapsedRealtime else 0L,
                persistedAtElapsedRealtime = nowElapsedRealtime,
                persistedAtWallClockMillis = nowWallClockMillis,
                isRunning = isRunning,
                isPaused = isPaused,
                notificationPhase = state.currentPhase,
                notificationRemainingSeconds = state.remainingSeconds,
                notificationPhaseElapsedMillis = notificationPhaseElapsedMillis,
                notificationElapsedSeconds = state.elapsedSeconds,
                notificationTotalElapsedMillis = notificationTotalElapsedMillis,
                notificationIsRunning = state.isRunning,
                notificationIsPaused = state.isPaused,
            )
        }
        WalkingTimerStateStore.save(this, persistedState)
    }

    private fun currentUiState(): TimerUiState {
        return calculateSnapshot(now = SystemClock.elapsedRealtime())
    }

    private fun resetTimerProgressState() {
        val restoredVibrationEnabled = WalkingTimerStateStore.load(this)?.isVibrationEnabled ?: true
        synchronized(stateLock) {
            currentPhase = WalkingPhase.Fast
            totalElapsedBeforeRunMillis = 0L
            phaseElapsedBeforeRunMillis = 0L
            runStartedAtElapsedRealtime = 0L
            phaseStartedAtElapsedRealtime = 0L
            isVibrationEnabled = restoredVibrationEnabled
            isRunning = false
            isPaused = false
            notificationTotalElapsedMillis = 0L
            notificationPhaseElapsedMillis = 0L
            pendingRestoredAnnouncementPhase = null
            lastObservedPhase = WalkingPhase.Fast
        }
    }

    private fun rememberNotificationSnapshotLocked(snapshot: PreciseTimerSnapshot) {
        notificationTotalElapsedMillis = snapshot.totalElapsedMillis
        notificationPhaseElapsedMillis = snapshot.phaseElapsedMillis
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

    private fun setLastObservedPhase(phase: WalkingPhase) {
        synchronized(stateLock) {
            lastObservedPhase = phase
        }
    }

    private fun observePhaseTransition(currentPhase: WalkingPhase): WalkingPhase? {
        return synchronized(stateLock) {
            if (currentPhase == lastObservedPhase) {
                null
            } else {
                lastObservedPhase = currentPhase
                currentPhase
            }
        }
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
    private fun cancelTickerJob(reason: String) {
        tickerJob?.let { job ->
            Log.i(TAG, "Cancelling ticker job reason=$reason")
            job.cancel()
        }
        tickerJob = null
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
        private const val TAG = "WalkingTimerService"

        fun createIntent(context: Context, action: String): Intent {
            return Intent(context, WalkingTimerService::class.java).apply {
                this.action = action
            }
        }
    }
}
