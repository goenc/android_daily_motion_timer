package com.goenc.dailymotiontimer

import com.goenc.dailymotiontimer.heartrate.HeartRateAlertPhaseMode
import com.goenc.dailymotiontimer.heartrate.HeartRateAlertEngine
import com.goenc.dailymotiontimer.heartrate.HeartRateParser
import com.goenc.dailymotiontimer.heartrate.HeartRateSettings
import com.goenc.dailymotiontimer.heartrate.HeartRateZone
import com.goenc.dailymotiontimer.heartrate.HeartRateZoneCalculator
import com.goenc.dailymotiontimer.heartrate.INTERVAL_LOW_HEART_RATE_ALERT_MESSAGE
import com.goenc.dailymotiontimer.heartrate.NORMAL_LOW_HEART_RATE_ALERT_MESSAGE
import com.goenc.dailymotiontimer.heartrate.resolveHeartRateAlertSpeechMessage
import com.goenc.dailymotiontimer.heartrate.shouldEnableHeartRateAlerts
import com.goenc.dailymotiontimer.WalkingPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateTest {
    @Test
    fun walkRuleMatchesConfiguredThresholds() {
        val rule = HeartRateZoneCalculator.buildRule(
            HeartRateSettings(
                targetLowerBpm = 97,
                targetUpperBpm = 124,
                dangerThresholdBpm = 150,
            ),
        )

        assertEquals(97, rule.targetLower)
        assertEquals(124, rule.targetUpper)
        assertEquals(137, rule.tooHighThreshold)
        assertEquals(150, rule.dangerThreshold)
    }

    @Test
    fun targetZoneUsesHysteresisBeforeChangingToHigh() {
        val settings = HeartRateSettings(
            targetLowerBpm = 97,
            targetUpperBpm = 124,
            dangerThresholdBpm = 150,
        )

        assertEquals(
            HeartRateZone.TARGET,
            HeartRateZoneCalculator.calculateZone(settings, 126, HeartRateZone.TARGET),
        )
        assertEquals(
            HeartRateZone.HIGH,
            HeartRateZoneCalculator.calculateZone(settings, 128, HeartRateZone.TARGET),
        )
    }

    @Test
    fun parserSupportsEightAndSixteenBitMeasurements() {
        assertEquals(82, HeartRateParser.parse(byteArrayOf(0x00, 82)))
        assertEquals(300, HeartRateParser.parse(byteArrayOf(0x01, 0x2C, 0x01)))
        assertNull(HeartRateParser.parse(byteArrayOf(0x00)))
    }

    @Test
    fun sustainedOutOfRangeHeartRateTriggersSpeechMessage() {
        val alerts = mutableListOf<String>()
        val engine = HeartRateAlertEngine(
            initialSettings = HeartRateSettings(
                targetLowerBpm = 97,
                targetUpperBpm = 124,
                dangerThresholdBpm = 150,
                confirmSeconds = 10,
            ),
            onSnapshot = { _, _, _ -> },
            onAlert = alerts::add,
        )

        engine.onHeartRateSample(140, timestampMs = 1_000L)
        engine.onHeartRateSample(140, timestampMs = 11_000L)

        assertEquals(listOf("上がりすぎです。ペースを落としてください"), alerts)
    }

    @Test
    fun suppressedPhaseDoesNotCarryOverConfirmSeconds() {
        val alerts = mutableListOf<String>()
        val engine = HeartRateAlertEngine(
            initialSettings = HeartRateSettings(
                targetLowerBpm = 97,
                targetUpperBpm = 124,
                dangerThresholdBpm = 150,
                confirmSeconds = 30,
            ),
            onSnapshot = { _, _, _ -> },
            onAlert = alerts::add,
        )

        engine.onHeartRateSample(80, timestampMs = 0L, alertsSuppressed = true)
        engine.onHeartRateSample(80, timestampMs = 15_000L, alertsSuppressed = true)
        engine.onHeartRateSample(80, timestampMs = 30_000L, alertsSuppressed = false)
        engine.onHeartRateSample(80, timestampMs = 45_000L, alertsSuppressed = false)
        assertEquals(emptyList<String>(), alerts)

        engine.onHeartRateSample(80, timestampMs = 60_000L, alertsSuppressed = false)
        assertEquals(listOf(INTERVAL_LOW_HEART_RATE_ALERT_MESSAGE), alerts)
    }

    @Test
    fun normalTimerEnablesLowHeartRateAlertsWhenEnabled() {
        val settings = HeartRateSettings(alertsEnabled = true)

        assertEquals(
            true,
            shouldEnableHeartRateAlerts(
                isNormalTimerRunning = true,
                isIntervalTimerRunning = false,
                intervalPhase = null,
                normalSettings = settings,
                intervalSettings = settings,
            ),
        )
    }

    @Test
    fun normalTimerSuppressesNonLowAlertSpeech() {
        assertEquals(
            NORMAL_LOW_HEART_RATE_ALERT_MESSAGE,
            resolveHeartRateAlertSpeechMessage(
                alertMessage = INTERVAL_LOW_HEART_RATE_ALERT_MESSAGE,
                isNormalTimerActive = true,
            ),
        )
        assertNull(
            resolveHeartRateAlertSpeechMessage(
                alertMessage = "上がりすぎです。ペースを落としてください",
                isNormalTimerActive = true,
            ),
        )
    }

    @Test
    fun intervalTimerStillUsesOriginalLowHeartRateMessage() {
        assertEquals(
            INTERVAL_LOW_HEART_RATE_ALERT_MESSAGE,
            resolveHeartRateAlertSpeechMessage(
                alertMessage = INTERVAL_LOW_HEART_RATE_ALERT_MESSAGE,
                isNormalTimerActive = false,
            ),
        )
    }

    @Test
    fun intervalTimerRespectsAlertPhaseMode() {
        val settings = HeartRateSettings(
            alertsEnabled = true,
            alertPhaseMode = HeartRateAlertPhaseMode.FastOnly,
        )

        assertEquals(
            true,
            shouldEnableHeartRateAlerts(
                isNormalTimerRunning = false,
                isIntervalTimerRunning = true,
                intervalPhase = WalkingPhase.Fast,
                normalSettings = settings,
                intervalSettings = settings,
            ),
        )
        assertEquals(
            false,
            shouldEnableHeartRateAlerts(
                isNormalTimerRunning = false,
                isIntervalTimerRunning = true,
                intervalPhase = WalkingPhase.Slow,
                normalSettings = settings,
                intervalSettings = settings,
            ),
        )
    }
}
