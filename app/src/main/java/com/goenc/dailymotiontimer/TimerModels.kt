package com.goenc.dailymotiontimer

import java.util.Locale

enum class WalkingPhase(val label: String, val announcement: String) {
    Slow(label = "ゆっくり歩く", announcement = "ゆっくり歩いてください"),
    Fast(label = "早く歩く", announcement = "早く歩いてください");

    fun next(): WalkingPhase = if (this == Slow) Fast else Slow
}

val PHASE_DURATION_OPTIONS_SECONDS = listOf(10, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330)
const val DEFAULT_PHASE_DURATION_SECONDS = 180
const val DEFAULT_ANNOUNCEMENT_VOLUME = 1.0f

data class TimerUiState(
    val currentPhase: WalkingPhase = WalkingPhase.Fast,
    val remainingSeconds: Int = DEFAULT_PHASE_DURATION_SECONDS,
    val elapsedSeconds: Int = 0,
    val fastPhaseDurationSeconds: Int = DEFAULT_PHASE_DURATION_SECONDS,
    val slowPhaseDurationSeconds: Int = DEFAULT_PHASE_DURATION_SECONDS,
    val announcementVolume: Float = DEFAULT_ANNOUNCEMENT_VOLUME,
    val isVibrationEnabled: Boolean = true,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val sessionStartElapsedRealtime: Long = 0L,
    val accumulatedPauseMillis: Long = 0L,
    val pauseStartedElapsedRealtime: Long = 0L,
    val startPhase: WalkingPhase = WalkingPhase.Fast,
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

    fun resolveAt(nowElapsedRealtime: Long): TimerUiState {
        val normalizedFastDurationSeconds = normalizePhaseDurationSeconds(fastPhaseDurationSeconds)
        val normalizedSlowDurationSeconds = normalizePhaseDurationSeconds(slowPhaseDurationSeconds)
        val snapshot = calculateTimerSessionSnapshot(
            nowElapsedRealtime = nowElapsedRealtime,
            sessionStartElapsedRealtime = sessionStartElapsedRealtime,
            accumulatedPauseMillis = accumulatedPauseMillis,
            pauseStartedElapsedRealtime = pauseStartedElapsedRealtime,
            fastDurationMillis = durationMillisFromSeconds(normalizedFastDurationSeconds),
            slowDurationMillis = durationMillisFromSeconds(normalizedSlowDurationSeconds),
            startPhase = startPhase,
            isRunning = isRunning,
            isPaused = isPaused,
        )
        return copy(
            currentPhase = snapshot.currentPhase,
            remainingSeconds = remainingSecondsForPhaseMillis(
                phase = snapshot.currentPhase,
                phaseElapsedMillis = snapshot.phaseElapsedMillis,
                fastPhaseDurationSeconds = normalizedFastDurationSeconds,
                slowPhaseDurationSeconds = normalizedSlowDurationSeconds,
            ),
            elapsedSeconds = elapsedSecondsFromMillis(snapshot.elapsedActiveMillis),
            fastPhaseDurationSeconds = normalizedFastDurationSeconds,
            slowPhaseDurationSeconds = normalizedSlowDurationSeconds,
            announcementVolume = normalizeAnnouncementVolume(announcementVolume),
        )
    }
}

