package com.goenc.dailymotiontimer

import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object WalkingTimerController {
    private val _uiState = MutableStateFlow(TimerUiState())
    val uiState: StateFlow<TimerUiState> = _uiState.asStateFlow()

    fun startOrResume(context: Context) {
        ContextCompat.startForegroundService(
            context.applicationContext,
            WalkingTimerService.createIntent(
                context = context.applicationContext,
                action = WalkingTimerService.ACTION_START_OR_RESUME,
            ),
        )
    }

    fun restoreState(context: Context) {
        val persistedState = WalkingTimerStateStore.load(context.applicationContext)
            ?.toUiState(nowElapsedRealtime = SystemClock.elapsedRealtime())
            ?: TimerUiState()
        publishState(persistedState)

        if (!persistedState.isActive) {
            return
        }

        ContextCompat.startForegroundService(
            context.applicationContext,
            WalkingTimerService.createIntent(
                context = context.applicationContext,
                action = WalkingTimerService.ACTION_RESTORE,
            ),
        )
    }

    fun pause(context: Context) {
        context.applicationContext.startService(
            WalkingTimerService.createIntent(
                context = context.applicationContext,
                action = WalkingTimerService.ACTION_PAUSE,
            ),
        )
    }

    fun stop(context: Context) {
        context.applicationContext.startService(
            WalkingTimerService.createIntent(
                context = context.applicationContext,
                action = WalkingTimerService.ACTION_STOP,
            ),
        )
    }

    fun updateFastPhaseDurationSeconds(context: Context, durationSeconds: Int) {
        updateIdlePhaseDurations(
            context = context,
            fastPhaseDurationSeconds = durationSeconds,
            slowPhaseDurationSeconds = _uiState.value.slowPhaseDurationSeconds,
        )
    }

    fun updateSlowPhaseDurationSeconds(context: Context, durationSeconds: Int) {
        updateIdlePhaseDurations(
            context = context,
            fastPhaseDurationSeconds = _uiState.value.fastPhaseDurationSeconds,
            slowPhaseDurationSeconds = durationSeconds,
        )
    }

    fun updateFastPhaseBeepIntervalSeconds(context: Context, intervalSeconds: Int) {
        updateIdlePhaseDurations(
            context = context,
            fastPhaseDurationSeconds = _uiState.value.fastPhaseDurationSeconds,
            slowPhaseDurationSeconds = _uiState.value.slowPhaseDurationSeconds,
            fastPhaseBeepIntervalSeconds = intervalSeconds,
            slowPhaseBeepIntervalSeconds = _uiState.value.slowPhaseBeepIntervalSeconds,
        )
    }

    fun updateSlowPhaseBeepIntervalSeconds(context: Context, intervalSeconds: Int) {
        updateIdlePhaseDurations(
            context = context,
            fastPhaseDurationSeconds = _uiState.value.fastPhaseDurationSeconds,
            slowPhaseDurationSeconds = _uiState.value.slowPhaseDurationSeconds,
            fastPhaseBeepIntervalSeconds = _uiState.value.fastPhaseBeepIntervalSeconds,
            slowPhaseBeepIntervalSeconds = intervalSeconds,
        )
    }

    fun updateSetCount(context: Context, setCount: Int) {
        val currentState = _uiState.value
        if (currentState.isActive) {
            return
        }

        val updatedState = currentState.copy(setCount = normalizeSetCount(setCount))
        publishState(updatedState)
        WalkingTimerStateStore.save(
            context.applicationContext,
            updatedState.toPersistedState(),
        )
    }

    fun updateVibrationEnabled(context: Context, isEnabled: Boolean) {
        val currentState = _uiState.value
        if (currentState.isActive) {
            return
        }

        val updatedState = currentState.copy(isVibrationEnabled = isEnabled)
        publishState(updatedState)
        WalkingTimerStateStore.save(
            context.applicationContext,
            updatedState.toPersistedState(),
        )
    }

    fun updateAnnouncementVolume(context: Context, volume: Float) {
        val currentState = _uiState.value
        val normalizedVolume = normalizeAnnouncementVolume(volume)
        val updatedState = currentState.copy(announcementVolume = normalizedVolume)
        publishState(updatedState)
        WalkingTimerStateStore.save(
            context.applicationContext,
            updatedState.toPersistedState(),
        )

        if (!currentState.isActive) {
            return
        }

        context.applicationContext.startService(
            WalkingTimerService.createAnnouncementVolumeIntent(
                context = context.applicationContext,
                announcementVolume = normalizedVolume,
            ),
        )
    }

    fun setAppVisible(context: Context, isVisible: Boolean) {
        if (!_uiState.value.isActive) {
            return
        }

        context.applicationContext.startService(
            WalkingTimerService.createAppVisibilityIntent(
                context = context.applicationContext,
                isVisible = isVisible,
            ),
        )
    }

    internal fun publishState(state: TimerUiState) {
        _uiState.value = state
    }

    private fun updateIdlePhaseDurations(
        context: Context,
        fastPhaseDurationSeconds: Int,
        slowPhaseDurationSeconds: Int,
        fastPhaseBeepIntervalSeconds: Int = _uiState.value.fastPhaseBeepIntervalSeconds,
        slowPhaseBeepIntervalSeconds: Int = _uiState.value.slowPhaseBeepIntervalSeconds,
    ) {
        val currentState = _uiState.value
        if (currentState.isActive) {
            return
        }

        val updatedState = TimerUiState(
            currentPhase = WalkingPhase.Fast,
            remainingSeconds = normalizePhaseDurationSeconds(fastPhaseDurationSeconds),
            elapsedSeconds = 0,
            fastPhaseDurationSeconds = normalizePhaseDurationSeconds(fastPhaseDurationSeconds),
            slowPhaseDurationSeconds = normalizePhaseDurationSeconds(slowPhaseDurationSeconds),
            fastPhaseBeepIntervalSeconds = normalizeBeepIntervalSeconds(
                fastPhaseBeepIntervalSeconds,
                DEFAULT_FAST_BEEP_INTERVAL_SECONDS,
            ),
            slowPhaseBeepIntervalSeconds = normalizeBeepIntervalSeconds(
                slowPhaseBeepIntervalSeconds,
                DEFAULT_SLOW_BEEP_INTERVAL_SECONDS,
            ),
            setCount = currentState.setCount,
            startDelaySeconds = currentState.startDelaySeconds,
            announcementVolume = currentState.announcementVolume,
            isVibrationEnabled = currentState.isVibrationEnabled,
            isRunning = false,
            isPaused = false,
        )
        publishState(updatedState)
        WalkingTimerStateStore.save(
            context.applicationContext,
            updatedState.toPersistedState(),
        )
    }
}
