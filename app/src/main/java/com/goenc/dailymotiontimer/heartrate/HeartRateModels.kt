package com.goenc.dailymotiontimer.heartrate

enum class HeartRateConnectionState(val label: String) {
    DISCONNECTED("未接続"),
    SCANNING("検索中"),
    CONNECTING("接続中"),
    CONNECTED("接続済み"),
    ERROR("エラー"),
}

enum class HeartRateZone(val label: String) {
    LOW("低い"),
    TARGET("範囲内"),
    HIGH("高め"),
    TOO_HIGH("上がりすぎ"),
    DANGER("危険"),
}

data class HeartRateSettings(
    val age: Int = 45,
    val alertsEnabled: Boolean = true,
    val averageWindowSeconds: Int = 5,
    val confirmSeconds: Int = 10,
    val normalCooldownSeconds: Int = 30,
    val dangerCooldownSeconds: Int = 15,
    val hysteresisBpm: Int = 3,
)

data class HeartRateRule(
    val estimatedMaxHeartRate: Int,
    val targetLower: Int,
    val targetUpper: Int,
    val tooHighThreshold: Int,
    val dangerThreshold: Int,
)

data class HeartRateDevice(
    val name: String,
    val address: String,
    val rssi: Int = 0,
    val supportsHeartRate: Boolean = false,
)

data class HeartRateUiState(
    val connectionState: HeartRateConnectionState = HeartRateConnectionState.DISCONNECTED,
    val heartRate: Int = 0,
    val averageHeartRate: Int? = null,
    val zone: HeartRateZone? = null,
    val rule: HeartRateRule = HeartRateZoneCalculator.buildRule(HeartRateSettings()),
    val devices: List<HeartRateDevice> = emptyList(),
    val savedDevice: HeartRateDevice? = null,
    val settings: HeartRateSettings = HeartRateSettings(),
    val errorMessage: String? = null,
)
