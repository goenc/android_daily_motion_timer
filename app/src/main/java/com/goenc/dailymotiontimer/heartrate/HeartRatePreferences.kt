package com.goenc.dailymotiontimer.heartrate

import android.content.Context

class HeartRatePreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadDevice(): HeartRateDevice? {
        val address = preferences.getString(KEY_DEVICE_ADDRESS, null) ?: return null
        return HeartRateDevice(
            name = preferences.getString(KEY_DEVICE_NAME, null) ?: "名前なし",
            address = address,
            supportsHeartRate = true,
        )
    }

    fun saveDevice(device: HeartRateDevice) {
        preferences.edit()
            .putString(KEY_DEVICE_NAME, device.name)
            .putString(KEY_DEVICE_ADDRESS, device.address)
            .apply()
    }

    fun clearDevice() {
        preferences.edit().remove(KEY_DEVICE_NAME).remove(KEY_DEVICE_ADDRESS).apply()
    }

    fun loadSelectedMode(): HeartRateGraphMode {
        val rawMode = preferences.getString(KEY_SELECTED_MODE, HeartRateGraphMode.Interval.name)
        return HeartRateGraphMode.entries.firstOrNull { it.name == rawMode } ?: HeartRateGraphMode.Interval
    }

    fun saveSelectedMode(mode: HeartRateGraphMode) {
        preferences.edit()
            .putString(KEY_SELECTED_MODE, mode.name)
            .apply()
    }

    fun loadSettings(mode: HeartRateGraphMode): HeartRateSettings {
        val targetLowerKey = scopedKey(KEY_TARGET_LOWER_BPM, mode)
        val targetUpperKey = scopedKey(KEY_TARGET_UPPER_BPM, mode)
        val dangerThresholdKey = scopedKey(KEY_DANGER_THRESHOLD_BPM, mode)
        val alertsEnabledKey = scopedKey(KEY_ALERTS_ENABLED, mode)
        val confirmSecondsKey = scopedKey(KEY_CONFIRM_SECONDS, mode)
        val alertPhaseModeKey = scopedKey(KEY_ALERT_PHASE_MODE, mode)
        val alertVolumeKey = scopedKey(KEY_ALERT_VOLUME, mode)
        val normalReadingIntervalKey = scopedKey(KEY_NORMAL_READING_INTERVAL_SECONDS, mode)
        val targetLower = preferences.getInt(
            targetLowerKey,
            HeartRateSettings().targetLowerBpm,
        ).takeIf { preferences.contains(targetLowerKey) }
            ?: legacyInt(KEY_TARGET_LOWER_BPM, HeartRateSettings().targetLowerBpm)
        val normalizedTargetLower = targetLower.coerceIn(MIN_HEART_RATE_THRESHOLD_BPM, MAX_HEART_RATE_THRESHOLD_BPM - 2)
        val targetUpper = preferences.getInt(
            targetUpperKey,
            HeartRateSettings().targetUpperBpm,
        ).takeIf { preferences.contains(targetUpperKey) }
            ?: legacyInt(KEY_TARGET_UPPER_BPM, HeartRateSettings().targetUpperBpm)
        val normalizedTargetUpper = targetUpper.coerceIn(normalizedTargetLower + 1, MAX_HEART_RATE_THRESHOLD_BPM - 1)
        val dangerThreshold = preferences.getInt(
            dangerThresholdKey,
            HeartRateSettings().dangerThresholdBpm,
        ).takeIf { preferences.contains(dangerThresholdKey) }
            ?: legacyInt(KEY_DANGER_THRESHOLD_BPM, HeartRateSettings().dangerThresholdBpm)
        val normalizedDangerThreshold = dangerThreshold.coerceIn(normalizedTargetUpper + 1, MAX_HEART_RATE_THRESHOLD_BPM)
        val rawAlertPhaseMode = if (preferences.contains(alertPhaseModeKey)) {
            preferences.getString(alertPhaseModeKey, HeartRateSettings().alertPhaseMode.name)
        } else {
            preferences.getString(KEY_ALERT_PHASE_MODE, HeartRateSettings().alertPhaseMode.name)
        }
        return HeartRateSettings(
            targetLowerBpm = normalizedTargetLower,
            targetUpperBpm = normalizedTargetUpper,
            dangerThresholdBpm = normalizedDangerThreshold,
            alertsEnabled = preferences.getBoolean(
                alertsEnabledKey,
                legacyBoolean(KEY_ALERTS_ENABLED, true),
            ),
            confirmSeconds = preferences.getInt(
                confirmSecondsKey,
                HeartRateSettings().confirmSeconds,
            ).takeIf { preferences.contains(confirmSecondsKey) }
                ?: legacyInt(KEY_CONFIRM_SECONDS, HeartRateSettings().confirmSeconds)
                    .coerceIn(MIN_CONFIRM_SECONDS, MAX_CONFIRM_SECONDS),
            alertPhaseMode = rawAlertPhaseMode?.let {
                HeartRateAlertPhaseMode.entries.firstOrNull { mode -> mode.name == it }
            } ?: HeartRateSettings().alertPhaseMode,
            alertVolume = normalizeHeartRateAlertVolume(
                if (preferences.contains(alertVolumeKey)) {
                    preferences.getFloat(alertVolumeKey, HeartRateSettings().alertVolume)
                } else {
                    preferences.getFloat(KEY_ALERT_VOLUME, HeartRateSettings().alertVolume)
                },
            ),
            normalReadingIntervalSeconds = normalizeHeartRateReadingIntervalSeconds(
                if (preferences.contains(normalReadingIntervalKey)) {
                    preferences.getInt(
                        normalReadingIntervalKey,
                        HeartRateSettings().normalReadingIntervalSeconds,
                    )
                } else {
                    preferences.getInt(
                        KEY_NORMAL_READING_INTERVAL_SECONDS,
                        HeartRateSettings().normalReadingIntervalSeconds,
                    )
                },
            ),
        )
    }

    fun saveSettings(mode: HeartRateGraphMode, settings: HeartRateSettings) {
        preferences.edit()
            .putInt(scopedKey(KEY_TARGET_LOWER_BPM, mode), settings.targetLowerBpm)
            .putInt(scopedKey(KEY_TARGET_UPPER_BPM, mode), settings.targetUpperBpm)
            .putInt(scopedKey(KEY_DANGER_THRESHOLD_BPM, mode), settings.dangerThresholdBpm)
            .putBoolean(scopedKey(KEY_ALERTS_ENABLED, mode), settings.alertsEnabled)
            .putInt(scopedKey(KEY_CONFIRM_SECONDS, mode), settings.confirmSeconds)
            .putString(scopedKey(KEY_ALERT_PHASE_MODE, mode), settings.alertPhaseMode.name)
            .putFloat(scopedKey(KEY_ALERT_VOLUME, mode), normalizeHeartRateAlertVolume(settings.alertVolume))
            .putInt(
                scopedKey(KEY_NORMAL_READING_INTERVAL_SECONDS, mode),
                normalizeHeartRateReadingIntervalSeconds(settings.normalReadingIntervalSeconds),
            )
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "heart_rate_monitor"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_DEVICE_ADDRESS = "device_address"
        const val KEY_SELECTED_MODE = "selected_mode"
        const val KEY_TARGET_LOWER_BPM = "target_lower_bpm"
        const val KEY_TARGET_UPPER_BPM = "target_upper_bpm"
        const val KEY_DANGER_THRESHOLD_BPM = "danger_threshold_bpm"
        const val KEY_ALERTS_ENABLED = "alerts_enabled"
        const val KEY_CONFIRM_SECONDS = "confirm_seconds"
        const val KEY_ALERT_PHASE_MODE = "alert_phase_mode"
        const val KEY_ALERT_VOLUME = "alert_volume"
        const val KEY_NORMAL_READING_INTERVAL_SECONDS = "normal_reading_interval_seconds"
    }

    private fun scopedKey(baseKey: String, mode: HeartRateGraphMode): String {
        return "${mode.name.lowercase()}_$baseKey"
    }

    private fun legacyInt(key: String, defaultValue: Int): Int {
        return preferences.getInt(key, defaultValue)
    }

    private fun legacyBoolean(key: String, defaultValue: Boolean): Boolean {
        return preferences.getBoolean(key, defaultValue)
    }
}
