package com.brahmadeo.supertonic.tts.service

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.brahmadeo.supertonic.tts.SupertonicTTS
import com.brahmadeo.supertonic.tts.utils.AssetInstaller
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class SupertonicTextToSpeechService : TextToSpeechService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var initJob: Deferred<Boolean>? = null
    private var requestedModelVersion: String? = null

    companion object {
        const val VOLUME_BOOST_FACTOR = 2.5f
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
        private val SUPPORTED_LANGS = listOf(
            SupportedLanguage("en", Locale.US, "eng", "USA"),
            SupportedLanguage("es", Locale.forLanguageTag("es-ES"), "spa", "ESP"),
            SupportedLanguage("pt", Locale.forLanguageTag("pt-PT"), "por", "PRT"),
            SupportedLanguage("fr", Locale.FRANCE, "fra", "FRA"),
            SupportedLanguage("ko", Locale.KOREA, "kor", "KOR")
        )
    }

    private data class SupportedLanguage(
        val appCode: String,
        val locale: Locale,
        val iso3Language: String,
        val iso3Country: String
    )

    private data class VoiceProfile(
        val code: String,
        val fileName: String,
        val displayName: String
    )

    private fun applyVolumeBoost(pcmData: ByteArray, gain: Float): ByteArray {
        if (gain == 1.0f) return pcmData
        val size = pcmData.size
        val boosted = ByteArray(size)
        val inBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val outBuffer = ByteBuffer.wrap(boosted).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = size / 2
        for (i in 0 until count) {
            val sample = inBuffer.get(i)
            var scaled = (sample * gain).toInt()
            if (scaled > 32767) scaled = 32767
            if (scaled < -32768) scaled = -32768
            outBuffer.put(i, scaled.toShort())
        }
        return boosted
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("SupertonicTTS", "Service created")
        com.brahmadeo.supertonic.tts.utils.LexiconManager.load(this)

        startEngineInitialization(getSelectedLang())
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val supported = findSupportedLanguage(lang) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        return if (!country.isNullOrEmpty() && country.equals(supported.iso3Country, ignoreCase = true)) {
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
        val prefs = getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
        val selected = prefs.getString("selected_voice", "M1.json") ?: "M1.json"

        val language = findSupportedLanguage(lang) ?: findSupportedLanguage(getSelectedLang()) ?: SUPPORTED_LANGS.first()
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
    }

    private fun normalizeLanguage(lang: String?): String {
        if (lang == null) return "en"
        val l = lang.lowercase(Locale.ROOT)
        return when {
            l.startsWith("en") -> "en"
            l.startsWith("ko") -> "ko"
            l.startsWith("kor") -> "ko"
            l.startsWith("es") -> "es"
            l.startsWith("spa") -> "es"
            l.startsWith("pt") -> "pt"
            l.startsWith("por") -> "pt"
            l.startsWith("fr") -> "fr"
            l.startsWith("fra") -> "fr"
            l.startsWith("fre") -> "fr"
            else -> "en"
        }
    }

    private val textNormalizer = com.brahmadeo.supertonic.tts.utils.TextNormalizer()

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        SupertonicTTS.setCancelled(false)
        val requestedVoice = request.voiceName
        val parsedVoice = parseVoiceRequest(requestedVoice)
        val requestLang = parsedVoice?.first?.appCode ?: normalizeLanguage(request.language)
        val engineReady = runBlocking {
            withTimeoutOrNull(15000) {
                ensureEngineReady(requestLang)
            } ?: false
        }
        if (!engineReady) {
            Log.e("SupertonicTTS", "Engine initialization timed out for language=$requestLang")
            callback.error()
            return
        }

        val rawText = request.charSequenceText?.toString() ?: return
        val effectiveSpeed = (request.speechRate / 100.0f).coerceIn(0.5f, 2.5f)
        callback.start(SupertonicTTS.getAudioSampleRate(), android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
        
        val localListener = object : SupertonicTTS.ProgressListener {
            override fun onProgress(sessionId: Long, current: Int, total: Int) {}
            override fun onAudioChunk(sessionId: Long, data: ByteArray) {
                val boostedData = applyVolumeBoost(data, VOLUME_BOOST_FACTOR)
                var offset = 0
                while (offset < boostedData.size) {
                    val length = Math.min(4096, boostedData.size - offset)
                    callback.audioAvailable(boostedData, offset, length)
                    offset += length
                }
            }
        }

        val prefs = getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
        val voiceFile = parsedVoice?.second ?: (prefs.getString("selected_voice", "M1.json") ?: "M1.json")

        val stylePath = resolveStyleFile(requestLang, voiceFile).absolutePath
        val steps = prefs.getInt("diffusion_steps", 5)

        try {
            val sentences = textNormalizer.splitIntoSentences(rawText)
            var success = true
            for (sentence in sentences) {
                if (SupertonicTTS.isCancelled()) { success = false; break }

                // Granular per-sentence detection
                // val sentenceLang = LanguageDetector.detect(sentence, requestLang)
                val sentenceLang = requestLang
                val normalizedText = textNormalizer.normalize(sentence, sentenceLang)

                SupertonicTTS.generateAudio(normalizedText, sentenceLang, stylePath, effectiveSpeed, 0.0f, steps, localListener)
            }
            if (success) callback.done() else callback.error()
        } finally {
            // Isolation handled in SupertonicTTS
        }
    }

    private fun getSelectedLang(): String {
        val prefs = getSharedPreferences("SupertonicPrefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString("selected_lang", "en") ?: "en"
    }

    private fun findSupportedLanguage(lang: String?): SupportedLanguage? {
        val normalized = normalizeLanguage(lang)
        return SUPPORTED_LANGS.firstOrNull { it.appCode == normalized }
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

            SupertonicTTS.initialize(preparedModel.modelPath, preparedModel.libPath)
        }
    }

    private suspend fun ensureEngineReady(lang: String): Boolean {
        val preferredVersion = AssetInstaller.preferredModelVersion(lang)
        if (requestedModelVersion != preferredVersion) {
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
}
