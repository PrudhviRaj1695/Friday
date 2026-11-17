package com.friday.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ResponseLength(val label: String, val instruction: String) {
    SHORT("Short", "Keep replies brief: two to four sentences unless the user asks for more."),
    BALANCED("Balanced", "Be concise by default but include the detail needed to be genuinely useful."),
    DETAILED("Detailed", "Give thorough, well-structured answers with examples where they help.")
}

/** Language a personality prefers. FOLLOW_APP uses the language selected in settings. */
enum class LanguagePreference(val label: String) { FOLLOW_APP("Follow app setting"), ENGLISH("English"), TELUGU("తెలుగు") }

data class PersonalityProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",
    val tone: String = "",
    val language: LanguagePreference = LanguagePreference.FOLLOW_APP,
    /** Optional temperature override; null keeps the active AI configuration's temperature. */
    val temperature: Float? = null,
    val responseLength: ResponseLength = ResponseLength.BALANCED,
    val builtIn: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("description", description)
        .put("systemPrompt", systemPrompt).put("tone", tone).put("language", language.name)
        .put("temperature", temperature?.toDouble() ?: JSONObject.NULL).put("responseLength", responseLength.name).put("builtIn", builtIn)

    companion object {
        fun newId() = "p-" + UUID.randomUUID().toString().take(8)
        fun fromJson(j: JSONObject) = PersonalityProfile(
            id = j.getString("id"), name = j.optString("name", "Custom"), description = j.optString("description"),
            systemPrompt = j.optString("systemPrompt"), tone = j.optString("tone"),
            language = runCatching { LanguagePreference.valueOf(j.optString("language")) }.getOrDefault(LanguagePreference.FOLLOW_APP),
            temperature = if (j.isNull("temperature") || !j.has("temperature")) null else j.getDouble("temperature").toFloat(),
            responseLength = runCatching { ResponseLength.valueOf(j.optString("responseLength")) }.getOrDefault(ResponseLength.BALANCED),
            builtIn = j.optBoolean("builtIn", false)
        )
    }

    /** Language actually used for replies when this personality is active. */
    fun effectiveLanguage(app: AppLanguage): AppLanguage = when (language) {
        LanguagePreference.FOLLOW_APP -> app
        LanguagePreference.ENGLISH -> AppLanguage.ENGLISH
        LanguagePreference.TELUGU -> AppLanguage.TELUGU
    }
}

object BuiltInPersonalities {
    val ALL = listOf(
        PersonalityProfile("friendly", "Friendly Assistant", "Warm, natural and supportive",
            "Be warm, natural, supportive and conversational without being overly agreeable.", "Warm and casual", builtIn = true),
        PersonalityProfile("jarvis", "JARVIS", "Composed, witty, mission-focused aide",
            "Act as a composed, highly capable AI aide in the style of a refined British butler-engineer. Be precise, calm and subtly witty. Address the user respectfully, anticipate the next useful step, and keep status updates crisp.",
            "Calm, precise, dry wit", responseLength = ResponseLength.SHORT, builtIn = true),
        PersonalityProfile("engineer", "Professional Engineer", "Senior engineer: precise and pragmatic",
            "Respond like a senior software engineer. Be technically precise and pragmatic, state assumptions, and include implementation details, edge cases and trade-offs when useful.",
            "Technical and direct", temperature = 0.3f, responseLength = ResponseLength.DETAILED, builtIn = true),
        PersonalityProfile("critic", "Critical Thinking Advisor", "Challenges assumptions, weighs evidence",
            "Act as a rigorous critical-thinking advisor. Identify hidden assumptions, weak evidence and risks, steelman alternatives, and end with a clear, reasoned recommendation. Do not flatter.",
            "Candid and analytical", temperature = 0.4f, builtIn = true),
        PersonalityProfile("creative", "Creative Director", "Bold ideas, vivid language",
            "Act as an imaginative creative director. Offer original, varied ideas and vivid language, then help refine the strongest ones. Mark speculation clearly.",
            "Expressive and energetic", temperature = 1.1f, builtIn = true),
        PersonalityProfile("companion", "Personal Companion", "Caring, attentive conversation partner",
            "Be a caring, attentive companion. Listen closely, reflect feelings briefly, ask gentle follow-up questions and keep the conversation natural. Never pretend to be human.",
            "Gentle and empathetic", responseLength = ResponseLength.SHORT, builtIn = true)
    )
    const val DEFAULT_ID = "friendly"

