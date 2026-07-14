/**
 * JNI bridge for the Supertonic C++ engine.
 *
 * Buffered synthesis is retained for the in-app player and file export. Android's system TTS
 * service uses synthesizeStreaming(), which converts and submits bounded PCM segments on the
 * calling synthesis thread and avoids constructing a second full-waveform JNI byte array.
 */

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <memory>
#include <sstream>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "helper.h"

#define LOG_TAG "SupertonicTTS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using Clock = std::chrono::steady_clock;
constexpr int kDefaultIntraOpThreads = 5;
constexpr int kMaxIntraOpThreads = 16;
constexpr size_t kMaxStyleCacheEntries = 16;

double elapsed_ms(Clock::time_point start, Clock::time_point end) {
    return std::chrono::duration<double, std::milli>(end - start).count();
}

struct SupertonicEngine {
    Ort::Env env{ORT_LOGGING_LEVEL_WARNING, "SupertonicTTS"};
    Ort::MemoryInfo memory_info{
        Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault)
    };
    std::unique_ptr<TextToSpeech> tts;
    // TextToSpeech stores raw pointers, so sessions and the processor must outlive it.
    OnnxModels models;
    std::unique_ptr<UnicodeProcessor> text_processor;
    std::unordered_map<std::string, std::shared_ptr<const Style>> style_cache;
    int intra_op_threads = kDefaultIntraOpThreads;
    float last_rtf = 1.0f;
};

struct CallbackMethods {
    jclass clazz = nullptr;
    jmethodID is_cancelled = nullptr;
    jmethodID notify_progress = nullptr;
    jmethodID notify_audio_chunk = nullptr;
};

struct NativeSynthesisResult {
    TextToSpeech::SynthesisResult audio;
    double style_lookup_ms = 0.0;
    double inference_ms = 0.0;
    bool style_cache_hit = false;
};

int detect_soc_class() {
    uint64_t max_freq = 0;
    for (int cpu = 0; cpu < 16; ++cpu) {
        const std::string path =
            "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq";
        std::ifstream file(path);
        uint64_t frequency = 0;
        if (file >> frequency) {
            max_freq = std::max(max_freq, frequency);
        }
    }
    if (max_freq == 0) return 1;
    if (max_freq >= 3000000) return 3;
    if (max_freq >= 2500000) return 2;
    if (max_freq >= 2000000) return 1;
    return 0;
}

