/**
 * JNI bridge for Supertonic TTS C++ engine.
 *
 * Replaces the Rust JNI layer (rust/src/lib.rs) with a thin C++ wrapper
 * that delegates to the existing helper.h / helper.cpp TTS pipeline.
 *
 * The six JNI entry points match the Kotlin declarations in SupertonicTTS.kt:
 *   init, synthesize, getSocClass, getSampleRate, reset, close
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <memory>
#include <chrono>
#include <cmath>
#include <fstream>
#include <cstdint>
#include <sstream>

#include "helper.h"

#define LOG_TAG "SupertonicTTS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Engine object that lives for the lifetime of the native pointer
// ---------------------------------------------------------------------------

struct SupertonicEngine {
    Ort::Env                          env{ORT_LOGGING_LEVEL_WARNING, "SupertonicTTS"};
    Ort::MemoryInfo                   memory_info{Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault)};
    std::unique_ptr<TextToSpeech>     tts;
    // Keep ONNX sessions and text processor alive (TextToSpeech stores raw ptrs)
    OnnxModels                        models;
    std::unique_ptr<UnicodeProcessor> text_processor;
    float                             last_rtf = 1.0f;
};

// ---------------------------------------------------------------------------
// SoC detection (simple heuristic for Android)
// ---------------------------------------------------------------------------

static int detect_soc_class() {
    // Read the max CPU frequency to classify the SoC tier
    uint64_t max_freq = 0;
    for (int cpu = 0; cpu < 16; ++cpu) {
        std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq";
        std::ifstream f(path);
        uint64_t freq = 0;
        if (f >> freq) {
            if (freq > max_freq) max_freq = freq;
        }
    }
    if (max_freq == 0) return 1; // MidRange fallback

    // Classify based on peak frequency (kHz)
    if (max_freq >= 3000000) return 3; // Flagship
    if (max_freq >= 2500000) return 2; // HighEnd
    if (max_freq >= 2000000) return 1; // MidRange
    return 0;                          // LowEnd
}

// ---------------------------------------------------------------------------
// JNI helpers
// ---------------------------------------------------------------------------

static std::string jstring_to_string(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

/** Convert float audio samples to 16-bit little-endian PCM bytes. */
static std::vector<uint8_t> float_to_pcm16(const std::vector<float>& wav) {
    std::vector<uint8_t> pcm;
    pcm.reserve(wav.size() * 2);
    for (float sample : wav) {
        float clamped = std::max(-1.0f, std::min(1.0f, sample));
        int16_t val = static_cast<int16_t>(clamped * 32767.0f);
        pcm.push_back(static_cast<uint8_t>(val & 0xFF));
        pcm.push_back(static_cast<uint8_t>((val >> 8) & 0xFF));
    }
    return pcm;
}

// ---------------------------------------------------------------------------
// Voice style loading with mixing support (mirrors Rust lib.rs logic)
// ---------------------------------------------------------------------------

