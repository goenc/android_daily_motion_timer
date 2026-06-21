package com.goenc.dailymotiontimer

import java.util.Locale
import kotlin.math.roundToInt

enum class WalkingPhase(val label: String, val announcement: String) {
    Slow(label = "ゆっくり歩く", announcement = "ゆっくり歩いてください"),
    Fast(label = "早く歩く", announcement = "早く歩いてください");

    fun next(): WalkingPhase = if (this == Slow) Fast else Slow
}

enum class BeepPitchPreset(
    val label: String,
    val frequencyHz: Double,
) {
    Low(label = "低", frequencyHz = 784.0),
    Mid(label = "中", frequencyHz = 1250.0),
    High(label = "高", frequencyHz = 1800.0);
}

val PHASE_DURATION_OPTIONS_SECONDS = listOf(10, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330)
val BEEP_INTERVAL_OPTIONS_SECONDS = listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 4.5f, 5.0f, 5.5f, 6.0f, 6.5f, 7.0f, 7.5f, 8.0f, 8.5f, 9.0f, 9.5f, 10.0f)
val SET_COUNT_OPTIONS = listOf(5, 10, 15, 20)
val START_DELAY_OPTIONS_SECONDS = listOf(0)
const val DEFAULT_PHASE_DURATION_SECONDS = 180
const val DEFAULT_FAST_BEEP_INTERVAL_SECONDS = 3.0f
const val DEFAULT_SLOW_BEEP_INTERVAL_SECONDS = 5.0f
const val DEFAULT_SET_COUNT = 5
const val DEFAULT_START_DELAY_SECONDS = 0
const val DEFAULT_ANNOUNCEMENT_VOLUME = 1.0f
const val DEFAULT_BEEP_VOLUME = 0.4f
const val MAX_ANNOUNCEMENT_VOLUME = 2.0f
const val MAX_BEEP_VOLUME = 0.4f

data class TimerUiState(
    val currentPhase: WalkingPhase = WalkingPhase.Fast,
    val remainingSeconds: Int = DEFAULT_PHASE_DURATION_SECONDS,
    val elapsedSeconds: Int = 0,
    val fastPhaseDurationSeconds: Int = DEFAULT_PHASE_DURATION_SECONDS,
    val slowPhaseDurationSeconds: Int = DEFAULT_PHASE_DURATION_SECONDS,
    val fastPhaseBeepIntervalSeconds: Float = DEFAULT_FAST_BEEP_INTERVAL_SECONDS,
    val slowPhaseBeepIntervalSeconds: Float = DEFAULT_SLOW_BEEP_INTERVAL_SECONDS,
    val fastPhaseBeepPitchPreset: BeepPitchPreset = BeepPitchPreset.Mid,
    val slowPhaseBeepPitchPreset: BeepPitchPreset = BeepPitchPreset.Mid,
    val setCount: Int = DEFAULT_SET_COUNT,
    val startDelaySeconds: Int = DEFAULT_START_DELAY_SECONDS,
    val announcementVolume: Float = DEFAULT_ANNOUNCEMENT_VOLUME,
    val beepVolume: Float = DEFAULT_BEEP_VOLUME,
    val isVibrationEnabled: Boolean = true,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val sessionStartElapsedRealtime: Long = 0L,
    val accumulatedPauseMillis: Long = 0L,
    val pauseStartedElapsedRealtime: Long = 0L,
    val startPhase: WalkingPhase = WalkingPhase.Fast,
    val preStartRemainingSeconds: Int = 0,
) {
    val formattedRemainingTime: String
        get() = formatRemainingDuration(remainingSeconds)

    val formattedElapsedTime: String
        get() = formatElapsedDuration(elapsedSeconds)

    val formattedFastPhaseDuration: String
        get() = formatPhaseDuration(fastPhaseDurationSeconds)

    val formattedSlowPhaseDuration: String
        get() = formatPhaseDuration(slowPhaseDurationSeconds)

    val formattedFastPhaseBeepInterval: String
        get() = formatBeepInterval(fastPhaseBeepIntervalSeconds)

    val formattedSlowPhaseBeepInterval: String
        get() = formatBeepInterval(slowPhaseBeepIntervalSeconds)

    val formattedFastPhaseBeepPitch: String
        get() = formatBeepPitchPreset(fastPhaseBeepPitchPreset)

    val formattedSlowPhaseBeepPitch: String
        get() = formatBeepPitchPreset(slowPhaseBeepPitchPreset)

    val formattedSetCount: String
        get() = formatSetCount(setCount)

    val currentSetNumber: Int
        get() = calculateCurrentSetNumber(
            elapsedSeconds = elapsedSeconds,
            fastPhaseDurationSeconds = fastPhaseDurationSeconds,
            slowPhaseDurationSeconds = slowPhaseDurationSeconds,
            setCount = setCount,
            isActive = isActive,
        )

    val isActive: Boolean
        get() = isRunning || isPaused

    val isPreparingStart: Boolean
        get() = isRunning && sessionStartElapsedRealtime > 0L && preStartRemainingSeconds > 0

    fun resolveAt(nowElapsedRealtime: Long): TimerUiState {
        val normalizedFastDurationSeconds = normalizePhaseDurationSeconds(fastPhaseDurationSeconds)
        val normalizedSlowDurationSeconds = normalizePhaseDurationSeconds(slowPhaseDurationSeconds)
        val normalizedFastBeepIntervalSeconds =
            normalizeBeepIntervalSeconds(fastPhaseBeepIntervalSeconds, DEFAULT_FAST_BEEP_INTERVAL_SECONDS)
        val normalizedSlowBeepIntervalSeconds =
            normalizeBeepIntervalSeconds(slowPhaseBeepIntervalSeconds, DEFAULT_SLOW_BEEP_INTERVAL_SECONDS)
        val normalizedFastBeepPitchPreset = normalizeBeepPitchPreset(fastPhaseBeepPitchPreset)
        val normalizedSlowBeepPitchPreset = normalizeBeepPitchPreset(slowPhaseBeepPitchPreset)
        val normalizedStartDelaySeconds = normalizeStartDelaySeconds(startDelaySeconds)
        val preStartRemainingSeconds =
            if (isRunning && sessionStartElapsedRealtime > nowElapsedRealtime) {
                (((sessionStartElapsedRealtime - nowElapsedRealtime) + 999L) / 1_000L).toInt()
            } else {
                0
            }
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
            fastPhaseBeepIntervalSeconds = normalizedFastBeepIntervalSeconds,
            slowPhaseBeepIntervalSeconds = normalizedSlowBeepIntervalSeconds,
            fastPhaseBeepPitchPreset = normalizedFastBeepPitchPreset,
            slowPhaseBeepPitchPreset = normalizedSlowBeepPitchPreset,
            setCount = normalizeSetCount(setCount),
            startDelaySeconds = normalizedStartDelaySeconds,
            announcementVolume = normalizeAnnouncementVolume(announcementVolume),
            beepVolume = normalizeBeepVolume(beepVolume),
            preStartRemainingSeconds = preStartRemainingSeconds,
        )
    }
}

