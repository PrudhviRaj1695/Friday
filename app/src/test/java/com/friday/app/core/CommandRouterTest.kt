package com.friday.app.core

import com.friday.app.core.AssistantCommand.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRouterTest {
    private fun r(s: String) = CommandRouter.route(s)

    @Test fun `english conversation requests go to the LLM with wake word stripped`() {
        assertEquals(Conversation("how are you?"), r("Hey Friday, how are you?"))
        assertEquals(Conversation("explain this Python code."), r("Friday, explain this Python code."))
        assertEquals(Conversation("what's the weather like"), r("ok friday what's the weather like"))
    }

    @Test fun `english configuration commands are deterministic`() {
        assertEquals(SetLanguage(AppLanguage.TELUGU), r("Switch to Telugu."))
        assertEquals(SetLanguage(AppLanguage.ENGLISH), r("Hey Friday, speak in English"))
        assertEquals(SetPersonality("jarvis"), r("Change personality to JARVIS."))
        assertEquals(SetPersonality("creative director"), r("activate the creative director personality"))
        assertEquals(SetTemperature(0.7f), r("Set temperature to 0.7."))
        assertEquals(SetTemperature(0.7f), r("set the temperature to zero point seven"))
        assertEquals(SetTopP(0.9f), r("set top p to 0.9"))
        assertEquals(SetMaxTokens(512), r("set max tokens to 512"))
        assertEquals(SetVoiceMode(VoiceMode.CONTINUOUS), r("continuous conversation mode"))
        assertEquals(SetBackend(InferenceBackend.WINDOWS_WORKER), r("use the windows worker"))
        assertEquals(SetBackend(InferenceBackend.ON_DEVICE), r("switch to phone model"))
    }

    @Test fun `english system commands`() {
        assertEquals(StopSpeaking, r("Stop speaking."))
        assertEquals(StopSpeaking, r("Friday, stop"))
        assertEquals(Repeat, r("Repeat that."))
        assertEquals(Repeat, r("say that again"))
        assertEquals(Summarize, r("Summarize this conversation."))
    }

    @Test fun `out of range values are rejected not applied`() {
        assertTrue(r("set temperature to 5") is Rejected)
        assertTrue(r("set top p to 3") is Rejected)
        assertTrue(r("set temperature to banana") is Rejected)
    }

    @Test fun `requests that merely mention a language stay conversational`() {
        assertTrue(r("Explain in Telugu what a Python list is") is Conversation)
        assertTrue(r("stop the server when I say so and explain why") is Conversation)
        assertTrue(r("How do I repeat a loop in Python?") is Conversation)
    }

    @Test fun `telugu commands`() {
        assertEquals(Conversation("ఎలా ఉన్నావు?"), r("ఫ్రైడే, ఎలా ఉన్నావు?"))
        assertEquals(SetLanguage(AppLanguage.TELUGU), r("ఇప్పుడు తెలుగు లో మాట్లాడు."))
        assertEquals(SetLanguage(AppLanguage.TELUGU), r("తెలుగులో మాట్లాడు"))
        assertEquals(SetLanguage(AppLanguage.ENGLISH), r("ఇంగ్లీష్ లో మాట్లాడు"))
        assertEquals(Conversation("ఈ కోడ్ వివరించు."), r("ఈ కోడ్ వివరించు."))
        assertEquals(StopSpeaking, r("నీ వాయిస్ ఆపు."))
        assertEquals(Repeat, r("ఈ సమాధానాన్ని మళ్ళీ చెప్పు."))
        assertEquals(Repeat, r("ఈ సమాధానాన్ని మళ్లీ చెప్పు")) // alternate spelling ళ్ల
    }

    @Test fun `spoken numbers`() {
        assertEquals(0.7f, SpokenNumber.parse("point seven")!!, 0.0001f)
        assertEquals(1.25f, SpokenNumber.parse("one point two five")!!, 0.0001f)
        assertEquals(2048f, SpokenNumber.parse("two thousand forty eight")!!, 0.0001f)
        assertEquals(512f, SpokenNumber.parse("five hundred twelve")!!, 0.0001f)
        assertEquals(null, SpokenNumber.parse("lots"))
    }

    @Test fun `bare wake word does not crash`() {
        assertTrue(r("Hey Friday") is Conversation)
        assertTrue(r("   ") is Conversation)
    }
}