    /** Maps names used by older FRIDAY versions to the new built-in profiles. */
    val LEGACY_NAMES = mapOf("Friendly" to "friendly", "Engineer" to "engineer")
}

/** Minimal key-value persistence abstraction so repositories are testable without Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class PersonalityRepository(private val store: KeyValueStore) {
    companion object {
        const val KEY_PROFILES = "personality_profiles_v2"
        const val KEY_ACTIVE = "personality_active_id_v2"
        const val LEGACY_NAME = "active_personality"
        const val LEGACY_PROMPT = "personality_prompt"
    }

    fun load(): List<PersonalityProfile> {
        val raw = store.getString(KEY_PROFILES)
        if (raw == null) {
            val migrated = migrateLegacy()
            save(migrated)
            return migrated
        }
        val custom = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { PersonalityProfile.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        // Built-ins always come from code (so improvements ship), followed by user profiles.
        val userProfiles = custom.filterNot { it.builtIn }
        return BuiltInPersonalities.ALL + userProfiles
    }

    fun save(all: List<PersonalityProfile>) {
        store.putString(KEY_PROFILES, JSONArray().apply { all.filterNot { it.builtIn }.forEach { put(it.toJson()) } }.toString())
    }

    fun activeId(profiles: List<PersonalityProfile>): String {
        val id = store.getString(KEY_ACTIVE)
        return if (id != null && profiles.any { it.id == id }) id else BuiltInPersonalities.DEFAULT_ID
    }

    fun setActive(id: String) = store.putString(KEY_ACTIVE, id)

    fun upsert(profile: PersonalityProfile): List<PersonalityProfile> {
        require(!profile.builtIn) { "Built-in profiles cannot be modified; duplicate it instead." }
        val all = load()
        val updated = if (all.any { it.id == profile.id }) all.map { if (it.id == profile.id) profile else it } else all + profile
        save(updated)
        return load()
    }

    fun duplicate(id: String): Pair<List<PersonalityProfile>, PersonalityProfile>? {
        val source = load().firstOrNull { it.id == id } ?: return null
        val copy = source.copy(id = PersonalityProfile.newId(), name = source.name + " (copy)", builtIn = false)
        return upsert(copy) to copy
    }

    fun delete(id: String): List<PersonalityProfile> {
        val all = load()
        val target = all.firstOrNull { it.id == id } ?: return all
        require(!target.builtIn) { "Built-in profiles cannot be deleted." }
        save(all.filterNot { it.id == id })
        if (store.getString(KEY_ACTIVE) == id) setActive(BuiltInPersonalities.DEFAULT_ID)
        return load()
    }

    /** Finds a profile by spoken name ("jarvis", "engineer", "creative director"). */
    fun findByQuery(profiles: List<PersonalityProfile>, query: String): PersonalityProfile? {
        val q = query.lowercase().removePrefix("the ").removeSuffix(" mode").trim()
        if (q.isBlank()) return null
        return profiles.firstOrNull { it.name.equals(q, true) }
            ?: profiles.firstOrNull { it.name.lowercase().split(" ").any { w -> w == q } }
            ?: profiles.firstOrNull { it.name.lowercase().contains(q) || q.contains(it.name.lowercase()) }
    }

    private fun migrateLegacy(): List<PersonalityProfile> {
        val legacyName = store.getString(LEGACY_NAME)
        val legacyPrompt = store.getString(LEGACY_PROMPT).orEmpty()
        val base = BuiltInPersonalities.ALL
        if (legacyName.isNullOrBlank()) return base
        val mapped = BuiltInPersonalities.LEGACY_NAMES[legacyName]
        if (mapped != null && base.first { it.id == mapped }.systemPrompt == legacyPrompt) { setActive(mapped); return base }
        if (legacyPrompt.isBlank()) { mapped?.let(::setActive); return base }
        // Preserve the user's previous custom personality as an editable profile.
        val custom = PersonalityProfile(PersonalityProfile.newId(), legacyName, "Imported from your previous settings", legacyPrompt)
        setActive(custom.id)
        return base + custom
    }
}