data class NormalTimerUiState(
    val elapsedSeconds: Int = 0,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val sessionStartElapsedRealtime: Long = 0L,
    val accumulatedPauseMillis: Long = 0L,
    val pauseStartedElapsedRealtime: Long = 0L,
) {
    val formattedElapsedTime: String
        get() = formatElapsedDuration(elapsedSeconds)

    val isActive: Boolean
        get() = isRunning || isPaused

    fun resolveAt(nowElapsedRealtime: Long): NormalTimerUiState {
        val resolvedElapsedSeconds = elapsedSecondsFromMillis(
            calculateElapsedActiveMillis(
                nowElapsedRealtime = nowElapsedRealtime,
                sessionStartElapsedRealtime = sessionStartElapsedRealtime,
                accumulatedPauseMillis = accumulatedPauseMillis,
                pauseStartedElapsedRealtime = pauseStartedElapsedRealtime,
                isRunning = isRunning,
                isPaused = isPaused,
            ),
        )
        return copy(elapsedSeconds = resolvedElapsedSeconds)
    }
}

data class PersistedTimerState(
    val sessionStartElapsedRealtime: Long,
    val accumulatedPauseMillis: Long,
    val pauseStartedElapsedRealtime: Long,
    val fastDurationMillis: Long,
    val slowDurationMillis: Long,
    val fastPhaseBeepIntervalSeconds: Float,
    val slowPhaseBeepIntervalSeconds: Float,
    val fastPhaseBeepPitchPreset: BeepPitchPreset,
    val slowPhaseBeepPitchPreset: BeepPitchPreset,
    val setCount: Int,
    val startDelaySeconds: Int,
    val startPhase: WalkingPhase,
    val isRunning: Boolean,
    val isPaused: Boolean,
    val announcementVolume: Float,
    val beepVolume: Float,
    val isVibrationEnabled: Boolean,
) {
    fun sanitized(nowElapsedRealtime: Long): PersistedTimerState {
        val normalizedFastDurationMillis = normalizePhaseDurationMillis(fastDurationMillis)
        val normalizedSlowDurationMillis = normalizePhaseDurationMillis(slowDurationMillis)
        val normalizedFastBeepIntervalSeconds =
            normalizeBeepIntervalSeconds(fastPhaseBeepIntervalSeconds, DEFAULT_FAST_BEEP_INTERVAL_SECONDS)
        val normalizedSlowBeepIntervalSeconds =
            normalizeBeepIntervalSeconds(slowPhaseBeepIntervalSeconds, DEFAULT_SLOW_BEEP_INTERVAL_SECONDS)
        val normalizedFastBeepPitchPreset = normalizeBeepPitchPreset(fastPhaseBeepPitchPreset)
        val normalizedSlowBeepPitchPreset = normalizeBeepPitchPreset(slowPhaseBeepPitchPreset)
        val normalizedAnnouncementVolume = normalizeAnnouncementVolume(announcementVolume)
        val normalizedBeepVolume = normalizeBeepVolume(beepVolume)
        val hasValidSession =
            sessionStartElapsedRealtime > 0L &&
                (sessionStartElapsedRealtime <= nowElapsedRealtime || isRunning)
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
            fastPhaseBeepIntervalSeconds = normalizedFastBeepIntervalSeconds,
            slowPhaseBeepIntervalSeconds = normalizedSlowBeepIntervalSeconds,
            fastPhaseBeepPitchPreset = normalizedFastBeepPitchPreset,
            slowPhaseBeepPitchPreset = normalizedSlowBeepPitchPreset,
            startDelaySeconds = normalizeStartDelaySeconds(startDelaySeconds),
            isRunning = sanitizedIsRunning,
            isPaused = sanitizedIsPaused,
            announcementVolume = normalizedAnnouncementVolume,
            beepVolume = normalizedBeepVolume,
        )
    }

    fun toUiState(nowElapsedRealtime: Long): TimerUiState {
        val sanitizedState = sanitized(nowElapsedRealtime)
        return TimerUiState(
            fastPhaseDurationSeconds = durationSecondsFromMillis(sanitizedState.fastDurationMillis),
            slowPhaseDurationSeconds = durationSecondsFromMillis(sanitizedState.slowDurationMillis),
            fastPhaseBeepIntervalSeconds = normalizeBeepIntervalSeconds(
                sanitizedState.fastPhaseBeepIntervalSeconds,
                DEFAULT_FAST_BEEP_INTERVAL_SECONDS,
            ),
            slowPhaseBeepIntervalSeconds = normalizeBeepIntervalSeconds(
                sanitizedState.slowPhaseBeepIntervalSeconds,
                DEFAULT_SLOW_BEEP_INTERVAL_SECONDS,
            ),
            fastPhaseBeepPitchPreset = normalizeBeepPitchPreset(sanitizedState.fastPhaseBeepPitchPreset),
            slowPhaseBeepPitchPreset = normalizeBeepPitchPreset(sanitizedState.slowPhaseBeepPitchPreset),
            setCount = normalizeSetCount(sanitizedState.setCount),
            startDelaySeconds = normalizeStartDelaySeconds(sanitizedState.startDelaySeconds),
            announcementVolume = sanitizedState.announcementVolume,
            beepVolume = sanitizedState.beepVolume,
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
        volume.coerceIn(0.0f, MAX_ANNOUNCEMENT_VOLUME)
    }
}

