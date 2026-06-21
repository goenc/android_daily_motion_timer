package com.goenc.dailymotiontimer.heartrate

import kotlin.math.roundToInt

class HeartRateAlertEngine(
    initialSettings: HeartRateSettings,
    private val onSnapshot: (averageHeartRate: Int?, zone: HeartRateZone?, rule: HeartRateRule) -> Unit,
    private val onAlert: (String) -> Unit,
) {
    private data class Sample(val timestampMs: Long, val heartRate: Int)

    private var settings = initialSettings
    private var rule = HeartRateZoneCalculator.buildRule(initialSettings)
    private val samples = ArrayDeque<Sample>()
    private var currentZone: HeartRateZone? = null
    private var pendingZone: HeartRateZone? = null
    private var pendingSinceMs: Long? = null
    private var confirmedZone: HeartRateZone? = null
    private var lastAlertAtMs: Long? = null

    fun updateSettings(newSettings: HeartRateSettings) {
        settings = newSettings
        rule = HeartRateZoneCalculator.buildRule(newSettings)
        reset()
    }

    fun onHeartRateSample(
        heartRate: Int,
        timestampMs: Long,
        alertsSuppressed: Boolean = false,
    ) {
        samples.addLast(Sample(timestampMs, heartRate))
        val cutoff = timestampMs - settings.averageWindowSeconds * 1_000L
        while (samples.isNotEmpty() && samples.first().timestampMs < cutoff) samples.removeFirst()
        val average = samples.map { it.heartRate }.average().roundToInt()
        val evaluatedZone = HeartRateZoneCalculator.calculateZone(settings, average, currentZone)
        currentZone = evaluatedZone
        if (alertsSuppressed) {
            clearPendingAlertState()
            onSnapshot(average, evaluatedZone, rule)
            return
        }
        confirmZone(evaluatedZone, average, timestampMs)
    }

    fun reset() {
        samples.clear()
        currentZone = null
        clearPendingAlertState()
        lastAlertAtMs = null
        onSnapshot(null, null, rule)
    }

    private fun clearPendingAlertState() {
        pendingZone = null
        pendingSinceMs = null
        confirmedZone = null
    }

    private fun confirmZone(zone: HeartRateZone, average: Int, timestampMs: Long) {
        if (pendingZone != zone) {
            pendingZone = zone
            pendingSinceMs = timestampMs
            onSnapshot(average, zone, rule)
            return
        }
        val confirmed = timestampMs - (pendingSinceMs ?: timestampMs) >= settings.confirmSeconds * 1_000L
        if (confirmed) {
            val zoneChanged = confirmedZone != zone
            confirmedZone = zone
            notifyIfNeeded(zone, timestampMs, zoneChanged)
        }
        onSnapshot(average, confirmedZone ?: zone, rule)
    }

    private fun notifyIfNeeded(zone: HeartRateZone, timestampMs: Long, force: Boolean) {
        if (!settings.alertsEnabled || zone == HeartRateZone.TARGET) return
        val cooldown = if (zone == HeartRateZone.DANGER) {
            settings.dangerCooldownSeconds * 1_000L
        } else {
            settings.normalCooldownSeconds * 1_000L
        }
        val lastAlert = lastAlertAtMs
        if (!force && lastAlert != null && timestampMs - lastAlert < cooldown) return
        onAlert(
            when (zone) {
                HeartRateZone.LOW -> INTERVAL_LOW_HEART_RATE_ALERT_MESSAGE
                HeartRateZone.TARGET -> "範囲内です"
                HeartRateZone.HIGH -> "心拍が高めです"
                HeartRateZone.TOO_HIGH -> INTERVAL_TOO_HIGH_HEART_RATE_ALERT_MESSAGE
                HeartRateZone.DANGER -> INTERVAL_DANGER_HEART_RATE_ALERT_MESSAGE
            },
        )
        lastAlertAtMs = timestampMs
    }
}
