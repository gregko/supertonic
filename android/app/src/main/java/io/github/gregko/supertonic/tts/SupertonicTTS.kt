package io.github.gregko.supertonic.tts

import android.util.Log
import io.github.gregko.supertonic.tts.utils.SynthesisPreferences

object SupertonicTTS {
    private var nativePtr: Long = 0
    private var nativeLoadError: UnsatisfiedLinkError? = null
    private var loadedModelPath: String? = null
    private var loadedLibPath: String? = null

    init {
        try {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("supertonic_tts")
            nativeLoadError = null
        } catch (e: UnsatisfiedLinkError) {
            nativeLoadError = e
            Log.e("SupertonicTTS", "Failed to load native libraries", e)
        }
    }

    private external fun init(modelPath: String, libPath: String): Long
    private external fun synthesize(ptr: Long, text: String, lang: String, stylePath: String, speed: Float, temperature: Float, bufferSeconds: Float, steps: Int): ByteArray
    private external fun getSocClass(ptr: Long): Int
    private external fun getSampleRate(ptr: Long): Int
    private external fun close(ptr: Long)
    private external fun reset(ptr: Long)

    @Synchronized
    fun initialize(modelPath: String, libPath: String): Boolean {
        nativeLoadError?.let {
            Log.e(
                "SupertonicTTS",
                "Native libraries are unavailable. Package libonnxruntime.so and libsupertonic_tts.so for the current ABI before initializing.",
                it
            )
            return false
        }

        if (nativePtr != 0L) {
            val sameModel = loadedModelPath == modelPath && loadedLibPath == libPath
            if (sameModel && getSocClass(nativePtr) != -1) {
                Log.i("SupertonicTTS", "Engine already initialized and healthy")
                return true
            }

            Log.i("SupertonicTTS", "Re-initializing engine for a different model or unhealthy state")
            release()
        }
        
        nativePtr = init(modelPath, libPath)
        val success = nativePtr != 0L
        if (success) {
            loadedModelPath = modelPath
            loadedLibPath = libPath
            Log.i("SupertonicTTS", "Engine initialized successfully: $nativePtr")
        } else {
            loadedModelPath = null
            loadedLibPath = null
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
        fun onAudioChunk(sessionId: Long, data: ByteArray)
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
    fun notifyAudioChunk(data: ByteArray) {
        val sid = currentSessionId
        // STRICT ISOLATION: Audio chunks ONLY go to the requester
        if (currentTaskListener != null) {
            currentTaskListener?.onAudioChunk(sid, data)
        } else {
            // Only if no specific task listener is active (e.g. legacy app call)
            // we send to global listeners
            for (l in listeners) l.onAudioChunk(sid, data)
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
                text,
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
    }

    @Synchronized
    fun reset() {
        if (nativePtr != 0L) {
            reset(nativePtr)
        }
    }
}
