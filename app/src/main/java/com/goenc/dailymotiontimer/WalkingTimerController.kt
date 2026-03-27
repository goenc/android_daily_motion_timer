package com.goenc.dailymotiontimer

import android.content.Context
import android.content.Intent
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

    internal fun publishState(state: TimerUiState) {
        _uiState.value = state
    }
}
