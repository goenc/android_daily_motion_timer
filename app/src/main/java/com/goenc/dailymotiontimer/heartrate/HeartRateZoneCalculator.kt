package com.goenc.dailymotiontimer.heartrate

import kotlin.math.roundToInt

object HeartRateZoneCalculator {
    fun buildRule(settings: HeartRateSettings): HeartRateRule {
        val estimatedMax = (208f - 0.7f * settings.age).roundToInt()
        val targetLower = (estimatedMax * 0.55f).roundToInt()
        val targetUpper = (estimatedMax * 0.70f).roundToInt()
        return HeartRateRule(
            estimatedMaxHeartRate = estimatedMax,
            targetLower = targetLower,
            targetUpper = targetUpper,
            tooHighThreshold = (estimatedMax * 0.75f).roundToInt(),
            dangerThreshold = (estimatedMax * 0.85f).roundToInt(),
        )
    }

    fun calculateZone(
        settings: HeartRateSettings,
        averageHeartRate: Int,
        previousZone: HeartRateZone?,
    ): HeartRateZone {
        val rule = buildRule(settings)
        val hysteresis = settings.hysteresisBpm
        return when (previousZone) {
            HeartRateZone.LOW -> if (averageHeartRate < rule.targetLower + hysteresis) {
                HeartRateZone.LOW
            } else {
                calculateRawZone(rule, averageHeartRate)
            }
            HeartRateZone.TARGET -> when {
                averageHeartRate < rule.targetLower - hysteresis -> HeartRateZone.LOW
                averageHeartRate > rule.targetUpper + hysteresis -> calculateRawZone(rule, averageHeartRate)
                else -> HeartRateZone.TARGET
            }
            HeartRateZone.HIGH -> when {
                averageHeartRate <= rule.targetUpper - hysteresis -> calculateRawZone(rule, averageHeartRate)
                averageHeartRate >= rule.tooHighThreshold + hysteresis -> calculateRawZone(rule, averageHeartRate)
                else -> HeartRateZone.HIGH
            }
            HeartRateZone.TOO_HIGH -> when {
                averageHeartRate <= rule.tooHighThreshold - hysteresis -> calculateRawZone(rule, averageHeartRate)
                averageHeartRate >= rule.dangerThreshold + hysteresis -> HeartRateZone.DANGER
                else -> HeartRateZone.TOO_HIGH
            }
            HeartRateZone.DANGER -> if (averageHeartRate <= rule.dangerThreshold - hysteresis) {
                calculateRawZone(rule, averageHeartRate)
            } else {
                HeartRateZone.DANGER
            }
            null -> calculateRawZone(rule, averageHeartRate)
        }
    }

    private fun calculateRawZone(rule: HeartRateRule, heartRate: Int): HeartRateZone = when {
        heartRate < rule.targetLower -> HeartRateZone.LOW
        heartRate <= rule.targetUpper -> HeartRateZone.TARGET
        heartRate < rule.tooHighThreshold -> HeartRateZone.HIGH
        heartRate < rule.dangerThreshold -> HeartRateZone.TOO_HIGH
        else -> HeartRateZone.DANGER
    }
}
