package com.goenc.dailymotiontimer.heartrate

import com.goenc.dailymotiontimer.WalkingPhase
import kotlin.math.roundToInt

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

enum class HeartRateAlertPhaseMode(val label: String) {
    FastOnly("早く歩く"),
    SlowOnly("ゆっくり歩く"),
    Both("両方");

    fun shouldAnnounce(phase: WalkingPhase): Boolean = when (this) {
        FastOnly -> phase == WalkingPhase.Fast
        SlowOnly -> phase == WalkingPhase.Slow
        Both -> true
    }
}

data class HeartRateSettings(
    val targetLowerBpm: Int = 97,
    val targetUpperBpm: Int = 124,
    val dangerThresholdBpm: Int = 150,
    val alertsEnabled: Boolean = true,
    val alertVolume: Float = DEFAULT_HEART_RATE_ALERT_VOLUME,
    val alertPhaseMode: HeartRateAlertPhaseMode = HeartRateAlertPhaseMode.FastOnly,
    val averageWindowSeconds: Int = 5,
    val confirmSeconds: Int = 10,
    val normalCooldownSeconds: Int = 30,
    val dangerCooldownSeconds: Int = 15,
    val hysteresisBpm: Int = 3,
)

data class HeartRateRule(
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

data class HeartRateGraphSample(
    val heartRate: Int,
    val timestampMs: Long,
    val hasMeasurement: Boolean,
)

enum class HeartRateGraphBand {
    IntervalFast,
    IntervalSlow,
    NormalActive,
}

data class HeartRatePhaseSample(
    val band: HeartRateGraphBand?,
    val timestampMs: Long,
)

enum class HeartRateGraphMode {
    Interval,
    Normal,
}

data class HeartRateGraphState(
    val heartRateHistory: List<HeartRateGraphSample> = emptyList(),
    val phaseHistory: List<HeartRatePhaseSample> = emptyList(),
)

data class HeartRateUiState(
    val connectionState: HeartRateConnectionState = HeartRateConnectionState.DISCONNECTED,
    val heartRate: Int = 0,
    val averageHeartRate: Int? = null,
    val zone: HeartRateZone? = null,
    val rule: HeartRateRule = HeartRateZoneCalculator.buildRule(HeartRateSettings()),
    val selectedGraphMode: HeartRateGraphMode = HeartRateGraphMode.Interval,
    val intervalGraphState: HeartRateGraphState = HeartRateGraphState(),
    val normalGraphState: HeartRateGraphState = HeartRateGraphState(),
    val devices: List<HeartRateDevice> = emptyList(),
    val savedDevice: HeartRateDevice? = null,
    val batteryLevelPercent: Int? = null,
    val settings: HeartRateSettings = HeartRateSettings(),
    val errorMessage: String? = null,
)

internal const val MIN_HEART_RATE_THRESHOLD_BPM = 40
internal const val MAX_HEART_RATE_THRESHOLD_BPM = 220
internal const val MIN_CONFIRM_SECONDS = 1
internal const val MAX_CONFIRM_SECONDS = 90
internal const val DEFAULT_HEART_RATE_ALERT_VOLUME = 1.0f
internal const val MAX_HEART_RATE_ALERT_VOLUME = 2.0f

internal const val INTERVAL_LOW_HEART_RATE_ALERT_MESSAGE = "心拍が低いです。少し上げてください"
internal const val INTERVAL_TOO_HIGH_HEART_RATE_ALERT_MESSAGE = "上がりすぎです。ペースを落としてください"
internal const val INTERVAL_DANGER_HEART_RATE_ALERT_MESSAGE = "心拍が高すぎます。停止してください"
internal const val NORMAL_LOW_HEART_RATE_ALERT_MESSAGE = "心拍数が低いです"
internal const val NORMAL_HIGH_HEART_RATE_ALERT_MESSAGE = "心拍が高めです"
internal const val NORMAL_TOO_HIGH_HEART_RATE_ALERT_MESSAGE = "上限を超えています"
internal const val NORMAL_DANGER_HEART_RATE_ALERT_MESSAGE = "高すぎて危険です"

internal fun shouldEnableHeartRateAlerts(
    isNormalTimerRunning: Boolean,
    isIntervalTimerRunning: Boolean,
    intervalPhase: WalkingPhase?,
    normalSettings: HeartRateSettings,
    intervalSettings: HeartRateSettings,
): Boolean {
    if (isNormalTimerRunning) {
        return normalSettings.alertsEnabled
    }
    if (!isIntervalTimerRunning || intervalPhase == null) {
        return false
    }
    return intervalSettings.alertsEnabled && intervalSettings.alertPhaseMode.shouldAnnounce(intervalPhase)
}

internal fun resolveHeartRateAlertSpeechMessage(
    alertMessage: String,
    isNormalTimerActive: Boolean,
): String? {
    if (!isNormalTimerActive) {
        return alertMessage
    }
    return when (alertMessage) {
        INTERVAL_LOW_HEART_RATE_ALERT_MESSAGE -> NORMAL_LOW_HEART_RATE_ALERT_MESSAGE
        HeartRateZone.HIGH.label,
        NORMAL_HIGH_HEART_RATE_ALERT_MESSAGE -> NORMAL_HIGH_HEART_RATE_ALERT_MESSAGE
        INTERVAL_TOO_HIGH_HEART_RATE_ALERT_MESSAGE -> NORMAL_TOO_HIGH_HEART_RATE_ALERT_MESSAGE
        NORMAL_TOO_HIGH_HEART_RATE_ALERT_MESSAGE -> NORMAL_TOO_HIGH_HEART_RATE_ALERT_MESSAGE
        INTERVAL_DANGER_HEART_RATE_ALERT_MESSAGE -> NORMAL_DANGER_HEART_RATE_ALERT_MESSAGE
        NORMAL_DANGER_HEART_RATE_ALERT_MESSAGE -> NORMAL_DANGER_HEART_RATE_ALERT_MESSAGE
        NORMAL_LOW_HEART_RATE_ALERT_MESSAGE -> NORMAL_LOW_HEART_RATE_ALERT_MESSAGE
        else -> alertMessage
    }
}

internal fun resolveHeartRateReadingSpeechMessage(heartRate: Int): String? {
    if (heartRate <= 0) {
        return null
    }
    return "心拍、$heartRate"
}

internal fun normalizeHeartRateAlertVolume(volume: Float): Float {
    return if (volume.isNaN() || volume.isInfinite()) {
        DEFAULT_HEART_RATE_ALERT_VOLUME
    } else {
        volume.coerceIn(0.0f, MAX_HEART_RATE_ALERT_VOLUME)
    }
}

internal fun heartRateAlertVolumeToDisplayPercent(volume: Float): Int {
    return (normalizeHeartRateAlertVolume(volume) * 100f).roundToInt().coerceIn(0, 200)
}

internal fun heartRateAlertVolumeFromDisplayPercent(percent: Int): Float {
    return normalizeHeartRateAlertVolume(percent.coerceIn(0, 200) / 100f)
}

internal fun heartRateAlertVolumeToSpeechVolume(volume: Float): Float {
    return normalizeHeartRateAlertVolume(volume).coerceAtMost(1.0f)
}
