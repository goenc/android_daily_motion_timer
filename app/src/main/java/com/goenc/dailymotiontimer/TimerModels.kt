package com.goenc.dailymotiontimer

import java.util.Locale

enum class WalkingPhase(val label: String, val announcement: String) {
    Slow(label = "ゆっくり歩く", announcement = "ゆっくり歩いてください"),
    Fast(label = "早く歩く", announcement = "早く歩いてください");

    fun next(): WalkingPhase = if (this == Slow) Fast else Slow
}

val PHASE_DURATION_OPTIONS_SECONDS = listOf(30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330)
const val DEFAULT_PHASE_DURATION_SECONDS = 180

data class TimerUiState(
    val currentPhase: WalkingPhase = WalkingPhase.Fast,
    val remainingSeconds: Int = DEFAULT_PHASE_DURATION_SECONDS,
    val elapsedSeconds: Int = 0,
    val fastPhaseDurationSeconds: Int = DEFAULT_PHASE_DURATION_SECONDS,
    val slowPhaseDurationSeconds: Int = DEFAULT_PHASE_DURATION_SECONDS,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
) {
    val formattedRemainingTime: String
        get() = formatRemainingDuration(remainingSeconds)

    val formattedElapsedTime: String
        get() = formatElapsedDuration(elapsedSeconds)

    val formattedFastPhaseDuration: String
        get() = formatPhaseDuration(fastPhaseDurationSeconds)

    val formattedSlowPhaseDuration: String
        get() = formatPhaseDuration(slowPhaseDurationSeconds)

    val isActive: Boolean
        get() = isRunning || isPaused
}

