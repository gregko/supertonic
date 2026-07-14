package io.github.gregko.supertonic.tts.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.gregko.supertonic.tts.utils.SynthesisPreferences

class TransitionGapTrackerTest {
    @Test
    fun computesPcmDurationForMonoPcm16() {
        assertEquals(
            1_000_000_000L,
            TransitionGapTracker.pcmDurationNanos(pcmBytes = 88_200L, sampleRate = 44_100)
        )
    }

    @Test
    fun estimatesPositiveGapBetweenRequests() {
        val tracker = TransitionGapTracker()
        val firstAudio = 10_000_000_000L

        assertNull(tracker.gapAtFirstAudioNanos(firstAudio))
        tracker.completeRequest(firstAudio, pcmBytes = 88_200L, sampleRate = 44_100)

        assertEquals(250_000_000L, tracker.gapAtFirstAudioNanos(11_250_000_000L))
    }

    @Test
    fun reportsOverlapAsNegativeGap() {
        val tracker = TransitionGapTracker()
        tracker.completeRequest(
            firstAudioNanos = 5_000_000_000L,
            pcmBytes = 176_400L,
            sampleRate = 44_100
        )

        assertEquals(-250_000_000L, tracker.gapAtFirstAudioNanos(6_750_000_000L))
    }

    @Test
    fun clampsIntraOpThreadPreference() {
        assertEquals(1, SynthesisPreferences.normalizeIntraOpThreads(-10))
        assertEquals(3, SynthesisPreferences.normalizeIntraOpThreads(3))
        assertEquals(8, SynthesisPreferences.normalizeIntraOpThreads(99))
    }
}