internal fun normalizeBeepVolume(volume: Float): Float {
    return if (volume.isNaN() || volume.isInfinite()) {
        DEFAULT_BEEP_VOLUME
    } else {
        volume.coerceIn(0.0f, MAX_BEEP_VOLUME)
    }
}

internal fun beepVolumeToDisplayPercent(volume: Float): Int {
    return ((normalizeBeepVolume(volume) / MAX_BEEP_VOLUME) * 100f).roundToInt().coerceIn(0, 100)
}

internal fun beepVolumeFromDisplayPercent(percent: Int): Float {
    val clampedPercent = percent.coerceIn(0, 100)
    return normalizeBeepVolume((clampedPercent / 100f) * MAX_BEEP_VOLUME)
}

internal fun normalizeBeepIntervalSeconds(seconds: Float, defaultValue: Float): Float {
    if (seconds.isNaN() || seconds.isInfinite()) {
        return defaultValue
    }
    val normalized = ((seconds * 2.0f).roundToInt() / 2.0f)
    return normalized.coerceIn(BEEP_INTERVAL_OPTIONS_SECONDS.first(), BEEP_INTERVAL_OPTIONS_SECONDS.last())
}

internal fun normalizeBeepPitchPreset(preset: BeepPitchPreset): BeepPitchPreset {
    return if (BeepPitchPreset.entries.contains(preset)) preset else BeepPitchPreset.Mid
}

