package com.goenc.dailymotiontimer

import android.content.Context

internal object WalkingTimerStateStore {
    private const val PREFS_NAME = "walking_timer_state"
    private const val KEY_CURRENT_PHASE = "current_phase"
    private const val KEY_TOTAL_ELAPSED_BEFORE_RUN_SECONDS = "total_elapsed_before_run_seconds"
    private const val KEY_PHASE_ELAPSED_BEFORE_RUN_SECONDS = "phase_elapsed_before_run_seconds"
    private const val KEY_TOTAL_ELAPSED_BEFORE_RUN_MILLIS = "total_elapsed_before_run_millis"
    private const val KEY_PHASE_ELAPSED_BEFORE_RUN_MILLIS = "phase_elapsed_before_run_millis"
    private const val KEY_FAST_PHASE_DURATION_SECONDS = "fast_phase_duration_seconds"
    private const val KEY_SLOW_PHASE_DURATION_SECONDS = "slow_phase_duration_seconds"
    private const val KEY_RUN_STARTED_AT_ELAPSED_REALTIME = "run_started_at_elapsed_realtime"
    private const val KEY_PHASE_STARTED_AT_ELAPSED_REALTIME = "phase_started_at_elapsed_realtime"
    private const val KEY_PERSISTED_AT_ELAPSED_REALTIME = "persisted_at_elapsed_realtime"
    private const val KEY_PERSISTED_AT_WALL_CLOCK_MILLIS = "persisted_at_wall_clock_millis"
    private const val KEY_IS_RUNNING = "is_running"
    private const val KEY_IS_PAUSED = "is_paused"
    private const val KEY_NOTIFICATION_PHASE = "notification_phase"
    private const val KEY_NOTIFICATION_REMAINING_SECONDS = "notification_remaining_seconds"
    private const val KEY_NOTIFICATION_ELAPSED_SECONDS = "notification_elapsed_seconds"
    private const val KEY_NOTIFICATION_IS_RUNNING = "notification_is_running"
    private const val KEY_NOTIFICATION_IS_PAUSED = "notification_is_paused"

    fun load(context: Context): PersistedTimerState? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_CURRENT_PHASE)) {
            return null
        }

        return PersistedTimerState(
            currentPhase = prefs.readPhase(KEY_CURRENT_PHASE, WalkingPhase.Fast),
            totalElapsedBeforeRunMillis = prefs.getLongOrFallbackSeconds(
                longKey = KEY_TOTAL_ELAPSED_BEFORE_RUN_MILLIS,
                secondsKey = KEY_TOTAL_ELAPSED_BEFORE_RUN_SECONDS,
            ),
            phaseElapsedBeforeRunMillis = prefs.getLongOrFallbackSeconds(
                longKey = KEY_PHASE_ELAPSED_BEFORE_RUN_MILLIS,
                secondsKey = KEY_PHASE_ELAPSED_BEFORE_RUN_SECONDS,
            ),
            fastPhaseDurationSeconds = normalizePhaseDurationSeconds(
                prefs.getInt(KEY_FAST_PHASE_DURATION_SECONDS, DEFAULT_PHASE_DURATION_SECONDS),
            ),
            slowPhaseDurationSeconds = normalizePhaseDurationSeconds(
                prefs.getInt(KEY_SLOW_PHASE_DURATION_SECONDS, DEFAULT_PHASE_DURATION_SECONDS),
            ),
            runStartedAtElapsedRealtime = prefs.getLong(KEY_RUN_STARTED_AT_ELAPSED_REALTIME, 0L),
            phaseStartedAtElapsedRealtime = prefs.getLong(KEY_PHASE_STARTED_AT_ELAPSED_REALTIME, 0L),
            persistedAtElapsedRealtime = prefs.getLong(KEY_PERSISTED_AT_ELAPSED_REALTIME, 0L),
            persistedAtWallClockMillis = prefs.getLong(KEY_PERSISTED_AT_WALL_CLOCK_MILLIS, 0L),
            isRunning = prefs.getBoolean(KEY_IS_RUNNING, false),
            isPaused = prefs.getBoolean(KEY_IS_PAUSED, false),
            notificationPhase = prefs.readPhase(KEY_NOTIFICATION_PHASE, WalkingPhase.Fast),
            notificationRemainingSeconds = prefs.getInt(
                KEY_NOTIFICATION_REMAINING_SECONDS,
                DEFAULT_PHASE_DURATION_SECONDS,
            ),
            notificationElapsedSeconds = prefs.getInt(KEY_NOTIFICATION_ELAPSED_SECONDS, 0),
            notificationIsRunning = prefs.getBoolean(KEY_NOTIFICATION_IS_RUNNING, false),
            notificationIsPaused = prefs.getBoolean(KEY_NOTIFICATION_IS_PAUSED, false),
        )
    }

    fun save(context: Context, state: PersistedTimerState) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CURRENT_PHASE, state.currentPhase.name)
            .putInt(
                KEY_TOTAL_ELAPSED_BEFORE_RUN_SECONDS,
                elapsedSecondsFromMillis(state.totalElapsedBeforeRunMillis),
            )
            .putInt(
                KEY_PHASE_ELAPSED_BEFORE_RUN_SECONDS,
                elapsedSecondsFromMillis(state.phaseElapsedBeforeRunMillis),
            )
            .putLong(KEY_TOTAL_ELAPSED_BEFORE_RUN_MILLIS, state.totalElapsedBeforeRunMillis)
            .putLong(KEY_PHASE_ELAPSED_BEFORE_RUN_MILLIS, state.phaseElapsedBeforeRunMillis)
            .putInt(KEY_FAST_PHASE_DURATION_SECONDS, state.fastPhaseDurationSeconds)
            .putInt(KEY_SLOW_PHASE_DURATION_SECONDS, state.slowPhaseDurationSeconds)
            .putLong(KEY_RUN_STARTED_AT_ELAPSED_REALTIME, state.runStartedAtElapsedRealtime)
            .putLong(KEY_PHASE_STARTED_AT_ELAPSED_REALTIME, state.phaseStartedAtElapsedRealtime)
            .putLong(KEY_PERSISTED_AT_ELAPSED_REALTIME, state.persistedAtElapsedRealtime)
            .putLong(KEY_PERSISTED_AT_WALL_CLOCK_MILLIS, state.persistedAtWallClockMillis)
            .putBoolean(KEY_IS_RUNNING, state.isRunning)
            .putBoolean(KEY_IS_PAUSED, state.isPaused)
            .putString(KEY_NOTIFICATION_PHASE, state.notificationPhase.name)
            .putInt(KEY_NOTIFICATION_REMAINING_SECONDS, state.notificationRemainingSeconds)
            .putInt(KEY_NOTIFICATION_ELAPSED_SECONDS, state.notificationElapsedSeconds)
            .putBoolean(KEY_NOTIFICATION_IS_RUNNING, state.notificationIsRunning)
            .putBoolean(KEY_NOTIFICATION_IS_PAUSED, state.notificationIsPaused)
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun android.content.SharedPreferences.readPhase(
        key: String,
        defaultValue: WalkingPhase,
    ): WalkingPhase {
        val rawValue = getString(key, defaultValue.name) ?: return defaultValue
        return runCatching { WalkingPhase.valueOf(rawValue) }.getOrDefault(defaultValue)
    }

    private fun android.content.SharedPreferences.getLongOrFallbackSeconds(
        longKey: String,
        secondsKey: String,
    ): Long {
        if (contains(longKey)) {
            return getLong(longKey, 0L)
        }
        return getInt(secondsKey, 0).toLong() * 1_000L
    }
}