std::string jstring_to_string(JNIEnv* env, jstring value) {
    if (!value) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return "";
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

float normalized_speed(float speed) {
    if (!std::isfinite(speed)) return 1.0f;
    return std::clamp(speed, 0.5f, 2.5f);
}

float normalized_temperature(float temperature) {
    if (!std::isfinite(temperature)) return 0.667f;
    return std::clamp(temperature, 0.4f, 1.0f);
}

float normalized_gain(float gain) {
    if (!std::isfinite(gain)) return 1.0f;
    return std::clamp(gain, 0.0f, 8.0f);
}

int normalized_steps(int steps) {
    return std::clamp(steps, 1, 20);
}

int normalized_chunk_bytes(int chunk_bytes) {
    const int bounded = std::clamp(chunk_bytes, 2, 65536);
    return bounded - (bounded % 2);
}

Style load_style(const std::string& style_path) {
    if (style_path.find(';') == std::string::npos) {
        return loadVoiceStyle({style_path}, false);
    }

    std::vector<std::string> parts;
    std::string token;
    std::istringstream stream(style_path);
    while (std::getline(stream, token, ';')) {
        parts.push_back(token);
    }
    if (parts.size() != 3) {
        throw std::runtime_error("Invalid mixed voice style path");
    }

    float alpha = 0.5f;
    try {
        alpha = std::stof(parts[2]);
    } catch (...) {
        throw std::runtime_error("Invalid mixed voice alpha");
    }
    alpha = std::clamp(alpha, 0.0f, 1.0f);

    Style first = loadVoiceStyle({parts[0]}, false);
    Style second = loadVoiceStyle({parts[1]}, false);
    if (first.getTtlShape() != second.getTtlShape() ||
        first.getDpShape() != second.getDpShape() ||
        first.getTtlData().size() != second.getTtlData().size() ||
        first.getDpData().size() != second.getDpData().size()) {
        throw std::runtime_error("Mixed voice styles have incompatible shapes");
    }

    auto mix = [alpha](const std::vector<float>& a, const std::vector<float>& b) {
        std::vector<float> result(a.size());
        for (size_t i = 0; i < a.size(); ++i) {
            result[i] = a[i] * (1.0f - alpha) + b[i] * alpha;
        }
        return result;
    };

    return Style(
        mix(first.getTtlData(), second.getTtlData()), first.getTtlShape(),
        mix(first.getDpData(), second.getDpData()), first.getDpShape()
    );
}

std::pair<std::shared_ptr<const Style>, bool> get_cached_style(
    SupertonicEngine& engine,
    const std::string& style_path
) {
    auto existing = engine.style_cache.find(style_path);
    if (existing != engine.style_cache.end()) {
        return {existing->second, true};
    }

    auto loaded = std::make_shared<const Style>(load_style(style_path));
    if (engine.style_cache.size() >= kMaxStyleCacheEntries) {
        engine.style_cache.erase(engine.style_cache.begin());
    }
    engine.style_cache.emplace(style_path, loaded);
    return {std::move(loaded), false};
}

CallbackMethods get_callback_methods(JNIEnv* env, jobject instance, bool needs_audio_callback) {
    CallbackMethods methods;
    methods.clazz = env->GetObjectClass(instance);
    if (!methods.clazz) return methods;

    methods.is_cancelled = env->GetMethodID(methods.clazz, "isCancelled", "()Z");
    methods.notify_progress = env->GetMethodID(methods.clazz, "notifyProgress", "(II)V");
    if (needs_audio_callback) {
        methods.notify_audio_chunk =
            env->GetMethodID(methods.clazz, "notifyAudioChunk", "([B)Z");
    }
    return methods;
}

void release_callback_methods(JNIEnv* env, CallbackMethods& methods) {
    if (methods.clazz) {
        env->DeleteLocalRef(methods.clazz);
        methods.clazz = nullptr;
    }
}

bool is_cancelled(JNIEnv* env, jobject instance, const CallbackMethods& methods) {
    return !methods.is_cancelled || env->CallBooleanMethod(instance, methods.is_cancelled) == JNI_TRUE;
}

bool notify_progress(
    JNIEnv* env,
    jobject instance,
    const CallbackMethods& methods,
    int current,
    int total
) {
    if (!methods.notify_progress) return false;
    env->CallVoidMethod(instance, methods.notify_progress, current, total);
    return !env->ExceptionCheck();
}

bool notify_audio_chunk(
    JNIEnv* env,
    jobject instance,
    const CallbackMethods& methods,
    jbyteArray chunk
) {
    if (!methods.notify_audio_chunk) return false;
    const jboolean accepted =
        env->CallBooleanMethod(instance, methods.notify_audio_chunk, chunk);
    return !env->ExceptionCheck() && accepted == JNI_TRUE;
}

void convert_pcm16_segment(
    const std::vector<float>& waveform,
    size_t sample_offset,
    size_t sample_count,
    float gain,
    std::vector<jbyte>& destination
) {
    destination.resize(sample_count * 2);
    for (size_t i = 0; i < sample_count; ++i) {
        const float scaled = std::clamp(waveform[sample_offset + i] * gain, -1.0f, 1.0f);
        const int16_t pcm = static_cast<int16_t>(scaled * 32767.0f);
        destination[i * 2] = static_cast<jbyte>(pcm & 0xff);
        destination[i * 2 + 1] = static_cast<jbyte>((pcm >> 8) & 0xff);
    }
}

NativeSynthesisResult run_inference(
    SupertonicEngine& engine,
    const std::string& text,
    const std::string& language,
    const std::string& style_path,
    float speed,
    int steps,
    float temperature
) {
    const auto style_start = Clock::now();
    auto [style, cache_hit] = get_cached_style(engine, style_path);
    const auto style_end = Clock::now();

    const auto inference_start = Clock::now();
    auto audio = engine.tts->call(
        engine.memory_info,
        text,
        language,
        *style,
        steps,
        speed,
        0.1f,
        temperature
    );
    const auto inference_end = Clock::now();

    // Input tensor backing buffers are no longer needed once all ONNX calls return. Releasing
    // them before Android callback backpressure avoids retaining inference-only memory for most
    // of playback.
    clearTensorBuffers();

    NativeSynthesisResult result;
    result.audio = std::move(audio);
    result.style_lookup_ms = elapsed_ms(style_start, style_end);
    result.inference_ms = elapsed_ms(inference_start, inference_end);
    result.style_cache_hit = cache_hit;
    return result;
}

void log_metrics(
    const char* mode,
    const SupertonicEngine& engine,
    const NativeSynthesisResult& result,
    int steps,
    size_t audio_samples,
    size_t model_segments,
    size_t chunks,
    double pcm_conversion_ms,
    double callback_ms,
    double time_to_first_audio_ms,
    double total_ms,
    bool success
) {
    const double audio_ms = engine.tts->getSampleRate() > 0
        ? (audio_samples * 1000.0) / engine.tts->getSampleRate()
        : 0.0;
    const double conventional_rtf = audio_ms > 0.0 ? result.inference_ms / audio_ms : 0.0;
    const double throughput = result.inference_ms > 0.0 ? audio_ms / result.inference_ms : 0.0;

    LOGI(
        "TTS_METRIC native mode=%s success=%d threads=%d steps=%d cache_hit=%d "
        "style_ms=%.3f inference_ms=%.3f pcm_conversion_ms=%.3f "
        "callback_backpressure_ms=%.3f time_to_first_audio_ms=%.3f total_ms=%.3f "
        "audio_ms=%.3f rtf=%.4f throughput=%.2fx model_segments=%zu chunks=%zu",
        mode,
        success ? 1 : 0,
        engine.intra_op_threads,
        steps,
        result.style_cache_hit ? 1 : 0,
        result.style_lookup_ms,
        result.inference_ms,
        pcm_conversion_ms,
        callback_ms,
        time_to_first_audio_ms,
        total_ms,
        audio_ms,
        conventional_rtf,
        throughput,
        model_segments,
        chunks
    );
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_gregko_supertonic_tts_SupertonicTTS_init(
    JNIEnv* env,
    jclass,
    jstring model_path_value,
    jstring,
    jint intra_op_threads
) {
    const std::string model_path = jstring_to_string(env, model_path_value);
    const int threads = std::clamp(
        static_cast<int>(intra_op_threads),
        1,
        kMaxIntraOpThreads
    );
    LOGI(
        "Initializing Supertonic C++ engine, model path=%s, intra_op_threads=%d",
        model_path.c_str(),
        threads
    );

    try {
        auto engine = std::make_unique<SupertonicEngine>();
        engine->intra_op_threads = threads;

        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(threads);
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

        auto configs = loadCfgs(model_path);
        engine->models = loadOnnxAll(engine->env, model_path, options);
        engine->text_processor = loadTextProcessor(model_path);
        engine->tts = std::make_unique<TextToSpeech>(
            configs,
            engine->text_processor.get(),
            engine->models.dp.get(),
            engine->models.text_enc.get(),
            engine->models.vector_est.get(),
            engine->models.vocoder.get()
        );

        LOGI("Engine initialized successfully (sample_rate=%d)", engine->tts->getSampleRate());
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception& error) {
        LOGE("Engine init failed: %s", error.what());
        return 0;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_gregko_supertonic_tts_SupertonicTTS_synthesize(
    JNIEnv* env,
    jobject instance,
    jlong pointer,
    jstring text_value,
    jstring language_value,
    jstring style_path_value,
    jfloat speed,
    jfloat temperature,
    jfloat,
    jint steps
) {
    if (pointer == 0) {
        LOGE("synthesize called with null engine pointer");
        return env->NewByteArray(0);
    }

    auto* engine = reinterpret_cast<SupertonicEngine*>(pointer);
    CallbackMethods methods = get_callback_methods(env, instance, false);
    const auto native_start = Clock::now();
    const int safe_steps = normalized_steps(steps);

    try {
        if (is_cancelled(env, instance, methods)) {
            release_callback_methods(env, methods);
            return env->NewByteArray(0);
        }
        notify_progress(env, instance, methods, 0, 1);

        auto result = run_inference(
            *engine,
            jstring_to_string(env, text_value),
            jstring_to_string(env, language_value),
            jstring_to_string(env, style_path_value),
            normalized_speed(speed),
            safe_steps,
            normalized_temperature(temperature)
        );

        if (is_cancelled(env, instance, methods)) {
            release_callback_methods(env, methods);
            return env->NewByteArray(0);
        }

        const auto pcm_start = Clock::now();
        std::vector<jbyte> pcm;
        convert_pcm16_segment(result.audio.wav, 0, result.audio.wav.size(), 1.0f, pcm);
        const auto pcm_end = Clock::now();

        jbyteArray output = env->NewByteArray(static_cast<jsize>(pcm.size()));
        if (output && !pcm.empty()) {
            env->SetByteArrayRegion(output, 0, static_cast<jsize>(pcm.size()), pcm.data());
        }
        notify_progress(env, instance, methods, 1, 1);

        const auto end = Clock::now();
        const double audio_seconds = engine->tts->getSampleRate() > 0
            ? result.audio.wav.size() / static_cast<double>(engine->tts->getSampleRate())
            : 0.0;
        if (audio_seconds > 0.0) {
            engine->last_rtf = static_cast<float>((result.inference_ms / 1000.0) / audio_seconds);
        }
        log_metrics(
            "buffered",
            *engine,
            result,
            safe_steps,
            result.audio.wav.size(),
            1,
            pcm.empty() ? 0 : 1,
            elapsed_ms(pcm_start, pcm_end),
            0.0,
            elapsed_ms(native_start, pcm_end),
            elapsed_ms(native_start, end),
            output != nullptr
        );
        release_callback_methods(env, methods);
        return output ? output : env->NewByteArray(0);
    } catch (const std::exception& error) {
        LOGE("Synthesis failed: %s", error.what());
        clearTensorBuffers();
        release_callback_methods(env, methods);
        return env->NewByteArray(0);
    }
}

JNIEXPORT jboolean JNICALL
Java_io_github_gregko_supertonic_tts_SupertonicTTS_synthesizeStreaming(
    JNIEnv* env,
    jobject instance,
    jlong pointer,
    jstring text_value,
    jstring language_value,
    jstring style_path_value,
    jfloat speed,
    jfloat temperature,
    jfloat,
    jint steps,
    jint chunk_bytes,
    jfloat gain
) {
    if (pointer == 0) {
        LOGE("synthesizeStreaming called with null engine pointer");
        return JNI_FALSE;
    }

    auto* engine = reinterpret_cast<SupertonicEngine*>(pointer);
    CallbackMethods methods = get_callback_methods(env, instance, true);
    const auto native_start = Clock::now();
    const int safe_steps = normalized_steps(steps);
    const size_t samples_per_chunk = static_cast<size_t>(normalized_chunk_bytes(chunk_bytes) / 2);

    try {
        if (is_cancelled(env, instance, methods)) {
            release_callback_methods(env, methods);
            return JNI_FALSE;
        }
        const std::string text = jstring_to_string(env, text_value);
        const std::string language = jstring_to_string(env, language_value);
        const std::string style_path = jstring_to_string(env, style_path_value);
        const int max_text_length = (language == "ko" || language == "ja") ? 120 : 300;
        const auto model_text_segments = chunkText(text, max_text_length);
        if (model_text_segments.empty() ||
            !notify_progress(
                env,
                instance,
                methods,
                0,
                static_cast<int>(model_text_segments.size())
            )) {
            release_callback_methods(env, methods);
            return JNI_FALSE;
        }

        NativeSynthesisResult result;
        const auto style_start = Clock::now();
        auto [style, cache_hit] = get_cached_style(*engine, style_path);
        const auto style_end = Clock::now();
        result.style_lookup_ms = elapsed_ms(style_start, style_end);
        result.style_cache_hit = cache_hit;

        bool success = true;
        double pcm_conversion_ms = 0.0;
        double callback_ms = 0.0;
        double time_to_first_audio_ms = 0.0;
        size_t audio_samples = 0;
        size_t chunks = 0;
        std::vector<jbyte> pcm_chunk;

        auto submit_waveform = [&](const std::vector<float>& waveform, float waveform_gain) {
            for (size_t offset = 0; success && offset < waveform.size();) {
                const size_t count = std::min(samples_per_chunk, waveform.size() - offset);
                const auto pcm_start = Clock::now();
                convert_pcm16_segment(
                    waveform,
                    offset,
                    count,
                    waveform_gain,
                    pcm_chunk
                );
                const auto pcm_end = Clock::now();
                pcm_conversion_ms += elapsed_ms(pcm_start, pcm_end);

                jbyteArray chunk = env->NewByteArray(static_cast<jsize>(pcm_chunk.size()));
                if (!chunk) {
                    success = false;
                    break;
                }
                env->SetByteArrayRegion(
                    chunk,
                    0,
                    static_cast<jsize>(pcm_chunk.size()),
                    pcm_chunk.data()
                );
                if (env->ExceptionCheck()) {
                    env->DeleteLocalRef(chunk);
                    success = false;
                    break;
                }

                const auto callback_start = Clock::now();
                if (chunks == 0) {
                    time_to_first_audio_ms = elapsed_ms(native_start, callback_start);
                }
                success = notify_audio_chunk(env, instance, methods, chunk);
                const auto callback_end = Clock::now();
                callback_ms += elapsed_ms(callback_start, callback_end);
                env->DeleteLocalRef(chunk);

                ++chunks;
                offset += count;
                if (success) {
                    success = !is_cancelled(env, instance, methods);
                }
            }
        };

        const float safe_speed = normalized_speed(speed);
        const float safe_temperature = normalized_temperature(temperature);
        const float safe_gain = normalized_gain(gain);
        const size_t silence_samples = static_cast<size_t>(engine->tts->getSampleRate() * 0.1f);
        const std::vector<float> silence(silence_samples, 0.0f);

        for (size_t index = 0; success && index < model_text_segments.size(); ++index) {
            const auto inference_start = Clock::now();
            auto segment = engine->tts->batch(
                engine->memory_info,
                {model_text_segments[index]},
                {language},
                *style,
                safe_steps,
                safe_speed,
                safe_temperature
            );
            const auto inference_end = Clock::now();
            result.inference_ms += elapsed_ms(inference_start, inference_end);
            clearTensorBuffers();

            // This is the same silence and the same existing text boundary used by call(). The
            // next model segment is inferred before its leading silence is submitted, allowing
            // inference to use the playback headroom left by the preceding segment.
            if (index > 0) {
                audio_samples += silence.size();
                submit_waveform(silence, 1.0f);
            }
            if (success) {
                audio_samples += segment.wav.size();
                submit_waveform(segment.wav, safe_gain);
            }
            if (success) {
                success = notify_progress(
                    env,
                    instance,
                    methods,
                    static_cast<int>(index + 1),
                    static_cast<int>(model_text_segments.size())
                );
            }
        }

        const auto end = Clock::now();

        const double audio_seconds = engine->tts->getSampleRate() > 0
            ? audio_samples / static_cast<double>(engine->tts->getSampleRate())
            : 0.0;
        if (audio_seconds > 0.0) {
            engine->last_rtf = static_cast<float>((result.inference_ms / 1000.0) / audio_seconds);
        }
        log_metrics(
            "streaming",
            *engine,
            result,
            safe_steps,
            audio_samples,
            model_text_segments.size(),
            chunks,
            pcm_conversion_ms,
            callback_ms,
            time_to_first_audio_ms,
            elapsed_ms(native_start, end),
            success
        );
        release_callback_methods(env, methods);
        return success ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& error) {
        LOGE("Streaming synthesis failed: %s", error.what());
        clearTensorBuffers();
        release_callback_methods(env, methods);
        return JNI_FALSE;
    }
}

JNIEXPORT jint JNICALL
Java_io_github_gregko_supertonic_tts_SupertonicTTS_getSocClass(
    JNIEnv*,
    jclass,
    jlong pointer
) {
    if (pointer == 0) return -1;
    static int cached_soc = -1;
    if (cached_soc < 0) cached_soc = detect_soc_class();
    return cached_soc;
}

JNIEXPORT jint JNICALL
Java_io_github_gregko_supertonic_tts_SupertonicTTS_getSampleRate(
    JNIEnv*,
    jclass,
    jlong pointer
) {
    if (pointer == 0) return 44100;
    auto* engine = reinterpret_cast<SupertonicEngine*>(pointer);
    return engine->tts->getSampleRate();
}

JNIEXPORT void JNICALL
Java_io_github_gregko_supertonic_tts_SupertonicTTS_reset(
    JNIEnv*,
    jclass,
    jlong pointer
) {
    if (pointer == 0) return;
    auto* engine = reinterpret_cast<SupertonicEngine*>(pointer);
    engine->last_rtf = 1.0f;
    LOGI("Engine state reset (style cache retained, entries=%zu)", engine->style_cache.size());
}

JNIEXPORT void JNICALL
Java_io_github_gregko_supertonic_tts_SupertonicTTS_close(
    JNIEnv*,
    jclass,
    jlong pointer
) {
    if (pointer == 0) return;
    auto* engine = reinterpret_cast<SupertonicEngine*>(pointer);
    LOGI("Closing engine");
    delete engine;
}

}  // extern "C"
