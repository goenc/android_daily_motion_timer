package com.goenc.dailymotiontimer.heartrate

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateGraphTest {
    @Test
    fun `時間が空いた実測点は別の線分になる`() {
        val samples = listOf(
            measuredSample(80, 1_000L),
            measuredSample(82, 2_000L),
            measuredSample(90, 20_000L),
            measuredSample(92, 21_000L),
        )

        val segments = buildMeasuredSegments(samples)

        assertEquals(listOf(listOf(80, 82), listOf(90, 92)), segments.map { segment ->
            segment.map { it.heartRate }
        })
    }

    @Test
    fun `欠損点の前後は別の線分になる`() {
        val samples = listOf(
            measuredSample(80, 1_000L),
            HeartRateGraphSample(heartRate = 0, timestampMs = 2_000L, hasMeasurement = false),
            measuredSample(90, 3_000L),
        )

        val segments = buildMeasuredSegments(samples)

        assertEquals(listOf(listOf(80), listOf(90)), segments.map { segment ->
            segment.map { it.heartRate }
        })
    }

    private fun measuredSample(heartRate: Int, timestampMs: Long) = HeartRateGraphSample(
        heartRate = heartRate,
        timestampMs = timestampMs,
        hasMeasurement = true,
    )
}
