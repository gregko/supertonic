package io.github.gregko.supertonic.tts.service

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import android.os.SystemClock
import io.github.gregko.supertonic.tts.SupertonicTTS
import io.github.gregko.supertonic.tts.utils.AssetInstaller
import io.github.gregko.supertonic.tts.utils.SynthesisPreferences
import io.github.gregko.supertonic.tts.utils.SupportedLanguage
import io.github.gregko.supertonic.tts.utils.SupportedLanguages
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class SupertonicTextToSpeechService : TextToSpeechService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var initJob: Deferred<Boolean>? = null
    private var requestedModelVersion: String? = null
    private var requestedIntraOpThreads: Int? = null
    private val transitionGapTracker = TransitionGapTracker()

    companion object {
        const val VOLUME_BOOST_FACTOR = 2.5f
        private const val TAG = "SupertonicTTS"
        private const val STREAM_CHUNK_BYTES = 4096
        private val REQUEST_SEQUENCE = AtomicLong(0L)
        private val VOICE_PROFILES = listOf(
            VoiceProfile("M1", "M1.json", "Alex - Lively, Upbeat"),
            VoiceProfile("M2", "M2.json", "James - Deep, Calm"),
            VoiceProfile("M3", "M3.json", "Robert - Polished, Authoritative"),
            VoiceProfile("M4", "M4.json", "Sam - Soft, Friendly"),
            VoiceProfile("M5", "M5.json", "Daniel - Warm, Soothing"),
            VoiceProfile("F1", "F1.json", "Sarah - Calm, Composed"),
            VoiceProfile("F2", "F2.json", "Lily - Bright, Cheerful"),
            VoiceProfile("F3", "F3.json", "Jessica - Professional, Clear"),
            VoiceProfile("F4", "F4.json", "Olivia - Crisp, Confident"),
            VoiceProfile("F5", "F5.json", "Emily - Kind, Gentle")
        )
        private val SUPPORTED_LANGS = SupportedLanguages.ALL
    }

    private data class VoiceProfile(
        val code: String,
        val fileName: String,
        val displayName: String
    )

    override fun onCreate() {
        super.onCreate()
        Log.i("SupertonicTTS", "Service created")
        io.github.gregko.supertonic.tts.utils.LexiconManager.load(this)

        startEngineInitialization(getSelectedLang())
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val supported = findSupportedLanguage(lang) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        return if (
            !country.isNullOrEmpty() &&
            (country.equals(supported.iso3Country, ignoreCase = true) ||
                country.equals(supported.locale.country, ignoreCase = true))
        ) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetLanguage(): Array<String> {
        val selected = findSupportedLanguage(getSelectedLang()) ?: SUPPORTED_LANGS.first()
        return arrayOf(selected.iso3Language, selected.iso3Country, "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onLoadVoice(voiceName: String?): Int {
        val (language, voiceFile) = parseVoiceRequest(voiceName) ?: return TextToSpeech.ERROR
        val engineReady = runBlocking {
            withTimeoutOrNull(15000) {
                ensureEngineReady(language.appCode)
            } ?: false
        }
        if (!engineReady) {
            return TextToSpeech.ERROR
        }

        return if (resolveStyleFile(language.appCode, voiceFile).exists()) {
            saveExternalVoiceSelection(language.appCode, voiceFile)
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        return if (parseVoiceRequest(voiceName) != null) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val language = findSupportedLanguage(lang) ?: findSupportedLanguage(getSelectedLang()) ?: SUPPORTED_LANGS.first()
        val selected = getEffectiveVoiceFile(language.appCode)
        val profile = findVoiceProfileByFileName(selected) ?: VOICE_PROFILES.first()
        return buildVoiceName(language, profile)
    }

    override fun onGetVoices(): List<Voice> {
        val voicesList = mutableListOf<Voice>()
        SUPPORTED_LANGS.forEach { language ->
            VOICE_PROFILES.forEach { profile ->
                voicesList.add(
                    Voice(
                        buildVoiceName(language, profile),
                        language.locale,
                        Voice.QUALITY_VERY_HIGH,
                        Voice.LATENCY_NORMAL,
                        false,
                        setOf()
                    )
                )
            }
        }

        return voicesList
    }

    override fun onStop() {
        SupertonicTTS.setCancelled(true)
        transitionGapTracker.reset()
    }

    private fun normalizeLanguage(lang: String?): String {
        return SupportedLanguages.normalizeOrDefault(lang)
    }

    private val textNormalizer = io.github.gregko.supertonic.tts.utils.TextNormalizer()

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        val requestId = REQUEST_SEQUENCE.incrementAndGet()
        val requestStartNanos = SystemClock.elapsedRealtimeNanos()
        SupertonicTTS.setCancelled(false)
        val requestedVoice = request.voiceName
        val parsedVoice = parseVoiceRequest(requestedVoice)
        val requestLang = parsedVoice?.first?.appCode ?: normalizeLanguage(request.language)
        val engineReady = runBlocking {
            withTimeoutOrNull(15000) {
                ensureEngineReady(requestLang)
            } ?: false
        }
        val engineReadyNanos = SystemClock.elapsedRealtimeNanos()
        if (!engineReady) {
            Log.e(TAG, "Engine initialization timed out for language=$requestLang")
            callback.error()
            return
        }

        val rawText = request.charSequenceText?.toString()
        if (rawText.isNullOrBlank()) {
            callback.error()
            return
        }
        val effectiveSpeed = (request.speechRate / 100.0f).coerceIn(0.5f, 2.5f)
        val sampleRate = SupertonicTTS.getAudioSampleRate()
        if (callback.start(
                sampleRate,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                1
            ) != TextToSpeech.SUCCESS
        ) {
            Log.e(TAG, "Synthesis callback rejected start for request=$requestId")
            callback.error()
            return
        }

        val callbackMaxBytes = callback.maxBufferSize.coerceAtLeast(2)
        val requestedChunkBytes = minOf(STREAM_CHUNK_BYTES, callbackMaxBytes).let {
            it - (it % 2)
        }.coerceAtLeast(2)
        var firstAudioNanos: Long? = null
        var estimatedTransitionGapNanos: Long? = null
        var callbackBackpressureNanos = 0L
        var totalPcmBytes = 0L
        var audioChunks = 0

        val localListener = object : SupertonicTTS.ProgressListener {
            override fun onProgress(sessionId: Long, current: Int, total: Int) {}
            override fun onAudioChunk(sessionId: Long, data: ByteArray): Boolean {
                val callbackStartNanos = SystemClock.elapsedRealtimeNanos()
                val status = callback.audioAvailable(data, 0, data.size)
                val callbackEndNanos = SystemClock.elapsedRealtimeNanos()
                callbackBackpressureNanos += callbackEndNanos - callbackStartNanos

                if (status == TextToSpeech.SUCCESS) {
                    if (firstAudioNanos == null) {
                        firstAudioNanos = callbackStartNanos
                        estimatedTransitionGapNanos =
                            transitionGapTracker.gapAtFirstAudioNanos(callbackStartNanos)
                    }
                    totalPcmBytes += data.size
                    audioChunks++
                    return true
                }

                Log.e(
                    TAG,
                    "audioAvailable failed request=$requestId session=$sessionId status=$status"
                )
                return false
            }
        }

        val prefs = getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
        val voiceFile = parsedVoice?.second ?: getEffectiveVoiceFile(requestLang)
        if (parsedVoice != null) {
            saveExternalVoiceSelection(requestLang, voiceFile)
        }

        val stylePath = resolveStyleFile(requestLang, voiceFile).absolutePath
        val steps = prefs.getInt("diffusion_steps", 5)
        val temperature = SynthesisPreferences.getTemperature(prefs)
        val intraOpThreads = SynthesisPreferences.getIntraOpThreads(prefs)

        val sentences = textNormalizer.splitIntoSentences(rawText)
        var success = sentences.isNotEmpty()
        for ((sentenceIndex, sentence) in sentences.withIndex()) {
            if (SupertonicTTS.isCancelled()) {
                success = false
                break
            }

            // Keep complete sentences as model inputs to preserve sentence-level prosody.
            val sentenceLang = requestLang
            val normalizedText = textNormalizer.normalize(sentence, sentenceLang)
            val sentenceStartNanos = SystemClock.elapsedRealtimeNanos()

            success = SupertonicTTS.streamAudio(
                normalizedText,
                sentenceLang,
                stylePath,
                effectiveSpeed,
                0.0f,
                steps,
                temperature,
                requestedChunkBytes,
                VOLUME_BOOST_FACTOR,
                localListener
            )
            Log.i(
                TAG,
                "TTS_METRIC sentence request=$requestId index=$sentenceIndex " +
                    "success=${if (success) 1 else 0} " +
                    "elapsed_ms=${formatMillis(SystemClock.elapsedRealtimeNanos() - sentenceStartNanos)}"
            )
            if (!success) {
                break
            }
        }

        val firstAudio = firstAudioNanos
        if (firstAudio != null && totalPcmBytes > 0L) {
            transitionGapTracker.completeRequest(firstAudio, totalPcmBytes, sampleRate)
        }
        if (success) callback.done() else callback.error()

        val requestEndNanos = SystemClock.elapsedRealtimeNanos()
        val timeToFirstAudioNanos = firstAudio?.minus(requestStartNanos)
        val audioDurationNanos = TransitionGapTracker.pcmDurationNanos(totalPcmBytes, sampleRate)
        Log.i(
            TAG,
            "TTS_METRIC service request=$requestId success=${if (success) 1 else 0} " +
                "threads=$intraOpThreads steps=$steps sentences=${sentences.size} chunks=$audioChunks " +
                "chunk_bytes=$requestedChunkBytes pcm_bytes=$totalPcmBytes " +
                "engine_wait_ms=${formatMillis(engineReadyNanos - requestStartNanos)} " +
                "time_to_first_audio_ms=${formatOptionalMillis(timeToFirstAudioNanos)} " +
                "callback_backpressure_ms=${formatMillis(callbackBackpressureNanos)} " +
                "estimated_transition_gap_ms=${formatOptionalMillis(estimatedTransitionGapNanos)} " +
                "audio_ms=${formatMillis(audioDurationNanos)} " +
                "total_ms=${formatMillis(requestEndNanos - requestStartNanos)}"
        )
    }

    private fun getSelectedLang(): String {
        val prefs = getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString("selected_lang", "en") ?: "en"
    }

    private fun getExternalVoicePreferenceKey(lang: String): String {
        return "tts_external_voice_${lang.lowercase(Locale.ROOT)}"
    }

    private fun getEffectiveVoiceFile(lang: String): String {
        val prefs = getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
        val externalVoice = prefs.getString(getExternalVoicePreferenceKey(lang), null)
        if (!externalVoice.isNullOrBlank()) {
            return externalVoice
        }

        return prefs.getString("selected_voice", "M1.json") ?: "M1.json"
    }

    private fun saveExternalVoiceSelection(lang: String, voiceFile: String) {
        getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(getExternalVoicePreferenceKey(lang), voiceFile)
            .apply()
    }

    private fun findSupportedLanguage(lang: String?): SupportedLanguage? {
        return SupportedLanguages.find(lang)
    }

    private fun parseVoiceRequest(voiceName: String?): Pair<SupportedLanguage, String>? {
        if (voiceName.isNullOrBlank()) {
            return null
        }

        SUPPORTED_LANGS.forEach { language ->
            VOICE_PROFILES.firstOrNull { buildVoiceName(language, it) == voiceName }?.let { profile ->
                return language to profile.fileName
            }
        }

        if (!voiceName.contains("-supertonic-")) {
            return null
        }

        val languagePrefix = voiceName.substringBefore("-supertonic-")
        val language = findSupportedLanguage(languagePrefix) ?: return null
        val token = voiceName.substringAfter("-supertonic-")

        val profile = VOICE_PROFILES.firstOrNull { buildVoiceName(language, it) == voiceName }
            ?: findVoiceProfileByCode(token)
            ?: findVoiceProfileByCode(token.substringBefore('-'))
            ?: findVoiceProfileByFileName(token)
            ?: VOICE_PROFILES.firstOrNull { token.contains(it.code, ignoreCase = true) }
            ?: return null

        return language to profile.fileName
    }

    private fun buildVoiceName(language: SupportedLanguage, profile: VoiceProfile): String {
        return "${profile.displayName} (${profile.code}, ${language.appCode})"
    }

    private fun findVoiceProfileByCode(code: String): VoiceProfile? {
        return VOICE_PROFILES.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }

    private fun findVoiceProfileByFileName(fileName: String): VoiceProfile? {
        return VOICE_PROFILES.firstOrNull { it.fileName.equals(fileName, ignoreCase = true) }
    }

    private fun resolveStyleFile(lang: String, voiceFile: String): File {
        return AssetInstaller.resolveStyleFile(this, voiceFile, lang)
    }

    private fun startEngineInitialization(lang: String, forceReset: Boolean = false) {
        requestedModelVersion = AssetInstaller.preferredModelVersion(lang)
        val intraOpThreads = SynthesisPreferences.getIntraOpThreads(
            getSharedPreferences(SynthesisPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        )
        requestedIntraOpThreads = intraOpThreads
        initJob?.cancel()
        initJob = serviceScope.async(Dispatchers.IO) {
            val preparedModel = AssetInstaller.prepareModel(this@SupertonicTextToSpeechService, lang)
            if (preparedModel == null) {
                Log.e("SupertonicTTS", "No compatible model assets available for language=$lang")
                return@async false
            }

            if (forceReset) {
                SupertonicTTS.release()
            }

            SupertonicTTS.initialize(
                preparedModel.modelPath,
                preparedModel.libPath,
                intraOpThreads
            )
        }
    }

    private suspend fun ensureEngineReady(lang: String): Boolean {
        val preferredVersion = AssetInstaller.preferredModelVersion(lang)
        val intraOpThreads = SynthesisPreferences.getIntraOpThreads(
            getSharedPreferences(SynthesisPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        )
        if (requestedModelVersion != preferredVersion || requestedIntraOpThreads != intraOpThreads) {
            startEngineInitialization(lang)
        }

        initJob?.let {
            val initialized = try {
                it.await()
            } catch (_: CancellationException) {
                false
            }

            if (initialized && SupertonicTTS.getSoC() != -1) {
                return true
            }
        }

        startEngineInitialization(lang)
        return try {
            initJob?.await() == true
        } catch (_: CancellationException) {
            false
        }
    }

    private fun formatMillis(nanos: Long): String {
        return String.format(Locale.ROOT, "%.3f", TransitionGapTracker.nanosToMillis(nanos))
    }

    private fun formatOptionalMillis(nanos: Long?): String {
        return nanos?.let(::formatMillis) ?: "na"
    }
}
