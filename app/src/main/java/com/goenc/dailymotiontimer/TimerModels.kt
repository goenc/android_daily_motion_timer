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
) {
    val formattedRemainingTime: String
        get() = formatRemainingDuration(remainingSeconds)

    val formattedElapsedTime: String
        get() = formatElapsedDuration(elapsedSeconds)
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
