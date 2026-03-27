package com.goenc.dailymotiontimer

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class WalkingPhase(val label: String, val announcement: String) {
    Slow(label = "ゆっくり歩く", announcement = "ゆっくり歩いてください"),
    Fast(label = "早く歩く", announcement = "早く歩いてください");

    fun next(): WalkingPhase = if (this == Slow) Fast else Slow
}

private const val PHASE_DURATION_SECONDS = 3 * 60
private const val TICK_INTERVAL_MILLIS = 1_000L

data class TimerUiState(
    val currentPhase: WalkingPhase = WalkingPhase.Slow,
    val remainingSeconds: Int = PHASE_DURATION_SECONDS,
    val isRunning: Boolean = false,
) {
    val formattedRemainingTime: String
        get() {
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
}

class TimerViewModel : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _uiState = MutableStateFlow(TimerUiState())
    val uiState: StateFlow<TimerUiState> = _uiState.asStateFlow()

    private val _speechEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val speechEvents: SharedFlow<String> = _speechEvents.asSharedFlow()

    private var timerJob: Job? = null

    fun startOrResume() {
        if (_uiState.value.isRunning) {
            return
        }
        _uiState.value = _uiState.value.copy(isRunning = true)
        startTicker()
    }

    fun pause() {
        timerJob?.cancel()
        timerJob = null
        _uiState.value = _uiState.value.copy(isRunning = false)
    }

    fun stop() {
        timerJob?.cancel()
        timerJob = null
        _uiState.value = TimerUiState()
    }

    private fun startTicker() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MILLIS)
                val state = _uiState.value
                if (!state.isRunning) {
                    continue
                }
                if (state.remainingSeconds > 1) {
                    _uiState.value = state.copy(remainingSeconds = state.remainingSeconds - 1)
                    continue
                }

                val nextPhase = state.currentPhase.next()
                _uiState.value = TimerUiState(
                    currentPhase = nextPhase,
                    remainingSeconds = PHASE_DURATION_SECONDS,
                    isRunning = true,
                )
                _speechEvents.tryEmit(nextPhase.announcement)
            }
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        scope.cancel()
        super.onCleared()
    }
}