static Style load_style(const std::string& style_path) {
    if (style_path.find(';') != std::string::npos) {
        // Mixing format: path1;path2;alpha
        std::vector<std::string> parts;
        std::string token;
        std::istringstream stream(style_path);
        while (std::getline(stream, token, ';')) {
            parts.push_back(token);
        }
        if (parts.size() == 3) {
            float alpha = 0.5f;
            try { alpha = std::stof(parts[2]); } catch (...) {}
            // Load both styles and mix
            Style s1 = loadVoiceStyle({parts[0]}, false);
            Style s2 = loadVoiceStyle({parts[1]}, false);

            // Linear interpolation of TTL and DP data
            auto mix = [](const std::vector<float>& a, const std::vector<float>& b, float alpha) {
                std::vector<float> result(a.size());
                for (size_t i = 0; i < a.size(); ++i) {
                    result[i] = a[i] * (1.0f - alpha) + b[i] * alpha;
                }
                return result;
            };

            return Style(
                mix(s1.getTtlData(), s2.getTtlData(), alpha), s1.getTtlShape(),
                mix(s1.getDpData(),  s2.getDpData(),  alpha), s1.getDpShape()
            );
        }
    }
    return loadVoiceStyle({style_path}, false);
}

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_gregko_supertonic_tts_SupertonicTTS_init(
    JNIEnv* env, jclass /*clazz*/,
    jstring modelPath, jstring /*libPath*/)
{
    std::string model_path = jstring_to_string(env, modelPath);
    LOGI("Initializing Supertonic C++ engine, model path: %s", model_path.c_str());

    try {
        auto engine = std::make_unique<SupertonicEngine>();

        Ort::SessionOptions opts;
        opts.SetIntraOpNumThreads(5);
        opts.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

        auto cfgs = loadCfgs(model_path);
        engine->models = loadOnnxAll(engine->env, model_path, opts);
        engine->text_processor = loadTextProcessor(model_path);

        engine->tts = std::make_unique<TextToSpeech>(
            cfgs,
            engine->text_processor.get(),
            engine->models.dp.get(),
            engine->models.text_enc.get(),
            engine->models.vector_est.get(),
            engine->models.vocoder.get()
        );

        LOGI("Engine initialized successfully (sample rate=%d)", engine->tts->getSampleRate());
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception& e) {
        LOGE("Engine init failed: %s", e.what());
        return 0;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_gregko_supertonic_tts_SupertonicTTS_synthesize(
    JNIEnv* env, jobject instance,
    jlong ptr, jstring jtext, jstring jlang, jstring jstylePath,
    jfloat speed, jfloat temperature, jfloat /*bufferSeconds*/, jint steps)
{
    if (ptr == 0) {
        LOGE("synthesize called with null engine pointer");
        return env->NewByteArray(0);
    }

    auto* engine = reinterpret_cast<SupertonicEngine*>(ptr);

    std::string text       = jstring_to_string(env, jtext);
    std::string lang       = jstring_to_string(env, jlang);
    std::string style_path = jstring_to_string(env, jstylePath);

    // Clamp speed
    if (speed < 0.5f) speed = 0.5f;
    if (speed > 2.5f) speed = 2.5f;

    // Normalize temperature (clamp to [0.4, 1.0], default 0.667)
    if (!std::isfinite(temperature)) temperature = 0.667f;
    if (temperature < 0.4f) temperature = 0.4f;
    if (temperature > 1.0f) temperature = 1.0f;

    try {
        Style style = load_style(style_path);

        // Cache JNI method IDs (looked up once per call, not per-chunk)
        jclass clazz = env->GetObjectClass(instance);
        jmethodID mid_isCancelled   = env->GetMethodID(clazz, "isCancelled",    "()Z");
        jmethodID mid_notifyProg    = env->GetMethodID(clazz, "notifyProgress", "(II)V");
        jmethodID mid_notifyChunk   = env->GetMethodID(clazz, "notifyAudioChunk", "([B)V");

        // Check cancellation before starting
        if (env->CallBooleanMethod(instance, mid_isCancelled)) {
            return env->NewByteArray(0);
        }

        auto start_time = std::chrono::high_resolution_clock::now();

        // Notify progress: starting (0 of 1)
        env->CallVoidMethod(instance, mid_notifyProg, 0, 1);

        // Synthesize the entire text.
        // The C++ call() internally chunks long text (>300 chars),
        // applies speed, and adds +0.3s duration padding to prevent cutoff.
        // Text arriving here is already sentence-split by the Kotlin layer.
        auto result = engine->tts->call(
            engine->memory_info,
            text,
            lang,
            style,
            static_cast<int>(steps),
            speed,
            0.1f,        // silence between internal chunks
            temperature  // latent sampling temperature
        );

        // Stream audio chunk via callback
        auto pcm_chunk = float_to_pcm16(result.wav);
        jbyteArray chunk_array = env->NewByteArray(static_cast<jsize>(pcm_chunk.size()));
        env->SetByteArrayRegion(chunk_array, 0, static_cast<jsize>(pcm_chunk.size()),
                                reinterpret_cast<const jbyte*>(pcm_chunk.data()));
        env->CallVoidMethod(instance, mid_notifyChunk, chunk_array);
        env->DeleteLocalRef(chunk_array);

        // Notify progress: done (1 of 1)
        env->CallVoidMethod(instance, mid_notifyProg, 1, 1);

        // Update RTF
        auto end_time = std::chrono::high_resolution_clock::now();
        float elapsed = std::chrono::duration<float>(end_time - start_time).count();
        float dur = result.duration.empty() ? 0.0f : result.duration[0];
        if (dur > 0.0f && elapsed > 0.0f) {
            engine->last_rtf = dur / elapsed;
            LOGI("Inference RTF: %.2fx (%.1fs audio in %.2fs)", engine->last_rtf, dur, elapsed);
        }

        // Build the full return byte array (same PCM data)
        // The streaming callback already sent it, but Kotlin also uses the return value.
        clearTensorBuffers();

        jbyteArray output = env->NewByteArray(static_cast<jsize>(pcm_chunk.size()));
        env->SetByteArrayRegion(output, 0, static_cast<jsize>(pcm_chunk.size()),
                                reinterpret_cast<const jbyte*>(pcm_chunk.data()));
        return output;

    } catch (const std::exception& e) {
        LOGE("Synthesis failed: %s", e.what());
        clearTensorBuffers();
        return env->NewByteArray(0);
    }
}

JNIEXPORT jint JNICALL
Java_io_github_gregko_supertonic_tts_SupertonicTTS_getSocClass(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr)
{
    if (ptr == 0) return -1;
    static int cached_soc = -1;
    if (cached_soc < 0) cached_soc = detect_soc_class();
    return cached_soc;
}

JNIEXPORT jint JNICALL
Java_io_github_gregko_supertonic_tts_SupertonicTTS_getSampleRate(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr)
{
    if (ptr == 0) return 24000;
    auto* engine = reinterpret_cast<SupertonicEngine*>(ptr);
    return engine->tts->getSampleRate();
}

JNIEXPORT void JNICALL
Java_io_github_gregko_supertonic_tts_SupertonicTTS_reset(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr)
{
    if (ptr != 0) {
        auto* engine = reinterpret_cast<SupertonicEngine*>(ptr);
        engine->last_rtf = 1.0f;
        LOGI("Engine state reset");
    }
}

JNIEXPORT void JNICALL
Java_io_github_gregko_supertonic_tts_SupertonicTTS_close(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr)
{
    if (ptr != 0) {
        auto* engine = reinterpret_cast<SupertonicEngine*>(ptr);
        LOGI("Closing engine");
        delete engine;
    }
}

} // extern "C"