internal fun normalizeSetCount(setCount: Int): Int {
    return if (setCount in SET_COUNT_OPTIONS) {
        setCount
    } else {
        DEFAULT_SET_COUNT
    }
}

internal fun normalizeStartDelaySeconds(startDelaySeconds: Int): Int {
    return if (startDelaySeconds in START_DELAY_OPTIONS_SECONDS) {
        startDelaySeconds
    } else {
        DEFAULT_START_DELAY_SECONDS
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
    val normalizedBeepVolume = normalizeBeepVolume(beepVolume)
    return PersistedTimerState(
        sessionStartElapsedRealtime = if (isActive) sessionStartElapsedRealtime else 0L,
        accumulatedPauseMillis = if (isActive) accumulatedPauseMillis.coerceAtLeast(0L) else 0L,
        pauseStartedElapsedRealtime = if (isPaused) pauseStartedElapsedRealtime else 0L,
        fastDurationMillis = durationMillisFromSeconds(normalizedFastDurationSeconds),
        slowDurationMillis = durationMillisFromSeconds(normalizedSlowDurationSeconds),
        fastPhaseBeepIntervalSeconds = normalizeBeepIntervalSeconds(
            fastPhaseBeepIntervalSeconds,
            DEFAULT_FAST_BEEP_INTERVAL_SECONDS,
        ),
        slowPhaseBeepIntervalSeconds = normalizeBeepIntervalSeconds(
            slowPhaseBeepIntervalSeconds,
            DEFAULT_SLOW_BEEP_INTERVAL_SECONDS,
        ),
        fastPhaseBeepPitchPreset = normalizeBeepPitchPreset(fastPhaseBeepPitchPreset),
        slowPhaseBeepPitchPreset = normalizeBeepPitchPreset(slowPhaseBeepPitchPreset),
        setCount = normalizeSetCount(setCount),
        startDelaySeconds = normalizeStartDelaySeconds(startDelaySeconds),
        startPhase = if (isActive) startPhase else WalkingPhase.Fast,
        isRunning = isRunning,
        isPaused = isPaused,
        announcementVolume = normalizedAnnouncementVolume,
        beepVolume = normalizedBeepVolume,
        isVibrationEnabled = isVibrationEnabled,
    )
}

internal fun elapsedSecondsFromMillis(elapsedMillis: Long): Int {
    return (elapsedMillis / 1_000L).toInt()
}

internal fun formatRemainingDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

internal fun formatElapsedDuration(totalSeconds: Int): String {
    val normalizedSeconds = totalSeconds.coerceAtLeast(0)
    val hours = normalizedSeconds / 3_600
    val minutes = (normalizedSeconds % 3_600) / 60
    val seconds = normalizedSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
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

internal fun formatSetCount(setCount: Int): String {
    return "${normalizeSetCount(setCount)}セット"
}

internal fun formatBeepInterval(totalSeconds: Int): String {
    return "${formatBeepInterval(totalSeconds.toFloat())}"
}

internal fun formatBeepInterval(totalSeconds: Float): String {
    val normalized = normalizeBeepIntervalSeconds(totalSeconds, DEFAULT_FAST_BEEP_INTERVAL_SECONDS)
    val formatted = if (normalized % 1.0f == 0f) {
        normalized.toInt().toString()
    } else {
        normalized.toString().trimEnd('0').trimEnd('.')
    }
    return "${formatted}秒ごと"
}

internal fun formatBeepPitchPreset(preset: BeepPitchPreset): String {
    return preset.label
}

internal fun calculateCurrentSetNumber(
    elapsedSeconds: Int,
    fastPhaseDurationSeconds: Int,
    slowPhaseDurationSeconds: Int,
    setCount: Int,
    isActive: Boolean,
): Int {
    if (!isActive) {
        return 1
    }
    val cycleSeconds =
        normalizePhaseDurationSeconds(fastPhaseDurationSeconds) +
            normalizePhaseDurationSeconds(slowPhaseDurationSeconds)
    if (cycleSeconds <= 0) {
        return 1
    }
    val currentSet = (elapsedSeconds / cycleSeconds) + 1
    return currentSet.coerceIn(1, normalizeSetCount(setCount))
}
