package com.goenc.dailymotiontimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseAudioPlayerTest {
    @Test
    fun speechPlaybackCleanupDelayUsesDurationPlusGraceOnly() {
        assertEquals(1_200L, speechPlaybackCleanupDelayMillis(durationMillis = 1_000L))
    }

    @Test
    fun beepPlaybackCleanupDelayUsesShorterGraceThanSpeech() {
        assertEquals(220L, beepPlaybackCleanupDelayMillis(durationMillis = 120L))
        assertTrue(
            beepPlaybackCleanupDelayMillis(durationMillis = 120L) <
                speechPlaybackCleanupDelayMillis(durationMillis = 120L),
        )
    }
}
