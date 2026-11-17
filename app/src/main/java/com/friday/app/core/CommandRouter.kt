package com.friday.app.core

import java.text.Normalizer

enum class VoiceMode(val label: String, val description: String) {
    PUSH_TO_TALK("Push to talk", "Tap the microphone for each request"),
    CONTINUOUS("Continuous", "FRIDAY listens again after each spoken reply"),
    TEXT_ONLY("Text only", "Microphone and spoken replies are off")
    // Wake-word mode is deliberately absent: no on-device hotword engine ships with FRIDAY.
}

enum class InferenceBackend(val label: String) { ON_DEVICE("Phone (on-device)"), WINDOWS_WORKER("Windows worker") }

/** Result of routing one user utterance. Only [Conversation] and [Summarize] reach the LLM. */
sealed interface AssistantCommand {
    /** Category used for logging/diagnostics. */
    val kind: Kind
    enum class Kind { CONVERSATION, CONFIGURATION, SYSTEM }

    data class Conversation(val text: String) : AssistantCommand { override val kind = Kind.CONVERSATION }
    data class SetLanguage(val language: AppLanguage) : AssistantCommand { override val kind = Kind.CONFIGURATION }
    data class SetPersonality(val query: String) : AssistantCommand { override val kind = Kind.CONFIGURATION }
    data class SetTemperature(val value: Float) : AssistantCommand { override val kind = Kind.CONFIGURATION }
    data class SetTopP(val value: Float) : AssistantCommand { override val kind = Kind.CONFIGURATION }
    data class SetMaxTokens(val value: Int) : AssistantCommand { override val kind = Kind.CONFIGURATION }
    data class SetVoiceMode(val mode: VoiceMode) : AssistantCommand { override val kind = Kind.CONFIGURATION }
    data class SetBackend(val backend: InferenceBackend) : AssistantCommand { override val kind = Kind.CONFIGURATION }
    data object StopSpeaking : AssistantCommand { override val kind = Kind.SYSTEM }
    data object Repeat : AssistantCommand { override val kind = Kind.SYSTEM }
    data object Summarize : AssistantCommand { override val kind = Kind.SYSTEM }
    data object Status : AssistantCommand { override val kind = Kind.SYSTEM }
    /** A recognised command whose value was invalid; carries a user-facing reason. */
    data class Rejected(val reason: String) : AssistantCommand { override val kind = Kind.SYSTEM }
}

object CommandRouter {
    private val englishWake = Regex("^(?:(?:hey|hi|hello|ok|okay)\\s+)?friday\\b[\\s,.:!?-]*", RegexOption.IGNORE_CASE)
    private val teluguWake = Regex("^(?:హే\\s*|హాయ్\\s*)?ఫ్రైడే[\\s,.:!?-]*")

