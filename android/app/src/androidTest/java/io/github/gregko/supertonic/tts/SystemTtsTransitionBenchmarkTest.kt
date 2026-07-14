package io.github.gregko.supertonic.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gregko.supertonic.tts.utils.SynthesisPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the real Android TextToSpeech queue and SynthesisCallback backpressure path. */
@RunWith(AndroidJUnit4::class)
class SystemTtsTransitionBenchmarkTest {
    @Test
    fun queuesTwoSentencesThroughAndroidTtsFramework() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SynthesisPreferences.getPrefs(context)
            .edit()
            .putInt("diffusion_steps", 5)
            .putInt(
                SynthesisPreferences.KEY_INTRA_OP_THREADS,
                SynthesisPreferences.DEFAULT_INTRA_OP_THREADS
            )
            .commit()

        val initialized = CountDownLatch(1)
        val initStatus = AtomicReference(TextToSpeech.ERROR)
        val tts = TextToSpeech(
            context,
            { status ->
                initStatus.set(status)
                initialized.countDown()
            },
            context.packageName
        )

        try {
            assertTrue("TTS engine initialization timed out", initialized.await(30, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, initStatus.get())
            assertTrue(tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE)

            val completed = CountDownLatch(2)
            val error = AtomicReference<String?>(null)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    Log.i(TAG, "TTS_CLIENT_EVENT event=start utterance=$utteranceId")
                }

                override fun onDone(utteranceId: String) {
                    Log.i(TAG, "TTS_CLIENT_EVENT event=done utterance=$utteranceId")
                    completed.countDown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    error.compareAndSet(null, utteranceId)
                    completed.countDown()
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    error.compareAndSet(null, "$utteranceId:$errorCode")
                    completed.countDown()
                }
            })

            val parameters = Bundle()
            assertEquals(
                TextToSpeech.SUCCESS,
                tts.speak(FIRST_SENTENCE, TextToSpeech.QUEUE_FLUSH, parameters, "transition-1")
            )
            assertEquals(
                TextToSpeech.SUCCESS,
                tts.speak(SECOND_SENTENCE, TextToSpeech.QUEUE_ADD, parameters, "transition-2")
            )

            assertTrue("Queued TTS requests timed out", completed.await(60, TimeUnit.SECONDS))
            assertEquals(null, error.get())
        } finally {
            tts.shutdown()
        }
    }

    companion object {
        private const val TAG = "SupertonicBenchmark"
        private const val FIRST_SENTENCE =
            "The first sentence gives Android enough audio to expose callback backpressure."
        private const val SECOND_SENTENCE =
            "The second sentence measures how quickly queued inference can begin."
    }
}
