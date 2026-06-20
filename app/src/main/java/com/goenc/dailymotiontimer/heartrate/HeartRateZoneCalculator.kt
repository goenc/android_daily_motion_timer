package com.goenc.dailymotiontimer.heartrate

object HeartRateZoneCalculator {
    fun buildRule(settings: HeartRateSettings): HeartRateRule {
        val targetLower = settings.targetLowerBpm.coerceIn(
            MIN_HEART_RATE_THRESHOLD_BPM,
            MAX_HEART_RATE_THRESHOLD_BPM - 2,
        )
        val targetUpper = settings.targetUpperBpm.coerceIn(
            targetLower + 1,
            MAX_HEART_RATE_THRESHOLD_BPM - 1,
        )
        val dangerThreshold = settings.dangerThresholdBpm.coerceIn(
            targetUpper + 1,
            MAX_HEART_RATE_THRESHOLD_BPM,
        )
        val tooHighThreshold = (targetUpper + ((dangerThreshold - targetUpper) / 2)).coerceAtLeast(targetUpper + 1)
        return HeartRateRule(
            targetLower = targetLower,
            targetUpper = targetUpper,
            tooHighThreshold = tooHighThreshold,
            dangerThreshold = dangerThreshold,
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
