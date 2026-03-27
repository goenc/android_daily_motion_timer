package com.goenc.dailymotiontimer

import java.util.Locale

enum class WalkingPhase(val label: String, val announcement: String) {
    Slow(label = "ゆっくり歩く", announcement = "ゆっくり歩いてください"),
    Fast(label = "早く歩く", announcement = "早く歩いてください");

    fun next(): WalkingPhase = if (this == Slow) Fast else Slow
}

const val PHASE_DURATION_SECONDS = 3 * 60

data class TimerUiState(
    val currentPhase: WalkingPhase = WalkingPhase.Fast,
    val remainingSeconds: Int = PHASE_DURATION_SECONDS,
    val elapsedSeconds: Int = 0,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
) {
    val formattedRemainingTime: String
        get() = formatRemainingDuration(remainingSeconds)

    val formattedElapsedTime: String
        get() = formatElapsedDuration(elapsedSeconds)

    val isActive: Boolean
        get() = isRunning || isPaused
}

data class PersistedTimerState(
    val currentPhase: WalkingPhase,
    val totalElapsedBeforeRunSeconds: Int,
    val phaseElapsedBeforeRunSeconds: Int,
    val runStartedAtElapsedRealtime: Long,
    val phaseStartedAtElapsedRealtime: Long,
    val persistedAtElapsedRealtime: Long,
    val persistedAtWallClockMillis: Long,
    val isRunning: Boolean,
    val isPaused: Boolean,
    val notificationPhase: WalkingPhase,
    val notificationRemainingSeconds: Int,
    val notificationElapsedSeconds: Int,
    val notificationIsRunning: Boolean,
    val notificationIsPaused: Boolean,
) {
    fun toUiState(
        nowElapsedRealtime: Long,
        nowWallClockMillis: Long,
    ): TimerUiState {
        if (!isRunning) {
            return TimerUiState(
                currentPhase = currentPhase,
                remainingSeconds = PHASE_DURATION_SECONDS - phaseElapsedBeforeRunSeconds,
                elapsedSeconds = totalElapsedBeforeRunSeconds,
                isRunning = false,
                isPaused = isPaused,
            )
        }

        val deltaSeconds = (resolveElapsedDeltaMillis(
            nowElapsedRealtime = nowElapsedRealtime,
            nowWallClockMillis = nowWallClockMillis,
        ) / 1_000L).toInt()
        val totalElapsedSeconds = totalElapsedBeforeRunSeconds + deltaSeconds
        var phaseElapsedSeconds = phaseElapsedBeforeRunSeconds + deltaSeconds
        var restoredPhase = currentPhase

        while (phaseElapsedSeconds >= PHASE_DURATION_SECONDS) {
            phaseElapsedSeconds -= PHASE_DURATION_SECONDS
            restoredPhase = restoredPhase.next()
        }

        return TimerUiState(
            currentPhase = restoredPhase,
            remainingSeconds = PHASE_DURATION_SECONDS - phaseElapsedSeconds,
            elapsedSeconds = totalElapsedSeconds,
            isRunning = true,
            isPaused = false,
        )
    }

    fun notificationUiState(): TimerUiState {
        return TimerUiState(
            currentPhase = notificationPhase,
            remainingSeconds = notificationRemainingSeconds,
            elapsedSeconds = notificationElapsedSeconds,
            isRunning = notificationIsRunning,
            isPaused = notificationIsPaused,
        )
    }

    private fun resolveElapsedDeltaMillis(
        nowElapsedRealtime: Long,
        nowWallClockMillis: Long,
    ): Long {
        if (persistedAtElapsedRealtime > 0L) {
            val elapsedDelta = nowElapsedRealtime - persistedAtElapsedRealtime
            if (elapsedDelta >= 0L) {
                return elapsedDelta
            }
        }

        val wallClockDelta = nowWallClockMillis - persistedAtWallClockMillis
        return wallClockDelta.coerceAtLeast(0L)
    }
}

internal fun formatRemainingDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

internal fun formatElapsedDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
