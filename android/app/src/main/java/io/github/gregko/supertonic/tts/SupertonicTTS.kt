package io.github.gregko.supertonic.tts

import android.util.Log
import io.github.gregko.supertonic.tts.utils.SynthesisPreferences
import java.text.Normalizer

object SupertonicTTS {
    private var nativePtr: Long = 0
    private var nativeLoadError: UnsatisfiedLinkError? = null
    private var loadedModelPath: String? = null
    private var loadedLibPath: String? = null
    private var loadedIntraOpThreads: Int? = null

    init {
        try {
            System.loadLibrary("c++_shared")
            System.loadLibrary("onnxruntime")
            System.loadLibrary("supertonic_tts")
            nativeLoadError = null
        } catch (e: UnsatisfiedLinkError) {
            nativeLoadError = e
            Log.e("SupertonicTTS", "Failed to load native libraries", e)
        }
    }

    private external fun init(modelPath: String, libPath: String, intraOpThreads: Int): Long
    private external fun synthesize(ptr: Long, text: String, lang: String, stylePath: String, speed: Float, temperature: Float, bufferSeconds: Float, steps: Int): ByteArray
    private external fun synthesizeStreaming(
        ptr: Long,
        text: String,
        lang: String,
        stylePath: String,
        speed: Float,
        temperature: Float,
        bufferSeconds: Float,
        steps: Int,
        chunkBytes: Int,
        gain: Float
    ): Boolean
    private external fun getSocClass(ptr: Long): Int
    private external fun getSampleRate(ptr: Long): Int
    private external fun close(ptr: Long)
    private external fun reset(ptr: Long)

    @Synchronized
    fun initialize(
        modelPath: String,
        libPath: String,
        intraOpThreads: Int = SynthesisPreferences.DEFAULT_INTRA_OP_THREADS
    ): Boolean {
        nativeLoadError?.let {
            Log.e(
                "SupertonicTTS",
                "Native libraries are unavailable. Package libonnxruntime.so and libsupertonic_tts.so for the current ABI before initializing.",
                it
            )
            return false
        }

        if (nativePtr != 0L) {
            val normalizedThreads = SynthesisPreferences.normalizeIntraOpThreads(intraOpThreads)
            val sameModel = loadedModelPath == modelPath &&
                loadedLibPath == libPath &&
                loadedIntraOpThreads == normalizedThreads
            if (sameModel && getSocClass(nativePtr) != -1) {
                Log.i("SupertonicTTS", "Engine already initialized and healthy")
                return true
            }

            Log.i("SupertonicTTS", "Re-initializing engine for a different model or unhealthy state")
            release()
        }
        
        val normalizedThreads = SynthesisPreferences.normalizeIntraOpThreads(intraOpThreads)
        nativePtr = init(modelPath, libPath, normalizedThreads)
        val success = nativePtr != 0L
        if (success) {
            loadedModelPath = modelPath
            loadedLibPath = libPath
            loadedIntraOpThreads = normalizedThreads
            Log.i(
                "SupertonicTTS",
                "Engine initialized successfully: $nativePtr, intraOpThreads=$normalizedThreads"
            )
        } else {
            loadedModelPath = null
            loadedLibPath = null
            loadedIntraOpThreads = null
            Log.e("SupertonicTTS", "Engine initialization FAILED")
        }
        return success
    }

    private var listeners = java.util.concurrent.CopyOnWriteArrayList<ProgressListener>()
    
    @Volatile
    private var currentSessionId: Long = 0
    
    private var currentTaskListener: ProgressListener? = null

    interface ProgressListener {
        fun onProgress(sessionId: Long, current: Int, total: Int)
        fun onAudioChunk(sessionId: Long, data: ByteArray): Boolean
    }

