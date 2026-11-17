package com.friday.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.friday.app.ChatLine
import com.friday.app.FridayState
import com.friday.app.VoiceUi
import com.friday.app.core.AppLanguage
import com.friday.app.core.VoiceMode
import com.friday.app.core.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule val rule = createComposeRule()
    private val actions = RecordingActions()

    private fun show(state: FridayState) = rule.setContent {
        FridayTheme { HomeScreen(state, actions, onOpenSettings = {}, onOpenCamera = {}, onOpenAppSettings = {}) }
    }

    @Test fun empty_command_center_renders_identity_status_and_voice_entry() {
        show(baseState())
        rule.onNodeWithText("FRIDAY").assertIsDisplayed()
        rule.onNodeWithText("How can I help?").assertIsDisplayed()
        rule.onNodeWithText("On-device ready").assertIsDisplayed()
        rule.onNodeWithText("JARVIS").assertIsDisplayed()
        rule.onNodeWithContentDescription("Start voice input").assertIsDisplayed()
        rule.screenshot("home-empty-phone")
    }

    @Test fun mic_button_drives_voice_pipeline_and_typing_switches_to_send() {
        show(baseState())
        rule.onNodeWithContentDescription("Start voice input").performClick()
        assertEquals(listOf("mic"), actions.calls)
        rule.onNodeWithTag("composer-input").performTextInput("Set temperature to 0.7")
        rule.onNodeWithContentDescription("Send message").performClick()
        assertEquals("send:Set temperature to 0.7", actions.calls.last())
    }

    @Test fun long_markdown_reply_with_code_table_and_telugu_renders_and_scrolls() {
        show(baseState(ChatLine("user", "Explain list comprehensions"), ChatLine("assistant", LONG_REPLY), ChatLine("user", "తెలుగులో చెప్పు"), ChatLine("assistant", TELUGU_REPLY)))
        rule.onNodeWithTag("conversation").performScrollToNode(hasText("Python list comprehension"))
        rule.onNodeWithText("Python list comprehension").assertIsDisplayed()
        rule.onNodeWithContentDescription("Copy code").assertExists()
        rule.onNodeWithTag("conversation").performScrollToNode(hasText("లిస్ట్ కాంప్రహెన్షన్", substring = true))
        rule.screenshot("home-conversation-telugu")
    }

    @Test fun streaming_state_offers_stop_generating() {
        show(baseState(ChatLine("user", "hi"), ChatLine("assistant", "")).copy(busy = true, voice = VoiceUi(VoiceState.PROCESSING, "Thinking…")))
        rule.onNodeWithContentDescription("FRIDAY is thinking").assertExists()
        rule.onNodeWithContentDescription("Stop generating").performClick()
        assertEquals("stopGeneration", actions.calls.last())
    }

    @Test fun listening_shows_live_transcript_and_cancel() {
        show(baseState().copy(voice = VoiceUi(VoiceState.LISTENING, "switch to telugu", level = 0.6f)))
        rule.onNodeWithText("“switch to telugu”").assertIsDisplayed()
        rule.onNodeWithContentDescription("Finish listening").assertIsDisplayed()
        rule.screenshot("home-listening")
        rule.onNodeWithText("Cancel").performClick()
        assertEquals("cancelListening", actions.calls.last())
    }

    @Test fun speaking_offers_stop_and_pause_only_when_supported() {
        show(baseState(ChatLine("user", "hi"), ChatLine("assistant", "Hello there.")).copy(voice = VoiceUi(VoiceState.SPEAKING, "Speaking…", pausable = false)))
        rule.onNodeWithText("Stop").performClick()
        assertEquals("stopSpeaking", actions.calls.last())
        rule.onNode(hasText("Pause")).assertDoesNotExist()
    }

    @Test fun voice_error_explains_and_offers_retry() {
        show(baseState().copy(voice = VoiceUi(VoiceState.ERROR, "No speech detected. Tap the mic and speak after the tone.")))
        rule.onNodeWithText("No speech detected. Tap the mic and speak after the tone.").assertIsDisplayed()
        rule.screenshot("home-error")
        rule.onNodeWithText("Retry").performClick()
        assertEquals("mic", actions.calls.last())
    }

    @Test fun permission_error_links_to_app_settings() {
        show(baseState().copy(voice = VoiceUi(VoiceState.ERROR, "Microphone permission is off. Allow it to use voice commands.")))
        rule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test fun language_chip_toggles_language_and_personality_chip_switches_personality() {
        show(baseState())
        rule.onNodeWithTag("chip-language").performClick()
        assertEquals("language:TELUGU", actions.calls.last())
        rule.onNodeWithTag("chip-personality").performClick()
        rule.onNodeWithText("Professional Engineer").performClick()
        assertEquals("personality:engineer", actions.calls.last())
    }

    @Test fun text_only_mode_hides_microphone() {
        show(baseState().copy(voiceMode = VoiceMode.TEXT_ONLY))
        rule.onNode(hasContentDescription("Start voice input")).assertDoesNotExist()
        rule.onNodeWithText("Type a message below.").assertIsDisplayed()
    }

    @Test fun telugu_interface_strings_render() {
        show(baseState(language = AppLanguage.TELUGU))
        rule.onNodeWithText("నేను మీకు ఎలా సహాయం చేయగలను?").assertIsDisplayed()
        rule.screenshot("home-empty-telugu")
    }

    @Test fun command_confirmations_and_errors_are_distinct_from_chat() {
        show(baseState(ChatLine("user", "Set temperature to 0.7", ChatLine.KIND_COMMAND), ChatLine("assistant", "Temperature set to 0.7.", ChatLine.KIND_COMMAND), ChatLine("assistant", "Can't reach the worker.", ChatLine.KIND_ERROR)))
        rule.onNodeWithText("Temperature set to 0.7.").assertIsDisplayed()
        rule.onNodeWithText("Can't reach the worker.").assertIsDisplayed()
        rule.screenshot("home-commands")
    }

    @Test fun state_changes_recompose_the_orb_label() {
        var state by mutableStateOf(baseState(ChatLine("user", "hi"), ChatLine("assistant", "Hello")))
        rule.setContent { FridayTheme { HomeScreen(state, actions, {}, {}, {}) } }
        rule.onNodeWithText("Ready").assertIsDisplayed()
        state = state.copy(voice = VoiceUi(VoiceState.SPEAKING, "Speaking…"))
        rule.onNodeWithText("Speaking").assertIsDisplayed()
        assertTrue(actions.calls.isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
class SmallScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun small_phone_keeps_composer_and_status_visible() {
        rule.setContent { Sized(320, 568) { HomeScreen(baseState(ChatLine("user", "Explain"), ChatLine("assistant", LONG_REPLY)), RecordingActions(), {}, {}, {}) } }
        rule.onNodeWithContentDescription("Start voice input").assertIsDisplayed()
        rule.onNodeWithTag("composer-input").assertIsDisplayed()
        rule.onNodeWithTag("chip-connection").assertIsDisplayed()
        rule.screenshot("home-small-320dp")
    }
}

@RunWith(AndroidJUnit4::class)
class LargeScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tablet_layout_renders_conversation() {
        rule.setContent { Sized(840, 1200) { HomeScreen(baseState(ChatLine("user", "Explain"), ChatLine("assistant", LONG_REPLY)), RecordingActions(), {}, {}, {}) } }
        rule.onNodeWithText("Python list comprehension").assertIsDisplayed()
        rule.screenshot("home-tablet-840dp")
    }
}
