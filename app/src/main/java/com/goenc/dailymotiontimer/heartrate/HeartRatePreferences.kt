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

    fun loadSettings(): HeartRateSettings {
        val targetLower = preferences.getInt(
            KEY_TARGET_LOWER_BPM,
            HeartRateSettings().targetLowerBpm,
        ).coerceIn(MIN_HEART_RATE_THRESHOLD_BPM, MAX_HEART_RATE_THRESHOLD_BPM - 2)
        val targetUpper = preferences.getInt(
            KEY_TARGET_UPPER_BPM,
            HeartRateSettings().targetUpperBpm,
        ).coerceIn(targetLower + 1, MAX_HEART_RATE_THRESHOLD_BPM - 1)
        val dangerThreshold = preferences.getInt(
            KEY_DANGER_THRESHOLD_BPM,
            HeartRateSettings().dangerThresholdBpm,
        ).coerceIn(targetUpper + 1, MAX_HEART_RATE_THRESHOLD_BPM)
        return HeartRateSettings(
            targetLowerBpm = targetLower,
            targetUpperBpm = targetUpper,
            dangerThresholdBpm = dangerThreshold,
            alertsEnabled = preferences.getBoolean(KEY_ALERTS_ENABLED, true),
            alertPhaseMode = preferences.getString(
                KEY_ALERT_PHASE_MODE,
                HeartRateSettings().alertPhaseMode.name,
            )?.let {
                HeartRateAlertPhaseMode.entries.firstOrNull { mode -> mode.name == it }
            } ?: HeartRateSettings().alertPhaseMode,
        )
    }

    fun saveSettings(settings: HeartRateSettings) {
        preferences.edit()
            .putInt(KEY_TARGET_LOWER_BPM, settings.targetLowerBpm)
            .putInt(KEY_TARGET_UPPER_BPM, settings.targetUpperBpm)
            .putInt(KEY_DANGER_THRESHOLD_BPM, settings.dangerThresholdBpm)
            .putBoolean(KEY_ALERTS_ENABLED, settings.alertsEnabled)
            .putString(KEY_ALERT_PHASE_MODE, settings.alertPhaseMode.name)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "heart_rate_monitor"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_DEVICE_ADDRESS = "device_address"
        const val KEY_TARGET_LOWER_BPM = "target_lower_bpm"
        const val KEY_TARGET_UPPER_BPM = "target_upper_bpm"
        const val KEY_DANGER_THRESHOLD_BPM = "danger_threshold_bpm"
        const val KEY_ALERTS_ENABLED = "alerts_enabled"
        const val KEY_ALERT_PHASE_MODE = "alert_phase_mode"
    }
}
