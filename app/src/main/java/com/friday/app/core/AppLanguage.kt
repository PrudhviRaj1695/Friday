package com.friday.app.core

/** Languages FRIDAY supports end to end (speech input, replies and speech output). */
enum class AppLanguage(val displayName: String, val nativeName: String, val bcp47: String) {
    ENGLISH("English", "English", "en-US"),
    TELUGU("Telugu", "తెలుగు", "te-IN");

    companion object {
        fun fromStored(value: String?): AppLanguage =
            entries.firstOrNull { it.name.equals(value, true) || it.displayName.equals(value, true) } ?: ENGLISH
    }
}

object Script {
    private fun isTelugu(c: Char) = c in 'ఀ'..'౿'

    fun teluguRatio(text: String): Float {
        var letters = 0
        var telugu = 0
        for (c in text) {
            if (isTelugu(c)) { telugu++; letters++ } else if (c.isLetter()) letters++
        }
        return if (letters == 0) 0f else telugu.toFloat() / letters
    }

    fun containsTelugu(text: String): Boolean = text.any(::isTelugu)

    /** Language that text-to-speech should use for this text. */
    fun dominantLanguage(text: String): AppLanguage = if (teluguRatio(text) >= 0.3f) AppLanguage.TELUGU else AppLanguage.ENGLISH
}
