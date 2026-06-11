package com.goenc.dailymotiontimer

import android.content.Context
import android.os.SystemClock

internal object WalkingTimerStateStore {
    private const val PREFS_NAME = "walking_timer_state"

    private const val KEY_SESSION_START_ELAPSED_REALTIME = "session_start_elapsed_realtime"
    private const val KEY_ACCUMULATED_PAUSE_MILLIS = "accumulated_pause_millis"
    private const val KEY_PAUSE_STARTED_ELAPSED_REALTIME = "pause_started_elapsed_realtime"
    private const val KEY_FAST_DURATION_MILLIS = "fast_duration_millis"
    private const val KEY_SLOW_DURATION_MILLIS = "slow_duration_millis"
    private const val KEY_FAST_BEEP_INTERVAL_SECONDS = "fast_beep_interval_seconds"
    private const val KEY_SLOW_BEEP_INTERVAL_SECONDS = "slow_beep_interval_seconds"
    private const val KEY_SET_COUNT = "set_count"
    private const val KEY_START_DELAY_SECONDS = "start_delay_seconds"
    private const val KEY_START_PHASE = "start_phase"
    private const val KEY_IS_RUNNING = "is_running"
    private const val KEY_IS_PAUSED = "is_paused"
    private const val KEY_ANNOUNCEMENT_VOLUME = "announcement_volume"
    private const val KEY_IS_VIBRATION_ENABLED = "is_vibration_enabled"

    private const val LEGACY_KEY_CURRENT_PHASE = "current_phase"
    private const val LEGACY_KEY_TOTAL_ELAPSED_BEFORE_RUN_SECONDS = "total_elapsed_before_run_seconds"
    private const val LEGACY_KEY_TOTAL_ELAPSED_BEFORE_RUN_MILLIS = "total_elapsed_before_run_millis"
    private const val LEGACY_KEY_FAST_PHASE_DURATION_SECONDS = "fast_phase_duration_seconds"
    private const val LEGACY_KEY_SLOW_PHASE_DURATION_SECONDS = "slow_phase_duration_seconds"
    private const val LEGACY_KEY_RUN_STARTED_AT_ELAPSED_REALTIME = "run_started_at_elapsed_realtime"
    private const val LEGACY_KEY_PERSISTED_AT_ELAPSED_REALTIME = "persisted_at_elapsed_realtime"
    private const val LEGACY_KEY_NOTIFICATION_ELAPSED_SECONDS = "notification_elapsed_seconds"
    private const val LEGACY_KEY_NOTIFICATION_TOTAL_ELAPSED_MILLIS = "notification_total_elapsed_millis"
    private const val LEGACY_KEY_NOTIFICATION_PHASE = "notification_phase"
    private const val LEGACY_KEY_NOTIFICATION_REMAINING_SECONDS = "notification_remaining_seconds"
    private const val LEGACY_KEY_PHASE_ELAPSED_BEFORE_RUN_SECONDS = "phase_elapsed_before_run_seconds"
    private const val LEGACY_KEY_PHASE_ELAPSED_BEFORE_RUN_MILLIS = "phase_elapsed_before_run_millis"
    private const val LEGACY_KEY_PHASE_STARTED_AT_ELAPSED_REALTIME = "phase_started_at_elapsed_realtime"
    private const val LEGACY_KEY_PERSISTED_AT_WALL_CLOCK_MILLIS = "persisted_at_wall_clock_millis"
    private const val LEGACY_KEY_NOTIFICATION_PHASE_ELAPSED_MILLIS = "notification_phase_elapsed_millis"
    private const val LEGACY_KEY_NOTIFICATION_IS_RUNNING = "notification_is_running"
    private const val LEGACY_KEY_NOTIFICATION_IS_PAUSED = "notification_is_paused"

