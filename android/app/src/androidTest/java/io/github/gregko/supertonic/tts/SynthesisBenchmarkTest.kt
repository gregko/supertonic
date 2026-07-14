package io.github.gregko.supertonic.tts

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gregko.supertonic.tts.utils.AssetInstaller
import io.github.gregko.supertonic.tts.utils.SynthesisPreferences
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Explicit on-device benchmark; run this class directly rather than as a release gate.
 * Native TTS_METRIC lines provide the phase breakdown, while TTS_BENCH lines make the
 * diffusion-step/thread matrix easy to aggregate from logcat.
 */
@RunWith(AndroidJUnit4::class)
class SynthesisBenchmarkTest {
    @After
    fun releaseEngine() {
        SupertonicTTS.release()
    }

    @Test
    fun benchmarkDiffusionStepsAndIntraOpThreads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preparedModel = AssetInstaller.prepareModel(context, LANGUAGE)
        assertNotNull("Bundled model assets are required", preparedModel)
        val model = requireNotNull(preparedModel)
        val stylePath = AssetInstaller.resolveStyleFile(context, VOICE_FILE, LANGUAGE).absolutePath

        for (threads in THREAD_COUNTS) {
            SupertonicTTS.release()
            assertTrue(
                "Engine initialization failed for threads=$threads",
                SupertonicTTS.initialize(model.modelPath, model.libPath, threads)
            )
            SupertonicTTS.setCancelled(false)

            // Warm ONNX kernels and populate the parsed-style cache before measured runs.
            assertNotNull(
                SupertonicTTS.generateAudio(
                    BENCHMARK_TEXT,
                    LANGUAGE,
                    stylePath,
                    steps = 3,
                    temperature = SynthesisPreferences.DEFAULT_TEMPERATURE
                )
            )

            for (steps in DIFFUSION_STEPS) {
                repeat(REPETITIONS) { repetition ->
                    val startNanos = SystemClock.elapsedRealtimeNanos()
                    val pcm = SupertonicTTS.generateAudio(
                        BENCHMARK_TEXT,
                        LANGUAGE,
                        stylePath,
                        steps = steps,
                        temperature = SynthesisPreferences.DEFAULT_TEMPERATURE
                    )
                    val elapsedNanos = SystemClock.elapsedRealtimeNanos() - startNanos
                    assertNotNull("Synthesis failed for threads=$threads steps=$steps", pcm)

                    val pcmBytes = requireNotNull(pcm).size
                    val sampleRate = SupertonicTTS.getAudioSampleRate()
                    val audioMillis = pcmBytes * 500.0 / sampleRate
                    val elapsedMillis = elapsedNanos / 1_000_000.0
                    val endToEndRtf = if (audioMillis > 0.0) elapsedMillis / audioMillis else 0.0
                    Log.i(
                        TAG,
                        String.format(
                            Locale.ROOT,
                            "TTS_BENCH threads=%d steps=%d repetition=%d elapsed_ms=%.3f " +
                                "audio_ms=%.3f end_to_end_rtf=%.4f pcm_bytes=%d",
                            threads,
                            steps,
                            repetition,
                            elapsedMillis,
                            audioMillis,
                            endToEndRtf,
                            pcmBytes
                        )
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "SupertonicBenchmark"
        private const val LANGUAGE = "en"
        private const val VOICE_FILE = "M1.json"
        private const val REPETITIONS = 2
        private const val BENCHMARK_TEXT =
            "The quick brown fox crosses the quiet valley while the morning train approaches."

        private val DIFFUSION_STEPS = intArrayOf(2, 3, 5)
        private val THREAD_COUNTS = intArrayOf(1, 2, 3, 4, 5, 6, 8)
    }
}
