package com.goenc.dailymotiontimer

import com.goenc.dailymotiontimer.heartrate.HeartRateAlertEngine
import com.goenc.dailymotiontimer.heartrate.HeartRateParser
import com.goenc.dailymotiontimer.heartrate.HeartRateSettings
import com.goenc.dailymotiontimer.heartrate.HeartRateZone
import com.goenc.dailymotiontimer.heartrate.HeartRateZoneCalculator
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
}