    fun load(context: Context): PersistedTimerState? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_FAST_DURATION_MILLIS)) {
            return PersistedTimerState(
                sessionStartElapsedRealtime = prefs.getLong(KEY_SESSION_START_ELAPSED_REALTIME, 0L),
                accumulatedPauseMillis = prefs.getLong(KEY_ACCUMULATED_PAUSE_MILLIS, 0L),
                pauseStartedElapsedRealtime = prefs.getLong(KEY_PAUSE_STARTED_ELAPSED_REALTIME, 0L),
                fastDurationMillis = prefs.getLong(
                    KEY_FAST_DURATION_MILLIS,
                    durationMillisFromSeconds(DEFAULT_PHASE_DURATION_SECONDS),
                ),
                slowDurationMillis = prefs.getLong(
                    KEY_SLOW_DURATION_MILLIS,
                    durationMillisFromSeconds(DEFAULT_PHASE_DURATION_SECONDS),
                ),
                fastPhaseBeepIntervalSeconds = normalizeBeepIntervalSeconds(
                    prefs.getInt(KEY_FAST_BEEP_INTERVAL_SECONDS, DEFAULT_FAST_BEEP_INTERVAL_SECONDS),
                    DEFAULT_FAST_BEEP_INTERVAL_SECONDS,
                ),
                slowPhaseBeepIntervalSeconds = normalizeBeepIntervalSeconds(
                    prefs.getInt(KEY_SLOW_BEEP_INTERVAL_SECONDS, DEFAULT_SLOW_BEEP_INTERVAL_SECONDS),
                    DEFAULT_SLOW_BEEP_INTERVAL_SECONDS,
                ),
                setCount = normalizeSetCount(prefs.getInt(KEY_SET_COUNT, DEFAULT_SET_COUNT)),
                startDelaySeconds = normalizeStartDelaySeconds(
                    prefs.getInt(KEY_START_DELAY_SECONDS, DEFAULT_START_DELAY_SECONDS),
                ),
                startPhase = prefs.readPhase(KEY_START_PHASE, WalkingPhase.Fast),
                isRunning = prefs.getBoolean(KEY_IS_RUNNING, false),
                isPaused = prefs.getBoolean(KEY_IS_PAUSED, false),
                announcementVolume = prefs.getFloat(KEY_ANNOUNCEMENT_VOLUME, DEFAULT_ANNOUNCEMENT_VOLUME),
                isVibrationEnabled = prefs.getBoolean(KEY_IS_VIBRATION_ENABLED, true),
            )
        }

        if (!prefs.contains(LEGACY_KEY_CURRENT_PHASE) &&
            !prefs.contains(LEGACY_KEY_FAST_PHASE_DURATION_SECONDS)
        ) {
            return null
        }

        val migratedState = loadLegacyState(prefs) ?: return null
        save(context.applicationContext, migratedState)
        return migratedState
    }

    fun save(context: Context, state: PersistedTimerState) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(
                KEY_SESSION_START_ELAPSED_REALTIME,
                if (state.isRunning || state.isPaused) state.sessionStartElapsedRealtime else 0L,
            )
            .putLong(
                KEY_ACCUMULATED_PAUSE_MILLIS,
                if (state.isRunning || state.isPaused) state.accumulatedPauseMillis.coerceAtLeast(0L) else 0L,
            )
            .putLong(
                KEY_PAUSE_STARTED_ELAPSED_REALTIME,
                if (state.isPaused) state.pauseStartedElapsedRealtime else 0L,
            )
            .putLong(KEY_FAST_DURATION_MILLIS, normalizePhaseDurationMillis(state.fastDurationMillis))
            .putLong(KEY_SLOW_DURATION_MILLIS, normalizePhaseDurationMillis(state.slowDurationMillis))
            .putInt(
                KEY_FAST_BEEP_INTERVAL_SECONDS,
                normalizeBeepIntervalSeconds(
                    state.fastPhaseBeepIntervalSeconds,
                    DEFAULT_FAST_BEEP_INTERVAL_SECONDS,
                ),
            )
            .putInt(
                KEY_SLOW_BEEP_INTERVAL_SECONDS,
                normalizeBeepIntervalSeconds(
                    state.slowPhaseBeepIntervalSeconds,
                    DEFAULT_SLOW_BEEP_INTERVAL_SECONDS,
                ),
            )
            .putInt(KEY_SET_COUNT, normalizeSetCount(state.setCount))
            .putInt(KEY_START_DELAY_SECONDS, normalizeStartDelaySeconds(state.startDelaySeconds))
            .putString(KEY_START_PHASE, state.startPhase.name)
            .putBoolean(KEY_IS_RUNNING, state.isRunning)
            .putBoolean(KEY_IS_PAUSED, state.isPaused)
            .putFloat(KEY_ANNOUNCEMENT_VOLUME, normalizeAnnouncementVolume(state.announcementVolume))
            .putBoolean(KEY_IS_VIBRATION_ENABLED, state.isVibrationEnabled)
            .remove(LEGACY_KEY_CURRENT_PHASE)
            .remove(LEGACY_KEY_TOTAL_ELAPSED_BEFORE_RUN_SECONDS)
            .remove(LEGACY_KEY_TOTAL_ELAPSED_BEFORE_RUN_MILLIS)
            .remove(LEGACY_KEY_PHASE_ELAPSED_BEFORE_RUN_SECONDS)
            .remove(LEGACY_KEY_PHASE_ELAPSED_BEFORE_RUN_MILLIS)
            .remove(LEGACY_KEY_FAST_PHASE_DURATION_SECONDS)
            .remove(LEGACY_KEY_SLOW_PHASE_DURATION_SECONDS)
            .remove(LEGACY_KEY_RUN_STARTED_AT_ELAPSED_REALTIME)
            .remove(LEGACY_KEY_PHASE_STARTED_AT_ELAPSED_REALTIME)
            .remove(LEGACY_KEY_PERSISTED_AT_ELAPSED_REALTIME)
            .remove(LEGACY_KEY_PERSISTED_AT_WALL_CLOCK_MILLIS)
            .remove(LEGACY_KEY_NOTIFICATION_PHASE)
            .remove(LEGACY_KEY_NOTIFICATION_REMAINING_SECONDS)
            .remove(LEGACY_KEY_NOTIFICATION_PHASE_ELAPSED_MILLIS)
            .remove(LEGACY_KEY_NOTIFICATION_ELAPSED_SECONDS)
            .remove(LEGACY_KEY_NOTIFICATION_TOTAL_ELAPSED_MILLIS)
            .remove(LEGACY_KEY_NOTIFICATION_IS_RUNNING)
            .remove(LEGACY_KEY_NOTIFICATION_IS_PAUSED)
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun loadLegacyState(
        prefs: android.content.SharedPreferences,
    ): PersistedTimerState? {
        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        val fastPhaseDurationSeconds = normalizePhaseDurationSeconds(
            prefs.getInt(LEGACY_KEY_FAST_PHASE_DURATION_SECONDS, DEFAULT_PHASE_DURATION_SECONDS),
        )
        val slowPhaseDurationSeconds = normalizePhaseDurationSeconds(
            prefs.getInt(LEGACY_KEY_SLOW_PHASE_DURATION_SECONDS, DEFAULT_PHASE_DURATION_SECONDS),
        )
        val isRunning = prefs.getBoolean(KEY_IS_RUNNING, false)
        val isPaused = prefs.getBoolean(KEY_IS_PAUSED, false)
        val totalElapsedBeforeRunMillis = prefs.getLongOrFallbackSeconds(
            longKey = LEGACY_KEY_TOTAL_ELAPSED_BEFORE_RUN_MILLIS,
            secondsKey = LEGACY_KEY_TOTAL_ELAPSED_BEFORE_RUN_SECONDS,
        )
        val notificationElapsedMillis = prefs.getLongOrFallbackSeconds(
            longKey = LEGACY_KEY_NOTIFICATION_TOTAL_ELAPSED_MILLIS,
            secondsKey = LEGACY_KEY_NOTIFICATION_ELAPSED_SECONDS,
        )
        val persistedAtElapsedRealtime = prefs.getLong(LEGACY_KEY_PERSISTED_AT_ELAPSED_REALTIME, 0L)
        val runStartedAtElapsedRealtime = prefs.getLong(LEGACY_KEY_RUN_STARTED_AT_ELAPSED_REALTIME, 0L)
        val activeElapsedMillis = when {
            isRunning &&
                runStartedAtElapsedRealtime > 0L &&
                nowElapsedRealtime >= runStartedAtElapsedRealtime
            -> {
                totalElapsedBeforeRunMillis + (nowElapsedRealtime - runStartedAtElapsedRealtime)
            }

            isRunning &&
                persistedAtElapsedRealtime > 0L &&
                nowElapsedRealtime >= persistedAtElapsedRealtime
            -> {
                notificationElapsedMillis + (nowElapsedRealtime - persistedAtElapsedRealtime)
            }

            isRunning -> notificationElapsedMillis
            isPaused -> totalElapsedBeforeRunMillis
            else -> 0L
        }.coerceAtLeast(0L)

        val hasSession = (isRunning || isPaused) && activeElapsedMillis >= 0L
        return PersistedTimerState(
            sessionStartElapsedRealtime = if (hasSession) {
                (nowElapsedRealtime - activeElapsedMillis).coerceAtLeast(0L)
            } else {
                0L
            },
            accumulatedPauseMillis = 0L,
            pauseStartedElapsedRealtime = if (isPaused && hasSession) nowElapsedRealtime else 0L,
            fastDurationMillis = durationMillisFromSeconds(fastPhaseDurationSeconds),
            slowDurationMillis = durationMillisFromSeconds(slowPhaseDurationSeconds),
            fastPhaseBeepIntervalSeconds = DEFAULT_FAST_BEEP_INTERVAL_SECONDS,
            slowPhaseBeepIntervalSeconds = DEFAULT_SLOW_BEEP_INTERVAL_SECONDS,
            setCount = DEFAULT_SET_COUNT,
            startDelaySeconds = DEFAULT_START_DELAY_SECONDS,
            startPhase = WalkingPhase.Fast,
            isRunning = isRunning && hasSession,
            isPaused = isPaused && hasSession,
            announcementVolume = DEFAULT_ANNOUNCEMENT_VOLUME,
            isVibrationEnabled = prefs.getBoolean(KEY_IS_VIBRATION_ENABLED, true),
        )
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
