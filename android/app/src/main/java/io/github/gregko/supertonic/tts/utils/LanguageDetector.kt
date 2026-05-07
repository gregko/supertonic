package io.github.gregko.supertonic.tts.utils

object LanguageDetector {
    /**
     * Detects the language of a single sentence or text fragment.
     */
    fun detect(text: String, hint: String = "en"): String {
        // 1. Korean: Hangul Jamo (3131-314E), Vowels (314F-3163), Syllables (AC00-D7A3)
        if (Regex("[ㄱ-ㅎㅏ-ㅣ가-힣]").containsMatchIn(text)) return "ko"

        // Scripts that map cleanly to a single Supertonic 3 language.
        if (Regex("[\\u3040-\\u30ff\\u31f0-\\u31ff]").containsMatchIn(text)) return "ja"
        if (Regex("[\\u0600-\\u06ff]").containsMatchIn(text)) return "ar"
        if (Regex("[\\u0900-\\u097f]").containsMatchIn(text)) return "hi"
        if (Regex("[\\u0370-\\u03ff]").containsMatchIn(text)) return "el"
        if (Regex("[іїєґІЇЄҐ]").containsMatchIn(text)) return "uk"
        if (Regex("[\\u0400-\\u04ff]").containsMatchIn(text)) {
            val normalizedHint = SupportedLanguages.normalizeOrDefault(hint)
            return if (normalizedHint in listOf("bg", "ru", "uk")) normalizedHint else "ru"
        }
        
        // 2. Strong Unique Indicators
        // Portuguese: ã, õ
        if (Regex("[ãõ]", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "pt"
        
        // Spanish: ñ, ¿, ¡
        if (Regex("[ñ¿¡]", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "es"
        
        // French: è, ê, ë, î, ï, û, ù
        if (Regex("[èêëîïûù]", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "fr"
        
        // 3. Ambiguous Indicators
        
        // Check for 'ç' (French or Portuguese)
        if (Regex("ç", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            // Check for acute accents on vowels (Pt) vs others
            if (Regex("[áíóú]", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "pt"
            return "fr"
        }
        
        // Check for 'á', 'ó', 'í', 'ú' (Spanish or Portuguese)
        if (Regex("[áíóú]", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            // Pt uses circumflex [âêô]. Es does not.
            if (Regex("[âêô]", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "pt"
            
            // Whole words checks
            if (Regex("\\by\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "es"
            if (Regex("\\be\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "pt"
            
            if (Regex("\\bem\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "pt"
            if (Regex("\\ben\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "es"
            
            return "es" // Fallback bias
        }
        
        // Check for 'é' (Es, Pt, Fr)
        if (Regex("é", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            if (Regex("\\bet\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "fr"
            if (Regex("\\best\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "fr"
            if (Regex("\\by\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "es"
            if (Regex("\\bem\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "pt"
            if (Regex("\\ben\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "es"
            
            if (Regex("[àâô]", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "fr"
            
            val accentHint = SupportedLanguages.normalizeOrDefault(hint)
            if (accentHint in listOf("es", "pt", "fr")) return accentHint
            return "es"
        }
        
        // 5. English check (ASCII)
        val isRoman = text.all { it.code < 128 || it in ".,!?;:\"'()[]{}«»—– " }
        if (isRoman) {
            return if (SupportedLanguages.isSupported(hint)) {
                SupportedLanguages.normalizeOrDefault(hint)
            } else {
                "en"
            }
        }
        
        // 6. Fallback
        return if (SupportedLanguages.isSupported(hint)) {
            SupportedLanguages.normalizeOrDefault(hint)
        } else {
            "en"
        }
    }
}