    fun addProgressListener(listener: ProgressListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeProgressListener(listener: ProgressListener) {
        listeners.remove(listener)
    }

    // Called from JNI
    fun notifyProgress(current: Int, total: Int) {
        val sid = currentSessionId
        // Priority to task-specific listener
        if (currentTaskListener != null) {
            currentTaskListener?.onProgress(sid, current, total)
        } else {
            // Only notify global listeners if no specific task listener is set
            for (l in listeners) l.onProgress(sid, current, total)
        }
    }

    // Called from JNI
    fun notifyAudioChunk(data: ByteArray): Boolean {
        val sid = currentSessionId
        // STRICT ISOLATION: Audio chunks ONLY go to the requester
        if (currentTaskListener != null) {
            return currentTaskListener?.onAudioChunk(sid, data) ?: false
        } else {
            // Only if no specific task listener is active (e.g. legacy app call)
            // we send to global listeners
            var accepted = true
            for (l in listeners) accepted = l.onAudioChunk(sid, data) && accepted
            return accepted
        }
    }

    @Volatile
    private var isCancelled = false

    fun setCancelled(cancelled: Boolean) {
        isCancelled = cancelled
    }

    // Called from JNI
    fun isCancelled(): Boolean {
        return isCancelled
    }

    @Synchronized
    fun generateAudio(
        text: String,
        lang: String,
        stylePath: String,
        speed: Float = 1.0f,
        bufferDuration: Float = 0.0f,
        steps: Int = 5,
        temperature: Float = SynthesisPreferences.DEFAULT_TEMPERATURE,
        listener: ProgressListener? = null
    ): ByteArray? {
        if (nativePtr == 0L) {
            Log.e("SupertonicTTS", "Engine not initialized")
            return null
        }
        
        currentSessionId++ // New session for every sentence
        currentTaskListener = listener
        
        try {
            val data = synthesize(
                nativePtr,
                Normalizer.normalize(text, Normalizer.Form.NFKD),
                lang,
                stylePath,
                speed,
                SynthesisPreferences.normalizeTemperature(temperature),
                bufferDuration,
                steps
            )
            return if (data.isNotEmpty()) data else null
        } catch (e: Exception) {
            Log.e("SupertonicTTS", "Native synthesis exception: ${e.message}")
            return null
        } finally {
            currentTaskListener = null
        }
    }

    /**
     * Streams bounded PCM segments to [listener] on the calling synthesis thread. Unlike
     * [generateAudio], this path does not create or return a second full-waveform PCM array.
     */
    @Synchronized
    fun streamAudio(
        text: String,
        lang: String,
        stylePath: String,
        speed: Float = 1.0f,
        bufferDuration: Float = 0.0f,
        steps: Int = 5,
        temperature: Float = SynthesisPreferences.DEFAULT_TEMPERATURE,
        chunkBytes: Int,
        gain: Float = 1.0f,
        listener: ProgressListener
    ): Boolean {
        if (nativePtr == 0L) {
            Log.e("SupertonicTTS", "Engine not initialized")
            return false
        }

        currentSessionId++
        currentTaskListener = listener

        return try {
            synthesizeStreaming(
                nativePtr,
                Normalizer.normalize(text, Normalizer.Form.NFKD),
                lang,
                stylePath,
                speed,
                SynthesisPreferences.normalizeTemperature(temperature),
                bufferDuration,
                steps,
                chunkBytes,
                gain
            )
        } catch (e: Exception) {
            Log.e("SupertonicTTS", "Native streaming synthesis exception: ${e.message}")
            false
        } finally {
            currentTaskListener = null
        }
    }

    @Synchronized
    fun getSoC(): Int {
        if (nativePtr == 0L) return -1
        return getSocClass(nativePtr)
    }

    @Synchronized
    fun getAudioSampleRate(): Int {
        if (nativePtr == 0L) return 44100
        return getSampleRate(nativePtr)
    }

    @Synchronized
    fun release() {
        if (nativePtr != 0L) {
            Log.i("SupertonicTTS", "Releasing engine: $nativePtr")
            close(nativePtr)
            nativePtr = 0
        }
        loadedModelPath = null
        loadedLibPath = null
        loadedIntraOpThreads = null
    }

    @Synchronized
    fun reset() {
        if (nativePtr != 0L) {
            reset(nativePtr)
        }
    }
}
