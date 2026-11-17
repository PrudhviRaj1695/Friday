package com.friday.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.friday.app.WorkerUi
import com.friday.app.core.AppLanguage
import com.friday.app.core.InferenceBackend
import com.friday.app.worker.WorkerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()
    private val actions = RecordingActions()

    private fun show(page: SettingsPage?, state: com.friday.app.FridayState = baseState()) {
        var current by mutableStateOf(page)
        rule.setContent { Sized(411, 891) { SettingsScreen(current, state, actions, PlatformHooks(), onNavigate = { current = it }, onClose = {}) } }
    }

    @Test fun settings_home_lists_all_nine_categories_and_navigates() {
        show(null)
        SettingsPage.entries.forEach { rule.onNodeWithTag("settings-${it.name}").performScrollTo().assertIsDisplayed() }
        rule.screenshot("settings-home")
        rule.onNodeWithTag("settings-PERSONALITY").performScrollTo().performClick()
        rule.onNodeWithText("New personality").assertIsDisplayed()
    }

    @Test fun personality_studio_shows_built_ins_and_creates_custom_profile() {
        show(SettingsPage.PERSONALITY)
        rule.onNodeWithText("Critical Thinking Advisor").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("personality-jarvis").performScrollTo()
        rule.screenshot("settings-personality")
        rule.onNodeWithText("New personality").performScrollTo().performClick()
        rule.onNode(hasText("Name")).performTextInput("Coach")
        rule.onNodeWithText("Save").performClick()
        assertEquals("savePersonality:Coach", actions.calls.last())
    }

    @Test fun language_page_selects_telugu() {
        show(SettingsPage.LANGUAGE)
        rule.onNodeWithTag("lang-TELUGU").performClick()
        assertEquals("language:TELUGU", actions.calls.last())
    }

    @Test fun telugu_language_page_shows_checklist() {
        show(SettingsPage.LANGUAGE, baseState(language = AppLanguage.TELUGU))
        rule.onNodeWithText("Telugu checklist").assertIsDisplayed()
    }

    @Test fun voice_page_is_honest_about_wake_word() {
        show(SettingsPage.VOICE)
        rule.onNodeWithText("Wake word (“Hey FRIDAY”)").performScrollTo().assertIsDisplayed()
        rule.screenshot("settings-voice")
    }

    @Test fun ai_studio_exposes_real_sampling_controls_and_presets() {
        show(SettingsPage.AI_MODELS)
        listOf("Temperature", "Top-P", "Top-K", "Max output tokens", "Context length", "Stream responses").forEach {
            rule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText("Creative").performScrollTo().assertIsDisplayed()
        rule.screenshot("settings-ai-studio")
    }

    @Test fun connectivity_shows_worker_status() {
        show(SettingsPage.CONNECTIVITY, baseState().copy(backend = InferenceBackend.WINDOWS_WORKER, worker = WorkerUi(url = "http://192.168.1.20:8765", tokenSaved = true, status = WorkerStatus.MODEL_UNAVAILABLE, detail = "Model 'qwen2.5:7b' is not installed. On the PC run: ollama pull qwen2.5:7b")))
        rule.onNodeWithText("Model unavailable").assertIsDisplayed()
        rule.onNodeWithText("Model 'qwen2.5:7b' is not installed. On the PC run: ollama pull qwen2.5:7b").assertIsDisplayed()
        rule.screenshot("settings-connectivity")
        assertTrue(actions.calls.isEmpty())
    }
}
