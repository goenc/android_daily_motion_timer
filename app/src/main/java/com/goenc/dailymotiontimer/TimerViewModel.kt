package com.goenc.dailymotiontimer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.goenc.dailymotiontimer.heartrate.HeartRateAlertPhaseMode
import com.goenc.dailymotiontimer.heartrate.HeartRateController
import com.goenc.dailymotiontimer.heartrate.HeartRateUiState
import kotlinx.coroutines.flow.StateFlow

class TimerViewModel(application: Application) : AndroidViewModel(application) {
    val uiState: StateFlow<TimerUiState> = WalkingTimerController.uiState
    val heartRateUiState: StateFlow<HeartRateUiState> = HeartRateController.uiState

    init {
        WalkingTimerController.restoreState(getApplication())
        HeartRateController.initialize(getApplication())
    }

    fun startOrResume() {
        WalkingTimerController.startOrResume(getApplication())
    }

    fun pause() {
        WalkingTimerController.pause(getApplication())
    }

    fun stop() {
        WalkingTimerController.stop(getApplication())
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

    fun hasHeartRatePermissions(): Boolean = HeartRateController.hasBluetoothPermissions(getApplication())

    fun heartRatePermissions(): Array<String> = HeartRateController.requiredPermissions()

    fun reportHeartRatePermissionDenied() {
        HeartRateController.reportPermissionDenied()
    }
}