data class PersistedTimerState(
    val sessionStartElapsedRealtime: Long,
    val accumulatedPauseMillis: Long,
    val pauseStartedElapsedRealtime: Long,
    val fastDurationMillis: Long,
    val slowDurationMillis: Long,
    val startPhase: WalkingPhase,
    val isRunning: Boolean,
    val isPaused: Boolean,
    val announcementVolume: Float,
    val isVibrationEnabled: Boolean,
) {
    fun sanitized(nowElapsedRealtime: Long): PersistedTimerState {
        val normalizedFastDurationMillis = normalizePhaseDurationMillis(fastDurationMillis)
        val normalizedSlowDurationMillis = normalizePhaseDurationMillis(slowDurationMillis)
        val normalizedAnnouncementVolume = normalizeAnnouncementVolume(announcementVolume)
        val hasValidSession =
            sessionStartElapsedRealtime > 0L && sessionStartElapsedRealtime <= nowElapsedRealtime
        val sanitizedPauseStartedElapsedRealtime =
            if (
                pauseStartedElapsedRealtime > 0L &&
                hasValidSession &&
                pauseStartedElapsedRealtime >= sessionStartElapsedRealtime &&
                pauseStartedElapsedRealtime <= nowElapsedRealtime
            ) {
                pauseStartedElapsedRealtime
            } else {
                0L
            }
        val sanitizedIsPaused = isPaused && sanitizedPauseStartedElapsedRealtime > 0L
        val sanitizedIsRunning = isRunning && hasValidSession && !sanitizedIsPaused
        return copy(
            sessionStartElapsedRealtime = if (hasValidSession) sessionStartElapsedRealtime else 0L,
            accumulatedPauseMillis = if (hasValidSession) accumulatedPauseMillis.coerceAtLeast(0L) else 0L,
            pauseStartedElapsedRealtime = if (sanitizedIsPaused) sanitizedPauseStartedElapsedRealtime else 0L,
            fastDurationMillis = normalizedFastDurationMillis,
            slowDurationMillis = normalizedSlowDurationMillis,
            isRunning = sanitizedIsRunning,
            isPaused = sanitizedIsPaused,
            announcementVolume = normalizedAnnouncementVolume,
        )
    }

    fun toUiState(nowElapsedRealtime: Long): TimerUiState {
        val sanitizedState = sanitized(nowElapsedRealtime)
        return TimerUiState(
            fastPhaseDurationSeconds = durationSecondsFromMillis(sanitizedState.fastDurationMillis),
            slowPhaseDurationSeconds = durationSecondsFromMillis(sanitizedState.slowDurationMillis),
            announcementVolume = sanitizedState.announcementVolume,
            isVibrationEnabled = sanitizedState.isVibrationEnabled,
            isRunning = sanitizedState.isRunning,
            isPaused = sanitizedState.isPaused,
            sessionStartElapsedRealtime = sanitizedState.sessionStartElapsedRealtime,
            accumulatedPauseMillis = sanitizedState.accumulatedPauseMillis,
            pauseStartedElapsedRealtime = sanitizedState.pauseStartedElapsedRealtime,
            startPhase = sanitizedState.startPhase,
        ).resolveAt(nowElapsedRealtime)
    }
}

internal data class TimerSessionSnapshot(
    val currentPhase: WalkingPhase,
    val phaseElapsedMillis: Long,
    val remainingPhaseMillis: Long,
    val elapsedActiveMillis: Long,
)

internal fun normalizePhaseDurationSeconds(seconds: Int): Int {
    return if (seconds in PHASE_DURATION_OPTIONS_SECONDS) {
        seconds
    } else {
        DEFAULT_PHASE_DURATION_SECONDS
    }
}

internal fun durationMillisFromSeconds(seconds: Int): Long {
    return normalizePhaseDurationSeconds(seconds).toLong() * 1_000L
}

internal fun durationSecondsFromMillis(durationMillis: Long): Int {
    return normalizePhaseDurationSeconds((durationMillis / 1_000L).toInt())
}

internal fun normalizePhaseDurationMillis(durationMillis: Long): Long {
    return durationMillisFromSeconds(durationSecondsFromMillis(durationMillis))
}

internal fun normalizeAnnouncementVolume(volume: Float): Float {
    return if (volume.isNaN() || volume.isInfinite()) {
        DEFAULT_ANNOUNCEMENT_VOLUME
    } else {
        volume.coerceIn(0.0f, 1.0f)
    }
}

internal fun phaseDurationSeconds(
    phase: WalkingPhase,
    fastPhaseDurationSeconds: Int,
    slowPhaseDurationSeconds: Int,
): Int {
    return if (phase == WalkingPhase.Fast) fastPhaseDurationSeconds else slowPhaseDurationSeconds
}

internal fun phaseDurationMillis(
    phase: WalkingPhase,
    fastDurationMillis: Long,
    slowDurationMillis: Long,
): Long {
    return if (phase == WalkingPhase.Fast) fastDurationMillis else slowDurationMillis
}

internal fun remainingSecondsForPhaseMillis(
    phase: WalkingPhase,
    phaseElapsedMillis: Long,
    fastPhaseDurationSeconds: Int,
    slowPhaseDurationSeconds: Int,
): Int {
    val durationMillis = phaseDurationMillis(
        phase = phase,
        fastDurationMillis = durationMillisFromSeconds(fastPhaseDurationSeconds),
        slowDurationMillis = durationMillisFromSeconds(slowPhaseDurationSeconds),
    )
    val clampedRemainingMillis = (durationMillis - phaseElapsedMillis).coerceIn(0L, durationMillis)
    return ((clampedRemainingMillis + 999L) / 1_000L).toInt()
}