data class PersistedTimerState(
    val currentPhase: WalkingPhase,
    val totalElapsedBeforeRunMillis: Long,
    val phaseElapsedBeforeRunMillis: Long,
    val fastPhaseDurationSeconds: Int,
    val slowPhaseDurationSeconds: Int,
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
        val fastDuration = normalizePhaseDurationSeconds(fastPhaseDurationSeconds)
        val slowDuration = normalizePhaseDurationSeconds(slowPhaseDurationSeconds)
        if (!isRunning) {
            return TimerUiState(
                currentPhase = currentPhase,
                remainingSeconds = remainingSecondsForPhaseMillis(
                    phase = currentPhase,
                    phaseElapsedMillis = phaseElapsedBeforeRunMillis,
                    fastPhaseDurationSeconds = fastDuration,
                    slowPhaseDurationSeconds = slowDuration,
                ),
                elapsedSeconds = elapsedSecondsFromMillis(totalElapsedBeforeRunMillis),
                fastPhaseDurationSeconds = fastDuration,
                slowPhaseDurationSeconds = slowDuration,
                isRunning = false,
                isPaused = isPaused,
            )
        }

        val canUseStoredRealtimeBase =
            runStartedAtElapsedRealtime > 0L &&
                phaseStartedAtElapsedRealtime > 0L &&
                nowElapsedRealtime >= runStartedAtElapsedRealtime &&
                nowElapsedRealtime >= phaseStartedAtElapsedRealtime

        return if (canUseStoredRealtimeBase) {
            val totalElapsedMillis =
                totalElapsedBeforeRunMillis + (nowElapsedRealtime - runStartedAtElapsedRealtime)
            val phaseProgress = advancePhaseProgressMillis(
                startingPhase = currentPhase,
                startingPhaseElapsedMillis = phaseElapsedBeforeRunMillis,
                additionalElapsedMillis = nowElapsedRealtime - phaseStartedAtElapsedRealtime,
                fastPhaseDurationSeconds = fastDuration,
                slowPhaseDurationSeconds = slowDuration,
            )
            TimerUiState(
                currentPhase = phaseProgress.currentPhase,
                remainingSeconds = remainingSecondsForPhaseMillis(
                    phase = phaseProgress.currentPhase,
                    phaseElapsedMillis = phaseProgress.phaseElapsedMillis,
                    fastPhaseDurationSeconds = fastDuration,
                    slowPhaseDurationSeconds = slowDuration,
                ),
                elapsedSeconds = elapsedSecondsFromMillis(totalElapsedMillis),
                fastPhaseDurationSeconds = fastDuration,
                slowPhaseDurationSeconds = slowDuration,
                isRunning = true,
                isPaused = false,
            )
        } else {
            val deltaMillis = resolveElapsedDeltaMillis(
                nowElapsedRealtime = nowElapsedRealtime,
                nowWallClockMillis = nowWallClockMillis,
            )
            val notificationPhaseElapsedMillis = elapsedMillisInPhase(
                phase = notificationPhase,
                remainingSeconds = notificationRemainingSeconds,
                fastPhaseDurationSeconds = fastDuration,
                slowPhaseDurationSeconds = slowDuration,
            )
            val phaseProgress = advancePhaseProgressMillis(
                startingPhase = notificationPhase,
                startingPhaseElapsedMillis = notificationPhaseElapsedMillis,
                additionalElapsedMillis = deltaMillis,
                fastPhaseDurationSeconds = fastDuration,
                slowPhaseDurationSeconds = slowDuration,
            )
            TimerUiState(
                currentPhase = phaseProgress.currentPhase,
                remainingSeconds = remainingSecondsForPhaseMillis(
                    phase = phaseProgress.currentPhase,
                    phaseElapsedMillis = phaseProgress.phaseElapsedMillis,
                    fastPhaseDurationSeconds = fastDuration,
                    slowPhaseDurationSeconds = slowDuration,
                ),
                elapsedSeconds = elapsedSecondsFromMillis(
                    notificationElapsedSeconds.toLong() * 1_000L + deltaMillis,
                ),
                fastPhaseDurationSeconds = fastDuration,
                slowPhaseDurationSeconds = slowDuration,
                isRunning = true,
                isPaused = false,
            )
        }
    }

    fun notificationUiState(): TimerUiState {
        val fastDuration = normalizePhaseDurationSeconds(fastPhaseDurationSeconds)
        val slowDuration = normalizePhaseDurationSeconds(slowPhaseDurationSeconds)
        return TimerUiState(
            currentPhase = notificationPhase,
            remainingSeconds = notificationRemainingSeconds,
            elapsedSeconds = notificationElapsedSeconds,
            fastPhaseDurationSeconds = fastDuration,
            slowPhaseDurationSeconds = slowDuration,
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

data class PhaseProgress(
    val currentPhase: WalkingPhase,
    val phaseElapsedSeconds: Int,
)

data class PhaseProgressMillis(
    val currentPhase: WalkingPhase,
    val phaseElapsedMillis: Long,
)

internal fun normalizePhaseDurationSeconds(seconds: Int): Int {
    return if (seconds in PHASE_DURATION_OPTIONS_SECONDS) {
        seconds
    } else {
        DEFAULT_PHASE_DURATION_SECONDS
    }
}

internal fun phaseDurationSeconds(
    phase: WalkingPhase,
    fastPhaseDurationSeconds: Int,
    slowPhaseDurationSeconds: Int,
): Int {
    return if (phase == WalkingPhase.Fast) fastPhaseDurationSeconds else slowPhaseDurationSeconds
}

internal fun remainingSecondsForPhase(
    phase: WalkingPhase,
    phaseElapsedSeconds: Int,
    fastPhaseDurationSeconds: Int,
    slowPhaseDurationSeconds: Int,
): Int {
    val durationSeconds = phaseDurationSeconds(
        phase = phase,
        fastPhaseDurationSeconds = fastPhaseDurationSeconds,
        slowPhaseDurationSeconds = slowPhaseDurationSeconds,
    )
    return durationSeconds - phaseElapsedSeconds.coerceIn(0, durationSeconds)
}

internal fun remainingSecondsForPhaseMillis(
    phase: WalkingPhase,
    phaseElapsedMillis: Long,
    fastPhaseDurationSeconds: Int,
    slowPhaseDurationSeconds: Int,
): Int {
    val durationMillis = phaseDurationMillis(
        phase = phase,
        fastPhaseDurationSeconds = fastPhaseDurationSeconds,
        slowPhaseDurationSeconds = slowPhaseDurationSeconds,
    )
    val clampedRemainingMillis = (durationMillis - phaseElapsedMillis).coerceIn(0L, durationMillis)
    return ((clampedRemainingMillis + 999L) / 1_000L).toInt()
}

internal fun elapsedSecondsInPhase(
    phase: WalkingPhase,
    remainingSeconds: Int,
    fastPhaseDurationSeconds: Int,
    slowPhaseDurationSeconds: Int,
): Int {
    val durationSeconds = phaseDurationSeconds(
        phase = phase,
        fastPhaseDurationSeconds = fastPhaseDurationSeconds,
        slowPhaseDurationSeconds = slowPhaseDurationSeconds,
    )
    return (durationSeconds - remainingSeconds).coerceIn(0, durationSeconds)
}

internal fun elapsedMillisInPhase(
    phase: WalkingPhase,
    remainingSeconds: Int,
    fastPhaseDurationSeconds: Int,
    slowPhaseDurationSeconds: Int,
): Long {
    return elapsedSecondsInPhase(
        phase = phase,
        remainingSeconds = remainingSeconds,
        fastPhaseDurationSeconds = fastPhaseDurationSeconds,
        slowPhaseDurationSeconds = slowPhaseDurationSeconds,
    ).toLong() * 1_000L
}

internal fun advancePhaseProgress(
    startingPhase: WalkingPhase,
    startingPhaseElapsedSeconds: Int,
    additionalElapsedSeconds: Int,
    fastPhaseDurationSeconds: Int,
    slowPhaseDurationSeconds: Int,
    onPhaseTransition: ((WalkingPhase) -> Unit)? = null,
): PhaseProgress {
    var currentPhase = startingPhase
    var phaseElapsedSeconds = startingPhaseElapsedSeconds + additionalElapsedSeconds

    while (true) {
        val currentPhaseDurationSeconds = phaseDurationSeconds(
            phase = currentPhase,
            fastPhaseDurationSeconds = fastPhaseDurationSeconds,
            slowPhaseDurationSeconds = slowPhaseDurationSeconds,
        )
        if (phaseElapsedSeconds < currentPhaseDurationSeconds) {
            return PhaseProgress(
                currentPhase = currentPhase,
                phaseElapsedSeconds = phaseElapsedSeconds,
            )
        }
        phaseElapsedSeconds -= currentPhaseDurationSeconds
        currentPhase = currentPhase.next()
        onPhaseTransition?.invoke(currentPhase)
    }
}

internal fun advancePhaseProgressMillis(
    startingPhase: WalkingPhase,
    startingPhaseElapsedMillis: Long,
    additionalElapsedMillis: Long,
    fastPhaseDurationSeconds: Int,
    slowPhaseDurationSeconds: Int,
): PhaseProgressMillis {
    var currentPhase = startingPhase
    var phaseElapsedMillis = startingPhaseElapsedMillis + additionalElapsedMillis

    while (true) {
        val currentPhaseDurationMillis = phaseDurationMillis(
            phase = currentPhase,
            fastPhaseDurationSeconds = fastPhaseDurationSeconds,
            slowPhaseDurationSeconds = slowPhaseDurationSeconds,
        )
        if (phaseElapsedMillis < currentPhaseDurationMillis) {
            return PhaseProgressMillis(
                currentPhase = currentPhase,
                phaseElapsedMillis = phaseElapsedMillis,
            )
        }
        phaseElapsedMillis -= currentPhaseDurationMillis
        currentPhase = currentPhase.next()
    }
}

internal fun TimerUiState.toPersistedState(
    nowElapsedRealtime: Long,
    nowWallClockMillis: Long,
): PersistedTimerState {
    val normalizedFastDuration = normalizePhaseDurationSeconds(fastPhaseDurationSeconds)
    val normalizedSlowDuration = normalizePhaseDurationSeconds(slowPhaseDurationSeconds)
    return PersistedTimerState(
        currentPhase = currentPhase,
        totalElapsedBeforeRunMillis = elapsedSeconds.toLong() * 1_000L,
        phaseElapsedBeforeRunMillis = elapsedMillisInPhase(
            phase = currentPhase,
            remainingSeconds = remainingSeconds,
            fastPhaseDurationSeconds = normalizedFastDuration,
            slowPhaseDurationSeconds = normalizedSlowDuration,
        ),
        fastPhaseDurationSeconds = normalizedFastDuration,
        slowPhaseDurationSeconds = normalizedSlowDuration,
        runStartedAtElapsedRealtime = if (isRunning) nowElapsedRealtime else 0L,
        phaseStartedAtElapsedRealtime = if (isRunning) nowElapsedRealtime else 0L,
        persistedAtElapsedRealtime = nowElapsedRealtime,
        persistedAtWallClockMillis = nowWallClockMillis,
        isRunning = isRunning,
        isPaused = isPaused,
        notificationPhase = currentPhase,
        notificationRemainingSeconds = remainingSeconds,
        notificationElapsedSeconds = elapsedSeconds,
        notificationIsRunning = isRunning,
        notificationIsPaused = isPaused,
    )
}

internal fun phaseDurationMillis(
    phase: WalkingPhase,
    fastPhaseDurationSeconds: Int,
    slowPhaseDurationSeconds: Int,
): Long {
    return phaseDurationSeconds(
        phase = phase,
        fastPhaseDurationSeconds = fastPhaseDurationSeconds,
        slowPhaseDurationSeconds = slowPhaseDurationSeconds,
    ).toLong() * 1_000L
}

internal fun elapsedSecondsFromMillis(elapsedMillis: Long): Int {
    return (elapsedMillis / 1_000L).toInt()
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

internal fun formatPhaseDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes == 0 -> "${seconds}秒"
        seconds == 0 -> "${minutes}分"
        else -> "${minutes}分${seconds}秒"
    }
}
