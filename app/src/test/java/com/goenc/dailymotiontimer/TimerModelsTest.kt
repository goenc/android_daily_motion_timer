package com.goenc.dailymotiontimer

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerModelsTest {
    @Test
    fun toUiStateRestoresBeepIntervalsAndDurations() {
        val state = PersistedTimerState(
            sessionStartElapsedRealtime = 1_000L,
            accumulatedPauseMillis = 0L,
            pauseStartedElapsedRealtime = 0L,
            fastDurationMillis = 30_000L,
            slowDurationMillis = 90_000L,
            fastPhaseBeepIntervalSeconds = 2,
            slowPhaseBeepIntervalSeconds = 6,
            setCount = 10,
            startDelaySeconds = 0,
            startPhase = WalkingPhase.Slow,
            isRunning = true,
            isPaused = false,
            announcementVolume = 1.5f,
            isVibrationEnabled = false,
        )

        val restored = state.toUiState(nowElapsedRealtime = 16_000L)

        assertEquals(WalkingPhase.Slow, restored.currentPhase)
        assertEquals(2, restored.fastPhaseBeepIntervalSeconds)
        assertEquals(6, restored.slowPhaseBeepIntervalSeconds)
        assertEquals(30, restored.fastPhaseDurationSeconds)
        assertEquals(90, restored.slowPhaseDurationSeconds)
        assertEquals(10, restored.setCount)
    }

    @Test
    fun toPersistedStateNormalizesBeepIntervals() {
        val state = TimerUiState(
            fastPhaseBeepIntervalSeconds = 0,
            slowPhaseBeepIntervalSeconds = 99,
            announcementVolume = 2.5f,
        )

        val persisted = state.toPersistedState()

        assertEquals(DEFAULT_FAST_BEEP_INTERVAL_SECONDS, persisted.fastPhaseBeepIntervalSeconds)
        assertEquals(DEFAULT_SLOW_BEEP_INTERVAL_SECONDS, persisted.slowPhaseBeepIntervalSeconds)
        assertEquals(MAX_ANNOUNCEMENT_VOLUME, persisted.announcementVolume, 0.0f)
    }
}