internal fun calculateElapsedActiveMillis(
    nowElapsedRealtime: Long,
    sessionStartElapsedRealtime: Long,
    accumulatedPauseMillis: Long,
    pauseStartedElapsedRealtime: Long,
    isRunning: Boolean,
    isPaused: Boolean,
): Long {
    if (sessionStartElapsedRealtime <= 0L) {
        return 0L
    }

    val referenceElapsedRealtime = when {
        isRunning -> nowElapsedRealtime
        isPaused && pauseStartedElapsedRealtime > 0L -> pauseStartedElapsedRealtime
        else -> nowElapsedRealtime
    }
    return (referenceElapsedRealtime - sessionStartElapsedRealtime - accumulatedPauseMillis)
        .coerceAtLeast(0L)
}

internal fun calculateTimerSessionSnapshot(
    nowElapsedRealtime: Long,
    sessionStartElapsedRealtime: Long,
    accumulatedPauseMillis: Long,
    pauseStartedElapsedRealtime: Long,
    fastDurationMillis: Long,
    slowDurationMillis: Long,
    startPhase: WalkingPhase,
    isRunning: Boolean,
    isPaused: Boolean,
): TimerSessionSnapshot {
    val normalizedFastDurationMillis = normalizePhaseDurationMillis(fastDurationMillis)
    val normalizedSlowDurationMillis = normalizePhaseDurationMillis(slowDurationMillis)
    val elapsedActiveMillis = calculateElapsedActiveMillis(
        nowElapsedRealtime = nowElapsedRealtime,
        sessionStartElapsedRealtime = sessionStartElapsedRealtime,
        accumulatedPauseMillis = accumulatedPauseMillis.coerceAtLeast(0L),
        pauseStartedElapsedRealtime = pauseStartedElapsedRealtime,
        isRunning = isRunning,
        isPaused = isPaused,
    )
    val firstPhaseDurationMillis = phaseDurationMillis(
        phase = startPhase,
        fastDurationMillis = normalizedFastDurationMillis,
        slowDurationMillis = normalizedSlowDurationMillis,
    )
    val nextPhase = startPhase.next()
    val secondPhaseDurationMillis = phaseDurationMillis(
        phase = nextPhase,
        fastDurationMillis = normalizedFastDurationMillis,
        slowDurationMillis = normalizedSlowDurationMillis,
    )
    val cycleDurationMillis = firstPhaseDurationMillis + secondPhaseDurationMillis
    val positionInCycleMillis =
        if (cycleDurationMillis > 0L) elapsedActiveMillis % cycleDurationMillis else 0L

    return if (positionInCycleMillis < firstPhaseDurationMillis) {
        TimerSessionSnapshot(
            currentPhase = startPhase,
            phaseElapsedMillis = positionInCycleMillis,
            remainingPhaseMillis = (firstPhaseDurationMillis - positionInCycleMillis).coerceAtLeast(0L),
            elapsedActiveMillis = elapsedActiveMillis,
        )
    } else {
        val secondPhaseElapsedMillis = positionInCycleMillis - firstPhaseDurationMillis
        TimerSessionSnapshot(
            currentPhase = nextPhase,
            phaseElapsedMillis = secondPhaseElapsedMillis,
            remainingPhaseMillis = (secondPhaseDurationMillis - secondPhaseElapsedMillis).coerceAtLeast(0L),
            elapsedActiveMillis = elapsedActiveMillis,
        )
    }
}

internal fun TimerUiState.toPersistedState(): PersistedTimerState {
    val normalizedFastDurationSeconds = normalizePhaseDurationSeconds(fastPhaseDurationSeconds)
    val normalizedSlowDurationSeconds = normalizePhaseDurationSeconds(slowPhaseDurationSeconds)
    val normalizedAnnouncementVolume = normalizeAnnouncementVolume(announcementVolume)
    return PersistedTimerState(
        sessionStartElapsedRealtime = if (isActive) sessionStartElapsedRealtime else 0L,
        accumulatedPauseMillis = if (isActive) accumulatedPauseMillis.coerceAtLeast(0L) else 0L,
        pauseStartedElapsedRealtime = if (isPaused) pauseStartedElapsedRealtime else 0L,
        fastDurationMillis = durationMillisFromSeconds(normalizedFastDurationSeconds),
        slowDurationMillis = durationMillisFromSeconds(normalizedSlowDurationSeconds),
        startPhase = if (isActive) startPhase else WalkingPhase.Fast,
        isRunning = isRunning,
        isPaused = isPaused,
        announcementVolume = normalizedAnnouncementVolume,
        isVibrationEnabled = isVibrationEnabled,
    )
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
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
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
