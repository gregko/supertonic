package io.github.gregko.supertonic.tts.service

/**
 * Estimates the gap between sequential TTS requests from the first submitted PCM byte and the
 * duration of the submitted audio. This deliberately uses monotonic timestamps so wall-clock
 * changes cannot corrupt a benchmark run.
 *
 * Android does not expose the AudioTrack playback head through [android.speech.tts.SynthesisCallback],
 * so this is an estimate rather than an audible-onset measurement. It is still useful for A/B
 * comparisons made with the same client and device.
 */
internal class TransitionGapTracker {
    private var previousEstimatedPlaybackEndNanos: Long? = null

    fun gapAtFirstAudioNanos(firstAudioNanos: Long): Long? {
        return previousEstimatedPlaybackEndNanos?.let { firstAudioNanos - it }
    }

    fun completeRequest(firstAudioNanos: Long, pcmBytes: Long, sampleRate: Int) {
        if (firstAudioNanos <= 0L || pcmBytes <= 0L || sampleRate <= 0) {
            return
        }
        previousEstimatedPlaybackEndNanos = firstAudioNanos + pcmDurationNanos(pcmBytes, sampleRate)
    }

    fun reset() {
        previousEstimatedPlaybackEndNanos = null
    }

    companion object {
        private const val PCM_16_MONO_BYTES_PER_SAMPLE = 2L
        private const val NANOS_PER_SECOND = 1_000_000_000L

        fun pcmDurationNanos(pcmBytes: Long, sampleRate: Int): Long {
            if (pcmBytes <= 0L || sampleRate <= 0) return 0L
            return (pcmBytes * NANOS_PER_SECOND) / (sampleRate * PCM_16_MONO_BYTES_PER_SAMPLE)
        }

        fun nanosToMillis(nanos: Long): Double = nanos / 1_000_000.0
    }
}
