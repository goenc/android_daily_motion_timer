package com.goenc.dailymotiontimer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

class TimerViewModel(application: Application) : AndroidViewModel(application) {
    val uiState: StateFlow<TimerUiState> = WalkingTimerController.uiState

    init {
        WalkingTimerController.restoreState(getApplication())
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

    fun updateFastPhaseBeepIntervalSeconds(intervalSeconds: Int) {
        WalkingTimerController.updateFastPhaseBeepIntervalSeconds(getApplication(), intervalSeconds)
    }

    fun updateSlowPhaseBeepIntervalSeconds(intervalSeconds: Int) {
        WalkingTimerController.updateSlowPhaseBeepIntervalSeconds(getApplication(), intervalSeconds)
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
}
