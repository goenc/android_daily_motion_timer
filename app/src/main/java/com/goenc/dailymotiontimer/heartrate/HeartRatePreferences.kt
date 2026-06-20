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

    fun loadSettings(): HeartRateSettings = HeartRateSettings(
        age = preferences.getInt(KEY_AGE, HeartRateSettings().age).coerceIn(1, 120),
        alertsEnabled = preferences.getBoolean(KEY_ALERTS_ENABLED, true),
    )

    fun saveSettings(settings: HeartRateSettings) {
        preferences.edit()
            .putInt(KEY_AGE, settings.age.coerceIn(1, 120))
            .putBoolean(KEY_ALERTS_ENABLED, settings.alertsEnabled)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "heart_rate_monitor"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_DEVICE_ADDRESS = "device_address"
        const val KEY_AGE = "age"
        const val KEY_ALERTS_ENABLED = "alerts_enabled"
    }
}
