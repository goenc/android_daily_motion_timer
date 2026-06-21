package com.goenc.dailymotiontimer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import android.os.SystemClock
import com.goenc.dailymotiontimer.heartrate.HeartRateAlertPhaseMode
import com.goenc.dailymotiontimer.heartrate.HeartRateController
import com.goenc.dailymotiontimer.heartrate.HeartRateGraphMode
import com.goenc.dailymotiontimer.heartrate.HeartRateUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TimerViewModel(application: Application) : AndroidViewModel(application) {
    val uiState: StateFlow<TimerUiState> = WalkingTimerController.uiState
    val heartRateUiState: StateFlow<HeartRateUiState> = HeartRateController.uiState
    private val _normalTimerUiState = MutableStateFlow(NormalTimerUiState())
    val normalTimerUiState: StateFlow<NormalTimerUiState> = _normalTimerUiState.asStateFlow()

    init {
        WalkingTimerController.restoreState(getApplication())
        HeartRateController.initialize(getApplication())
    }

    fun startOrResume() {
        stopNormalTimer()
        WalkingTimerController.startOrResume(getApplication())
    }

    fun pause() {
        WalkingTimerController.pause(getApplication())
    }

    fun stop() {
        WalkingTimerController.stop(getApplication())
    }

    fun startOrResumeNormalTimer() {
        if (uiState.value.isActive) {
            WalkingTimerController.stop(getApplication())
        }
        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        val currentState = _normalTimerUiState.value.resolveAt(nowElapsedRealtime)
        val updatedState = when {
            currentState.isRunning -> currentState
            currentState.isPaused -> currentState.copy(
                isRunning = true,
                isPaused = false,
                accumulatedPauseMillis = currentState.accumulatedPauseMillis +
                    (nowElapsedRealtime - currentState.pauseStartedElapsedRealtime).coerceAtLeast(0L),
                pauseStartedElapsedRealtime = 0L,
            )

            else -> currentState.copy(
                isRunning = true,
                isPaused = false,
                sessionStartElapsedRealtime = nowElapsedRealtime,
                accumulatedPauseMillis = 0L,
                pauseStartedElapsedRealtime = 0L,
                elapsedSeconds = 0,
            )
        }
        _normalTimerUiState.value = updatedState.resolveAt(nowElapsedRealtime)
        HeartRateController.syncNormalTimerState(_normalTimerUiState.value)
    }

    fun pauseNormalTimer() {
        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        val currentState = _normalTimerUiState.value.resolveAt(nowElapsedRealtime)
        if (!currentState.isRunning) {
            return
        }
        _normalTimerUiState.value = currentState.copy(
            isRunning = false,
            isPaused = true,
            pauseStartedElapsedRealtime = nowElapsedRealtime,
        )
        HeartRateController.syncNormalTimerState(_normalTimerUiState.value)
    }

    fun stopNormalTimer() {
        _normalTimerUiState.value = NormalTimerUiState()
        HeartRateController.syncNormalTimerState(_normalTimerUiState.value)
    }

    fun updateFastPhaseDurationSeconds(durationSeconds: Int) {
        WalkingTimerController.updateFastPhaseDurationSeconds(getApplication(), durationSeconds)
    }

    fun updateSlowPhaseDurationSeconds(durationSeconds: Int) {
        WalkingTimerController.updateSlowPhaseDurationSeconds(getApplication(), durationSeconds)
    }

    fun updateFastPhaseBeepIntervalSeconds(intervalSeconds: Float) {
        WalkingTimerController.updateFastPhaseBeepIntervalSeconds(getApplication(), intervalSeconds)
    }

    fun updateSlowPhaseBeepIntervalSeconds(intervalSeconds: Float) {
        WalkingTimerController.updateSlowPhaseBeepIntervalSeconds(getApplication(), intervalSeconds)
    }

    fun updateFastPhaseBeepPitchPreset(preset: BeepPitchPreset) {
        WalkingTimerController.updateFastPhaseBeepPitchPreset(getApplication(), preset)
    }

    fun updateSlowPhaseBeepPitchPreset(preset: BeepPitchPreset) {
        WalkingTimerController.updateSlowPhaseBeepPitchPreset(getApplication(), preset)
    }

    fun updateSetCount(setCount: Int) {
        WalkingTimerController.updateSetCount(getApplication(), setCount)
    }

    fun updateVibrationEnabled(isEnabled: Boolean) {
        WalkingTimerController.updateVibrationEnabled(getApplication(), isEnabled)
    }

    fun updateAnnouncementVolume(volume: Float) {
        WalkingTimerController.updateAnnouncementVolume(getApplication(), volume)
    }

    fun updateBeepVolume(volume: Float) {
        WalkingTimerController.updateBeepVolume(getApplication(), volume)
    }

    fun setAppVisible(isVisible: Boolean) {
        WalkingTimerController.setAppVisible(getApplication(), isVisible)
    }

    fun connectSavedHeartRateDevice() {
        HeartRateController.connectSavedDevice(getApplication())
    }

    fun startHeartRateScan() {
        HeartRateController.startScan(getApplication())
    }

    fun stopHeartRateScan() {
        HeartRateController.stopScan()
    }

    fun connectHeartRateDevice(address: String) {
        HeartRateController.selectDevice(getApplication(), address)
    }

    fun disconnectHeartRateDevice() {
        HeartRateController.disconnect(getApplication())
    }

    fun forgetHeartRateDevice() {
        HeartRateController.forgetDevice(getApplication())
    }

    fun updateHeartRateSettings(
        targetLowerBpm: Int,
        targetUpperBpm: Int,
        dangerThresholdBpm: Int,
        alertsEnabled: Boolean,
        confirmSeconds: Int,
        alertPhaseMode: HeartRateAlertPhaseMode,
    ) {
        HeartRateController.updateSettings(
            getApplication(),
            targetLowerBpm,
            targetUpperBpm,
            dangerThresholdBpm,
            alertsEnabled,
            confirmSeconds,
            alertPhaseMode,
        )
    }

    fun updateHeartRateAlertVolume(volume: Float) {
        HeartRateController.updateAlertVolume(getApplication(), volume)
    }

    fun hasHeartRatePermissions(): Boolean = HeartRateController.hasBluetoothPermissions(getApplication())

    fun heartRatePermissions(): Array<String> = HeartRateController.requiredPermissions()

    fun reportHeartRatePermissionDenied() {
        HeartRateController.reportPermissionDenied()
    }

    fun setHeartRateGraphMode(mode: HeartRateGraphMode) {
        HeartRateController.setGraphMode(mode)
    }
}
