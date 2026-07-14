package io.github.gregko.supertonic.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gregko.supertonic.tts.utils.AssetInstaller
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamingSynthesisTest {
    @After
    fun releaseEngine() {
        SupertonicTTS.release()
    }

    @Test
    fun streamsBoundedChunksOnCallingThread() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = requireNotNull(AssetInstaller.prepareModel(context, "en"))
        val stylePath = AssetInstaller.resolveStyleFile(context, "M1.json", "en").absolutePath
        assertTrue(SupertonicTTS.initialize(model.modelPath, model.libPath, 5))
        SupertonicTTS.setCancelled(false)

        val synthesisThread = Thread.currentThread()
        var callbackCount = 0
        var pcmBytes = 0L
        val listener = object : SupertonicTTS.ProgressListener {
            override fun onProgress(sessionId: Long, current: Int, total: Int) = Unit

            override fun onAudioChunk(sessionId: Long, data: ByteArray): Boolean {
                assertTrue(synthesisThread === Thread.currentThread())
                assertTrue(data.isNotEmpty())
                assertTrue(data.size <= CHUNK_BYTES)
                assertEquals(0, data.size % 2)
                callbackCount++
                pcmBytes += data.size
                return true
            }
        }

        assertTrue(
            SupertonicTTS.streamAudio(
                text = "Streaming keeps every callback on the Android synthesis thread.",
                lang = "en",
                stylePath = stylePath,
                steps = 5,
                chunkBytes = CHUNK_BYTES,
                listener = listener
            )
        )
        assertTrue(callbackCount > 1)
        assertTrue(pcmBytes > CHUNK_BYTES)
    }

    companion object {
        private const val CHUNK_BYTES = 4096
    }
}
