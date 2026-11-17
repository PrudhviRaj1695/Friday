package com.friday.app.core

import org.json.JSONArray
import org.json.JSONObject

/** User-controlled sampling and request settings. Every field maps to a real backend parameter. */
data class GenerationSettings(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val maxOutputTokens: Int = 1024,
    val contextLength: Int = 4096,
    val streaming: Boolean = true,
    /** Extra system instructions appended after the built-in FRIDAY prompt. */
    val systemPrompt: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("temperature", temperature.toDouble()).put("topP", topP.toDouble()).put("topK", topK)
        .put("maxOutputTokens", maxOutputTokens).put("contextLength", contextLength)
        .put("streaming", streaming).put("systemPrompt", systemPrompt)

    companion object {
        val DEFAULT = GenerationSettings()
        fun fromJson(j: JSONObject) = GenerationSettings(
            temperature = j.optDouble("temperature", 0.7).toFloat(),
            topP = j.optDouble("topP", 0.95).toFloat(),
            topK = j.optInt("topK", 40),
            maxOutputTokens = j.optInt("maxOutputTokens", 1024),
            contextLength = j.optInt("contextLength", 4096),
            streaming = j.optBoolean("streaming", true),
            systemPrompt = j.optString("systemPrompt", "")
        )
    }
}

/**
 * What a particular backend/model can honour. The UI disables controls that are not supported
 * and [GenerationSettingsValidator] reports which values were clamped or ignored.
 */
data class ModelCapabilities(
    val supportsTemperature: Boolean = true,
    val supportsTopP: Boolean = true,
    val supportsTopK: Boolean = true,
    val supportsMaxOutputTokens: Boolean = true,
    val supportsContextLength: Boolean = true,
    val supportsStreaming: Boolean = true,
    val maxContextLength: Int = 4096,
    val maxOutputTokens: Int = 4096,
    /** True when changing the context window requires reloading the model (on-device LiteRT-LM). */
    val contextChangeRequiresReload: Boolean = false
) {
    companion object {
        /** LiteRT-LM 0.18: SamplerConfig(topK, topP, temperature), ConversationConfig.maxOutputToken, EngineConfig.maxNumTokens. */
        fun onDevice(maxContext: Int) = ModelCapabilities(maxContextLength = maxContext, maxOutputTokens = maxContext, contextChangeRequiresReload = true)
        /** Ollama options: temperature, top_p, top_k, num_predict, num_ctx. */
        fun ollama(maxContext: Int = 32768) = ModelCapabilities(maxContextLength = maxContext, maxOutputTokens = 8192)
    }
}

data class ValidatedSettings(val settings: GenerationSettings, val notes: List<String>)

object GenerationSettingsValidator {
    const val MIN_CONTEXT = 512
    const val MIN_OUTPUT = 32

    fun validate(input: GenerationSettings, caps: ModelCapabilities): ValidatedSettings {
        val notes = mutableListOf<String>()
        fun <T : Comparable<T>> clamp(name: String, v: T, lo: T, hi: T): T {
            val c = if (v < lo) lo else if (v > hi) hi else v
            if (c != v) notes += "$name adjusted from $v to $c (allowed $lo–$hi)."
            return c
        }
        val ctx = clamp("Context length", input.contextLength, MIN_CONTEXT, caps.maxContextLength)
        var maxOut = clamp("Max output tokens", input.maxOutputTokens, MIN_OUTPUT, caps.maxOutputTokens)
        if (maxOut >= ctx) {
            val fixed = (ctx / 2).coerceAtLeast(MIN_OUTPUT)
            notes += "Max output tokens reduced to $fixed so the prompt still fits in the $ctx-token context."
            maxOut = fixed
        }
        val settings = input.copy(
            temperature = clamp("Temperature", input.temperature, 0f, 2f),
            topP = clamp("Top-P", input.topP, 0.01f, 1f),
            topK = clamp("Top-K", input.topK, 1, 200),
            maxOutputTokens = maxOut,
            contextLength = ctx,
            streaming = input.streaming && caps.supportsStreaming,
            systemPrompt = input.systemPrompt.take(4000)
        )
        if (!caps.supportsTemperature) notes += "This model ignores temperature."
        if (!caps.supportsTopP) notes += "This model ignores top-p."
        if (!caps.supportsTopK) notes += "This model ignores top-k."
        if (!caps.supportsMaxOutputTokens) notes += "This model ignores the output token limit."
        if (!caps.supportsContextLength) notes += "This model uses a fixed context window."
        if (input.streaming && !caps.supportsStreaming) notes += "Streaming is not supported; full replies are shown when complete."
        if (input.systemPrompt.length > 4000) notes += "System prompt truncated to 4000 characters."
        return ValidatedSettings(settings, notes)
    }
}

/** A named, saved AI configuration the user can switch between. */
data class AiPreset(val id: String, val name: String, val settings: GenerationSettings) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("settings", settings.toJson())
    companion object {
        fun fromJson(j: JSONObject) = AiPreset(j.getString("id"), j.optString("name", "Preset"), GenerationSettings.fromJson(j.optJSONObject("settings") ?: JSONObject()))
        fun listToJson(list: List<AiPreset>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
        fun listFromJson(raw: String?): List<AiPreset> = runCatching {
            val a = JSONArray(raw ?: "[]")
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        val BUILT_IN = listOf(
            AiPreset("preset-precise", "Precise", GenerationSettings(temperature = 0.2f, topP = 0.8f, topK = 20, maxOutputTokens = 768)),
            AiPreset("preset-balanced", "Balanced", GenerationSettings()),
            AiPreset("preset-creative", "Creative", GenerationSettings(temperature = 1.1f, topP = 0.98f, topK = 80, maxOutputTokens = 1536))
        )
    }
}
