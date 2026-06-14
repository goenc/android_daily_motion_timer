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
            fastPhaseBeepIntervalSeconds = 2.5f,
            slowPhaseBeepIntervalSeconds = 6.5f,
            fastPhaseBeepPitchPreset = BeepPitchPreset.Low,
            slowPhaseBeepPitchPreset = BeepPitchPreset.High,
            setCount = 10,
            startDelaySeconds = 0,
            startPhase = WalkingPhase.Slow,
            isRunning = true,
            isPaused = false,
            announcementVolume = 1.5f,
            beepVolume = 0.2f,
            isVibrationEnabled = false,
        )

        val restored = state.toUiState(nowElapsedRealtime = 16_000L)

        assertEquals(WalkingPhase.Slow, restored.currentPhase)
        assertEquals(2.5f, restored.fastPhaseBeepIntervalSeconds, 0.0f)
        assertEquals(6.5f, restored.slowPhaseBeepIntervalSeconds, 0.0f)
        assertEquals(BeepPitchPreset.Low, restored.fastPhaseBeepPitchPreset)
        assertEquals(BeepPitchPreset.High, restored.slowPhaseBeepPitchPreset)
        assertEquals(0.2f, restored.beepVolume, 0.0f)
        assertEquals(30, restored.fastPhaseDurationSeconds)
        assertEquals(90, restored.slowPhaseDurationSeconds)
        assertEquals(10, restored.setCount)
    }

    @Test
    fun toPersistedStateNormalizesBeepIntervals() {
        val state = TimerUiState(
            fastPhaseBeepIntervalSeconds = 0.1f,
            slowPhaseBeepIntervalSeconds = 99.0f,
            fastPhaseBeepPitchPreset = BeepPitchPreset.Low,
            slowPhaseBeepPitchPreset = BeepPitchPreset.High,
            announcementVolume = 2.5f,
            beepVolume = 3.5f,
        )

        val persisted = state.toPersistedState()

        assertEquals(0.5f, persisted.fastPhaseBeepIntervalSeconds, 0.0f)
        assertEquals(10.0f, persisted.slowPhaseBeepIntervalSeconds, 0.0f)
        assertEquals(BeepPitchPreset.Low, persisted.fastPhaseBeepPitchPreset)
        assertEquals(BeepPitchPreset.High, persisted.slowPhaseBeepPitchPreset)
        assertEquals(MAX_ANNOUNCEMENT_VOLUME, persisted.announcementVolume, 0.0f)
        assertEquals(0.4f, persisted.beepVolume, 0.0f)
    }

    @Test
    fun formatElapsedDuration_usesStopwatchStyleWithoutHourUnderOneHour() {
        assertEquals("0:00", formatElapsedDuration(0))
        assertEquals("0:45", formatElapsedDuration(45))
        assertEquals("12:00", formatElapsedDuration(12 * 60))
        assertEquals("59:59", formatElapsedDuration(3_599))
    }

    @Test
    fun formatElapsedDuration_addsHourFromOneHour() {
        assertEquals("1:00:00", formatElapsedDuration(3_600))
        assertEquals("1:05:30", formatElapsedDuration(3_930))
        assertEquals("2:03:00", formatElapsedDuration(7_380))
    }
}