    /** Removes a leading wake phrase ("Hey Friday," / "ఫ్రైడే,"). Returns the remaining request. */
    fun stripWakeWord(text: String): String {
        val trimmed = text.trim()
        val stripped = englishWake.replaceFirst(trimmed, "").let { teluguWake.replaceFirst(it, "") }
        return stripped.trim().ifEmpty { if (stripped.length != trimmed.length) "" else trimmed }
    }

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
        .replace("‌", "").replace("‍", "")
        .replace("ళ్ల", "ళ్ళ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun englishKey(text: String) = text.lowercase().replace(Regex("[^a-z0-9.\\s-]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')

    fun route(raw: String): AssistantCommand {
        val request = stripWakeWord(normalize(raw))
        if (request.isBlank()) return AssistantCommand.Conversation(raw.trim())
        return routeTelugu(request) ?: routeEnglish(englishKey(request)) ?: AssistantCommand.Conversation(request)
    }

    // ---------------------------------------------------------------- English
    private val langPatterns = listOf(
        Regex("^(?:please )?(?:switch|change|set)(?: the)?(?: response| reply)?(?: language)? (?:to|into) (telugu|english)(?: language)?(?: please)?$"),
        Regex("^(?:please )?(?:speak|talk|reply|respond|answer)(?: to me)?(?: only)? in (telugu|english)(?: from now on| now)?(?: please)?$"),
        Regex("^(?:use )?(telugu|english) (?:mode|language)$")
    )
    private val personalityPatterns = listOf(
        Regex("^(?:please )?(?:change|switch|set)(?: the| your)? personality (?:to|as) (.+)$"),
        Regex("^(?:activate|use|enable|load) (?:the )?(.+?) personality$"),
        Regex("^(?:switch|change) to (?:the )?(.+?) personality$")
    )
    private val numberToken = "([a-z0-9. -]+)"
    private val temperaturePattern = Regex("^(?:please )?(?:set|change|make)(?: the)? temperature (?:to |at |as )?$numberToken$")
    private val topPPattern = Regex("^(?:please )?(?:set|change)(?: the)? top[ -]?p (?:to |at |as )?$numberToken$")
    private val maxTokensPattern = Regex("^(?:please )?(?:set|change)(?: the)? (?:max|maximum)(?: output)? (?:tokens|token limit|length) (?:to |at )?$numberToken(?: tokens)?$")
    private val stopPattern = Regex("^(?:please )?(?:stop|stop it|stop speaking|stop talking|stop the voice|be quiet|quiet|silence|shut up|enough|cancel speech)(?: please)?$")
    private val repeatPattern = Regex("^(?:please )?(?:repeat(?: that| it| the last answer| your last answer| the answer)?|say (?:that|it) again|come again|what did you say)(?: please)?$")
    private val summarizePattern = Regex("^(?:please )?(?:summari[sz]e|give me a summary of|recap)(?: this| the| our)? (?:conversation|chat|discussion)(?: so far)?(?: please)?$")
    private val statusPattern = Regex("^(?:status|system status|what is your status|are you connected)$")

    private fun routeEnglish(key: String): AssistantCommand? {
        langPatterns.forEach { p -> p.find(key)?.let { return AssistantCommand.SetLanguage(if (it.groupValues[1] == "telugu") AppLanguage.TELUGU else AppLanguage.ENGLISH) } }
        if (stopPattern.matches(key)) return AssistantCommand.StopSpeaking
        if (repeatPattern.matches(key)) return AssistantCommand.Repeat
        if (summarizePattern.matches(key)) return AssistantCommand.Summarize
        if (statusPattern.matches(key)) return AssistantCommand.Status
        temperaturePattern.find(key)?.let { m ->
            val v = SpokenNumber.parse(m.groupValues[1]) ?: return AssistantCommand.Rejected("I didn't catch the temperature value.")
            return if (v in 0f..2f) AssistantCommand.SetTemperature(v) else AssistantCommand.Rejected("Temperature must be between 0 and 2.")
        }
        topPPattern.find(key)?.let { m ->
            val v = SpokenNumber.parse(m.groupValues[1]) ?: return AssistantCommand.Rejected("I didn't catch the top-p value.")
            return if (v > 0f && v <= 1f) AssistantCommand.SetTopP(v) else AssistantCommand.Rejected("Top-p must be greater than 0 and at most 1.")
        }
        maxTokensPattern.find(key)?.let { m ->
            val v = SpokenNumber.parse(m.groupValues[1])?.toInt() ?: return AssistantCommand.Rejected("I didn't catch the token limit.")
            return if (v in 32..8192) AssistantCommand.SetMaxTokens(v) else AssistantCommand.Rejected("Max tokens must be between 32 and 8192.")
        }
        personalityPatterns.forEach { p -> p.find(key)?.let { return AssistantCommand.SetPersonality(it.groupValues[1].trim()) } }
        when {
            Regex("^(?:switch to |use |enable )?(?:text only|text-only|text) mode$").matches(key) -> return AssistantCommand.SetVoiceMode(VoiceMode.TEXT_ONLY)
            Regex("^(?:switch to |use |enable |start )?continuous(?: conversation| listening)?(?: mode)?$").matches(key) -> return AssistantCommand.SetVoiceMode(VoiceMode.CONTINUOUS)
            Regex("^(?:switch to |use |enable )?push to talk(?: mode)?$").matches(key) -> return AssistantCommand.SetVoiceMode(VoiceMode.PUSH_TO_TALK)
            Regex("^(?:switch to |use )(?:the )?(?:windows )?(?:worker|pc|computer|laptop|desktop)(?: model| worker)?$").matches(key) -> return AssistantCommand.SetBackend(InferenceBackend.WINDOWS_WORKER)
            Regex("^(?:switch to |use )(?:the )?(?:phone|on-device|on device|local phone|mobile)(?: model)?$").matches(key) -> return AssistantCommand.SetBackend(InferenceBackend.ON_DEVICE)
        }
        return null
    }

    // ---------------------------------------------------------------- Telugu
    private val teluguVerbsLanguage = listOf("మాట్లాడు", "మాట్లాడండి", "మార్చు", "సమాధానం ఇవ్వు", "జవాబు ఇవ్వు")
    private val teluguStop = listOf("ఆపు", "ఆపేయ్", "ఆపండి", "ఆపేయి")
    private val teluguRepeat = listOf("మళ్ళీ చెప్పు", "మళ్ళీ చెప్పండి", "మరోసారి చెప్పు", "తిరిగి చెప్పు")

    private fun routeTelugu(text: String): AssistantCommand? {
        if (!Script.containsTelugu(text)) return null
        val t = text.replace(Regex("[.!?,।]"), " ").replace(Regex("\\s+"), " ").trim()
        val compact = t.replace(" ", "")
        val words = t.split(" ").size
        // Language switch: short utterance naming a language plus a "speak/change" verb.
        if (words <= 6 && teluguVerbsLanguage.any { compact.endsWith(it.replace(" ", "")) }) {
            if (compact.contains("తెలుగు")) return AssistantCommand.SetLanguage(AppLanguage.TELUGU)
            if (compact.contains("ఇంగ్లీష్") || compact.contains("ఇంగ్లిష్") || compact.contains("ఆంగ్లం") || compact.contains("ఆంగ్ల")) return AssistantCommand.SetLanguage(AppLanguage.ENGLISH)
        }
        if (words <= 5 && teluguStop.any { compact.endsWith(it) }) return AssistantCommand.StopSpeaking
        if (words <= 6 && teluguRepeat.any { compact.endsWith(it.replace(" ", "")) }) return AssistantCommand.Repeat
        if (words <= 6 && (compact.contains("సారాంశం") || compact.contains("సంగ్రహించు")) && (compact.contains("సంభాషణ") || compact.contains("చాట్"))) return AssistantCommand.Summarize
        return null
    }
}

/** Parses spoken or typed numbers such as "0.7", "point seven", "zero point seven", "1", "two thousand". */
object SpokenNumber {
    private val units = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    fun parse(raw: String): Float? {
        val text = raw.trim().lowercase().replace("-", " ").replace(Regex("\\s+"), " ")
        text.replace(" ", "").toFloatOrNull()?.let { return it }
        val tokens = text.split(" ").filter { it.isNotBlank() && it != "and" }
        if (tokens.isEmpty()) return null
        val pointIndex = tokens.indexOf("point")
        val wholeTokens = if (pointIndex >= 0) tokens.subList(0, pointIndex) else tokens
        val fracTokens = if (pointIndex >= 0) tokens.subList(pointIndex + 1, tokens.size) else emptyList()
        val whole = if (wholeTokens.isEmpty()) 0 else parseWhole(wholeTokens) ?: return null
        if (pointIndex < 0) return whole.toFloat()
        if (fracTokens.isEmpty()) return null
        val digits = fracTokens.map { t -> t.toIntOrNull()?.takeIf { it in 0..9 } ?: units[t]?.takeIf { it in 0..9 } ?: return null }
        return "$whole.${digits.joinToString("")}".toFloat()
    }

    private fun parseWhole(tokens: List<String>): Int? {
        var total = 0
        var current = 0
        for (t in tokens) {
            val n = t.toIntOrNull()
            when {
                n != null -> current += n
                t == "hundred" -> current = (if (current == 0) 1 else current) * 100
                t == "thousand" -> { total += (if (current == 0) 1 else current) * 1000; current = 0 }
                units.containsKey(t) -> current += units.getValue(t)
                else -> return null
            }
        }
        return total + current
    }
}
