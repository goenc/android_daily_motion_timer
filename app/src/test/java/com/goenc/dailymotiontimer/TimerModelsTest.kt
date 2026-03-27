package com.goenc.dailymotiontimer

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerModelsTest {
    @Test
    fun runningStateUsesStoredRealtimeBaseWithoutSaveTimeRounding() {
        val state = PersistedTimerState(
            currentPhase = WalkingPhase.Fast,
            totalElapsedBeforeRunMillis = 0L,
            phaseElapsedBeforeRunMillis = 0L,
            fastPhaseDurationSeconds = 30,
            slowPhaseDurationSeconds = 30,
            runStartedAtElapsedRealtime = 1_000L,
            phaseStartedAtElapsedRealtime = 1_000L,
            persistedAtElapsedRealtime = 30_400L,
            persistedAtWallClockMillis = 100_000L,
            isRunning = true,
            isPaused = false,
            notificationPhase = WalkingPhase.Slow,
            notificationRemainingSeconds = 30,
            notificationElapsedSeconds = 30,
            notificationIsRunning = true,
            notificationIsPaused = false,
        )

        val restored = state.toUiState(
            nowElapsedRealtime = 31_500L,
            nowWallClockMillis = 101_100L,
        )

        assertEquals(WalkingPhase.Slow, restored.currentPhase)
        assertEquals(30, restored.remainingSeconds)
        assertEquals(30, restored.elapsedSeconds)
    }

    @Test
    fun shortFastLongSlowCountdownRemainsStable() {
        val state = PersistedTimerState(
            currentPhase = WalkingPhase.Fast,
            totalElapsedBeforeRunMillis = 0L,
            phaseElapsedBeforeRunMillis = 0L,
            fastPhaseDurationSeconds = 30,
            slowPhaseDurationSeconds = 330,
            runStartedAtElapsedRealtime = 10_000L,
            phaseStartedAtElapsedRealtime = 10_000L,
            persistedAtElapsedRealtime = 25_000L,
            persistedAtWallClockMillis = 200_000L,
            isRunning = true,
            isPaused = false,
            notificationPhase = WalkingPhase.Fast,
            notificationRemainingSeconds = 15,
            notificationElapsedSeconds = 15,
            notificationIsRunning = true,
            notificationIsPaused = false,
        )

        val beforeSwitch = state.toUiState(
            nowElapsedRealtime = 39_500L,
            nowWallClockMillis = 229_500L,
        )
        val afterSwitch = state.toUiState(
            nowElapsedRealtime = 40_500L,
            nowWallClockMillis = 230_500L,
        )

        assertEquals(WalkingPhase.Fast, beforeSwitch.currentPhase)
        assertEquals(1, beforeSwitch.remainingSeconds)
        assertEquals(WalkingPhase.Slow, afterSwitch.currentPhase)
        assertEquals(330, afterSwitch.remainingSeconds)
    }

    @Test
    fun longFastShortSlowCountdownRemainsStable() {
        val state = PersistedTimerState(
            currentPhase = WalkingPhase.Fast,
            totalElapsedBeforeRunMillis = 0L,
            phaseElapsedBeforeRunMillis = 0L,
            fastPhaseDurationSeconds = 330,
            slowPhaseDurationSeconds = 30,
            runStartedAtElapsedRealtime = 5_000L,
            phaseStartedAtElapsedRealtime = 5_000L,
            persistedAtElapsedRealtime = 300_000L,
            persistedAtWallClockMillis = 400_000L,
            isRunning = true,
            isPaused = false,
            notificationPhase = WalkingPhase.Fast,
            notificationRemainingSeconds = 35,
            notificationElapsedSeconds = 295,
            notificationIsRunning = true,
            notificationIsPaused = false,
        )

        val beforeSwitch = state.toUiState(
            nowElapsedRealtime = 334_500L,
            nowWallClockMillis = 729_500L,
        )
        val afterSwitch = state.toUiState(
            nowElapsedRealtime = 335_500L,
            nowWallClockMillis = 730_500L,
        )

        assertEquals(WalkingPhase.Fast, beforeSwitch.currentPhase)
        assertEquals(1, beforeSwitch.remainingSeconds)
        assertEquals(WalkingPhase.Slow, afterSwitch.currentPhase)
        assertEquals(30, afterSwitch.remainingSeconds)
    }

    @Test
    fun rebootFallbackAdvancesFromPersistedNotificationState() {
        val state = PersistedTimerState(
            currentPhase = WalkingPhase.Fast,
            totalElapsedBeforeRunMillis = 0L,
            phaseElapsedBeforeRunMillis = 0L,
            fastPhaseDurationSeconds = 30,
            slowPhaseDurationSeconds = 30,
            runStartedAtElapsedRealtime = 90_000L,
            phaseStartedAtElapsedRealtime = 90_000L,
            persistedAtElapsedRealtime = 120_000L,
            persistedAtWallClockMillis = 1_000_000L,
            isRunning = true,
            isPaused = false,
            notificationPhase = WalkingPhase.Slow,
            notificationRemainingSeconds = 30,
            notificationElapsedSeconds = 30,
            notificationIsRunning = true,
            notificationIsPaused = false,
        )

        val restored = state.toUiState(
            nowElapsedRealtime = 5_000L,
            nowWallClockMillis = 1_001_200L,
        )

        assertEquals(WalkingPhase.Slow, restored.currentPhase)
        assertEquals(29, restored.remainingSeconds)
        assertEquals(31, restored.elapsedSeconds)
    }
}
