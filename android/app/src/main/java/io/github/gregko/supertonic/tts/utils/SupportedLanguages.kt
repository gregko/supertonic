package io.github.gregko.supertonic.tts.utils

import java.util.Locale
import java.util.MissingResourceException

data class SupportedLanguage(
    val appCode: String,
    val displayName: String,
    val localeTag: String,
    val aliases: Set<String> = emptySet()
) {
    val locale: Locale
        get() = Locale.forLanguageTag(localeTag)

    val iso3Language: String
        get() = try {
            locale.getISO3Language()
        } catch (_: MissingResourceException) {
            ""
        }

    val iso3Country: String
        get() = try {
            locale.getISO3Country()
        } catch (_: MissingResourceException) {
            ""
        }

    fun matches(language: String): Boolean {
        val normalized = SupportedLanguages.normalizeInput(language)
        val primary = normalized.substringBefore('-')
        val matchValues = buildSet {
            add(appCode)
            add(locale.language.lowercase(Locale.ROOT))
            add(iso3Language.lowercase(Locale.ROOT))
            addAll(aliases.map { it.lowercase(Locale.ROOT) })
        }
        return normalized in matchValues || primary in matchValues
    }
}

object SupportedLanguages {
    const val DEFAULT_CODE = "en"

    val ALL: List<SupportedLanguage> = listOf(
        SupportedLanguage("en", "English", "en-US", setOf("eng")),
        SupportedLanguage("ko", "Korean", "ko-KR", setOf("kor")),
        SupportedLanguage("ja", "Japanese", "ja-JP", setOf("jpn")),
        SupportedLanguage("ar", "Arabic", "ar", setOf("ara")),
        SupportedLanguage("bg", "Bulgarian", "bg-BG", setOf("bul")),
        SupportedLanguage("cs", "Czech", "cs-CZ", setOf("ces", "cze")),
        SupportedLanguage("da", "Danish", "da-DK", setOf("dan")),
        SupportedLanguage("de", "German", "de-DE", setOf("deu", "ger")),
        SupportedLanguage("el", "Greek", "el-GR", setOf("ell", "gre")),
        SupportedLanguage("es", "Spanish", "es-ES", setOf("spa")),
        SupportedLanguage("et", "Estonian", "et-EE", setOf("est")),
        SupportedLanguage("fi", "Finnish", "fi-FI", setOf("fin")),
        SupportedLanguage("fr", "French", "fr-FR", setOf("fra", "fre")),
        SupportedLanguage("hi", "Hindi", "hi-IN", setOf("hin")),
        SupportedLanguage("hr", "Croatian", "hr-HR", setOf("hrv")),
        SupportedLanguage("hu", "Hungarian", "hu-HU", setOf("hun")),
        SupportedLanguage("id", "Indonesian", "id-ID", setOf("ind")),
        SupportedLanguage("it", "Italian", "it-IT", setOf("ita")),
        SupportedLanguage("lt", "Lithuanian", "lt-LT", setOf("lit")),
        SupportedLanguage("lv", "Latvian", "lv-LV", setOf("lav")),
        SupportedLanguage("nl", "Dutch", "nl-NL", setOf("nld", "dut")),
        SupportedLanguage("pl", "Polish", "pl-PL", setOf("pol")),
        SupportedLanguage("pt", "Portuguese", "pt-PT", setOf("por")),
        SupportedLanguage("ro", "Romanian", "ro-RO", setOf("ron", "rum")),
        SupportedLanguage("ru", "Russian", "ru-RU", setOf("rus")),
        SupportedLanguage("sk", "Slovak", "sk-SK", setOf("slk", "slo")),
        SupportedLanguage("sl", "Slovenian", "sl-SI", setOf("slv")),
        SupportedLanguage("sv", "Swedish", "sv-SE", setOf("swe")),
        SupportedLanguage("tr", "Turkish", "tr-TR", setOf("tur")),
        SupportedLanguage("uk", "Ukrainian", "uk-UA", setOf("ukr")),
        SupportedLanguage("vi", "Vietnamese", "vi-VN", setOf("vie"))
    )

    val displayNameToCode: Map<String, String> = ALL
        .sortedBy { it.displayName }
        .associate { it.displayName to it.appCode }

    fun normalizeInput(language: String): String {
        return language.trim().lowercase(Locale.ROOT).replace('_', '-')
    }

    fun find(language: String?): SupportedLanguage? {
        if (language.isNullOrBlank()) {
            return null
        }
        return ALL.firstOrNull { it.matches(language) }
    }

    fun normalizeOrDefault(language: String?): String {
        return find(language)?.appCode ?: DEFAULT_CODE
    }

    fun isSupported(language: String?): Boolean {
        return find(language) != null
    }
}
