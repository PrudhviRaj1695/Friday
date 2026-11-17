package com.friday.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

class ConfigAndPersonalityTest {
    @Test fun `settings are clamped to model capabilities with notes`() {
        val v = GenerationSettingsValidator.validate(
            GenerationSettings(temperature = 3f, topP = 0f, maxOutputTokens = 9000, contextLength = 100_000),
            ModelCapabilities.onDevice(maxContext = 4096)
        )
        assertEquals(2f, v.settings.temperature)
        assertEquals(0.01f, v.settings.topP)
        assertEquals(4096, v.settings.contextLength)
        assertTrue(v.settings.maxOutputTokens < v.settings.contextLength)
        assertTrue(v.notes.size >= 3)
    }

    @Test fun `valid settings pass unchanged`() {
        val s = GenerationSettings(temperature = 0.5f, topP = 0.9f, topK = 30, maxOutputTokens = 512, contextLength = 2048)
        val v = GenerationSettingsValidator.validate(s, ModelCapabilities.ollama())
        assertEquals(s, v.settings)
        assertTrue(v.notes.isEmpty())
    }

    @Test fun `streaming disabled when unsupported`() {
        val v = GenerationSettingsValidator.validate(GenerationSettings(), ModelCapabilities(supportsStreaming = false))
        assertFalse(v.settings.streaming)
    }

    @Test fun `generation settings and presets round trip through json`() {
        val p = AiPreset("x", "Mine", GenerationSettings(0.3f, 0.8f, 12, 300, 2048, false, "Be brief"))
        assertEquals(listOf(p), AiPreset.listFromJson(AiPreset.listToJson(listOf(p))))
        assertTrue(AiPreset.listFromJson("not json").isEmpty())
    }

    @Test fun `personality crud and persistence`() {
        val store = MapStore()
        val repo = PersonalityRepository(store)
        val initial = repo.load()
        assertEquals(BuiltInPersonalities.ALL.size, initial.size)
        assertEquals("friendly", repo.activeId(initial))

        val custom = PersonalityProfile(PersonalityProfile.newId(), "Coach", "Motivates", "Be a coach", "Upbeat", LanguagePreference.TELUGU, 0.9f, ResponseLength.SHORT)
        val afterAdd = repo.upsert(custom)
        assertTrue(afterAdd.any { it.name == "Coach" })
        // Reload from storage with a fresh repository instance.
        val reloaded = PersonalityRepository(store).load().first { it.id == custom.id }
        assertEquals(custom, reloaded)

        val (_, copy) = repo.duplicate("jarvis")!!
        assertFalse(copy.builtIn)
        assertEquals("JARVIS (copy)", copy.name)

        repo.setActive(custom.id)
        val afterDelete = repo.delete(custom.id)
        assertFalse(afterDelete.any { it.id == custom.id })
        assertEquals("friendly", repo.activeId(afterDelete))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `built-in personalities cannot be deleted`() { PersonalityRepository(MapStore()).delete("jarvis") }

    @Test fun `legacy custom personality is migrated, not lost`() {
        val store = MapStore()
        store.putString(PersonalityRepository.LEGACY_NAME, "Teacher")
        store.putString(PersonalityRepository.LEGACY_PROMPT, "Teach patiently")
        val repo = PersonalityRepository(store)
        val all = repo.load()
        val active = all.first { it.id == repo.activeId(all) }
        assertEquals("Teacher", active.name)
        assertEquals("Teach patiently", active.systemPrompt)
    }

    @Test fun `legacy friendly maps to built in`() {
        val store = MapStore()
        store.putString(PersonalityRepository.LEGACY_NAME, "Friendly")
        store.putString(PersonalityRepository.LEGACY_PROMPT, BuiltInPersonalities.ALL.first { it.id == "friendly" }.systemPrompt)
        val repo = PersonalityRepository(store)
        val all = repo.load()
        assertEquals(BuiltInPersonalities.ALL.size, all.size)
        assertEquals("friendly", repo.activeId(all))
    }

    @Test fun `spoken personality lookup`() {
        val repo = PersonalityRepository(MapStore())
        val all = repo.load()
        assertEquals("jarvis", repo.findByQuery(all, "jarvis")?.id)
        assertEquals("engineer", repo.findByQuery(all, "engineer")?.id)
        assertEquals("creative", repo.findByQuery(all, "creative director")?.id)
        assertNull(repo.findByQuery(all, "pirate"))
    }

    @Test fun `prompt composer includes language personality and user prompt`() {
        val jarvis = BuiltInPersonalities.ALL.first { it.id == "jarvis" }
        val p = PromptComposer.compose("BASE", jarvis, AppLanguage.TELUGU, "Balanced", "Call me boss", listOf("Preferred name: Raj"))
        assertTrue(p.startsWith("BASE"))
        assertTrue(p.contains("Telugu script"))
        assertTrue(p.contains("ACTIVE PERSONALITY (JARVIS)"))
        assertTrue(p.contains(ResponseLength.SHORT.instruction))
        assertTrue(p.contains("Call me boss"))
        assertTrue(p.contains("Preferred name: Raj"))
    }

    @Test fun `personality language preference overrides app language`() {
        val p = PersonalityProfile("x", "T", language = LanguagePreference.TELUGU)
        assertEquals(AppLanguage.TELUGU, p.effectiveLanguage(AppLanguage.ENGLISH))
        assertEquals(AppLanguage.ENGLISH, p.copy(language = LanguagePreference.FOLLOW_APP).effectiveLanguage(AppLanguage.ENGLISH))
    }
}
