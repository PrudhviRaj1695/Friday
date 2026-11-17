package com.friday.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.friday.app.ChatLine
import com.friday.app.FridayState
import com.friday.app.VoiceUi
import com.friday.app.WorkerUi
import com.friday.app.core.AppLanguage
import com.friday.app.core.BuiltInPersonalities
import com.friday.app.core.InferenceBackend
import com.friday.app.core.VoiceMode
import com.friday.app.core.VoiceState
import com.friday.app.worker.WorkerStatus
import org.junit.Rule
import org.junit.Test

/**
 * Renders every major screen and voice state with Android's layoutlib on the JVM.
 * `gradle recordPaparazziDebug` refreshes the reference images in app/src/test/snapshots;
 * `gradle testDebugUnitTest` renders them (catching layout/composition crashes).
 */
abstract class ScreenshotBase(device: DeviceConfig) {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = device, showSystemUi = false, maxPercentDifference = 5.0)
    protected val actions = object : FridayActions {}
    protected fun state(vararg m: ChatLine, lang: AppLanguage = AppLanguage.ENGLISH) = FridayState(
        messages = m.toList(), modelReady = true, status = "On-device model ready • GPU • Qwen3 0.6B",
        personalities = BuiltInPersonalities.ALL, activePersonalityId = "jarvis", language = lang)
    protected fun home(s: FridayState) = paparazzi.snapshot { FridayTheme { HomeScreen(s, actions, {}, {}, {}) } }
    protected fun settings(page: SettingsPage?, s: FridayState = state()) = paparazzi.snapshot { FridayTheme { SettingsScreen(page, s, actions, PlatformHooks(onDeviceSttAvailable = true, sttAvailable = true), {}, {}) } }

    companion object {
        val LONG = """
## Python list comprehension

A list comprehension builds a new list in **one readable line**.

1. Start with the expression.
2. Add a `for` clause.

```python
squares = [n * n for n in range(10) if n % 2 == 0]
```

| Approach | Readability |
|---|---|
| Loop | Verbose |
| Comprehension | Concise |
""".trimIndent()
        const val TELUGU = "నమస్తే! పైథాన్‌లో **లిస్ట్ కాంప్రహెన్షన్** అంటే ఒకే లైన్‌లో కొత్త లిస్ట్ తయారు చేయడం."
    }
}

class PhoneScreenshotTest : ScreenshotBase(DeviceConfig.PIXEL_6) {
    @Test fun homeEmpty() = home(state())
    @Test fun homeConversation() = home(state(ChatLine("user", "Explain list comprehensions"), ChatLine("assistant", LONG),
        ChatLine("user", "Set temperature to 0.7", ChatLine.KIND_COMMAND), ChatLine("assistant", "Temperature set to 0.7.", ChatLine.KIND_COMMAND)))
    @Test fun homeTelugu() = home(state(ChatLine("user", "తెలుగులో చెప్పు"), ChatLine("assistant", TELUGU), lang = AppLanguage.TELUGU))
    @Test fun homeListening() = home(state(ChatLine("user", "hi"), ChatLine("assistant", "Hello. How can I help?")).copy(voice = VoiceUi(VoiceState.LISTENING, "change personality to jarvis", 0.6f)))
    @Test fun homeThinking() = home(state(ChatLine("user", "hi"), ChatLine("assistant", "")).copy(busy = true, voice = VoiceUi(VoiceState.PROCESSING, "hi")))
    @Test fun homeSpeaking() = home(state(ChatLine("user", "hi"), ChatLine("assistant", "Hello there.")).copy(voice = VoiceUi(VoiceState.SPEAKING, "Speaking…", pausable = true)))
    @Test fun homeError() = home(state().copy(voice = VoiceUi(VoiceState.ERROR, "Speech recognition for this language isn't installed. Install the language in Android voice-typing settings.")))
    @Test fun homeWorkerUnavailable() = home(state(ChatLine("user", "hello"), ChatLine("assistant", "Can't reach the worker at http://192.168.1.20:8765. Start start-friday.bat on the PC.", ChatLine.KIND_ERROR))
        .copy(backend = InferenceBackend.WINDOWS_WORKER, worker = WorkerUi(url = "http://192.168.1.20:8765", tokenSaved = true, status = WorkerStatus.WORKER_UNAVAILABLE)))
    @Test fun homeTextOnly() = home(state().copy(voiceMode = VoiceMode.TEXT_ONLY))
    @Test fun settingsHome() = settings(null)
    @Test fun settingsAiStudio() = settings(SettingsPage.AI_MODELS)
    @Test fun settingsPersonality() = settings(SettingsPage.PERSONALITY)
    @Test fun settingsVoice() = settings(SettingsPage.VOICE)
    @Test fun settingsLanguageTelugu() = settings(SettingsPage.LANGUAGE, state(lang = AppLanguage.TELUGU))
    @Test fun settingsConnectivity() = settings(SettingsPage.CONNECTIVITY, state().copy(backend = InferenceBackend.WINDOWS_WORKER,
        worker = WorkerUi(url = "http://192.168.1.20:8765", tokenSaved = true, status = WorkerStatus.CONNECTED, detail = "Worker 2.0.0 • qwen2.5:3b • 38ms", models = listOf("qwen2.5:3b", "qwen2.5:7b"), defaultModel = "qwen2.5:3b")))
    @Test fun settingsPrivacy() = settings(SettingsPage.PRIVACY)
}

class SmallPhoneScreenshotTest : ScreenshotBase(DeviceConfig.NEXUS_4) {
    @Test fun homeEmpty() = home(state())
    @Test fun homeConversation() = home(state(ChatLine("user", "Explain list comprehensions"), ChatLine("assistant", LONG)))
}

class TabletScreenshotTest : ScreenshotBase(DeviceConfig.PIXEL_C) {
    @Test fun homeConversation() = home(state(ChatLine("user", "Explain list comprehensions"), ChatLine("assistant", LONG)))
    @Test fun settingsAiStudio() = settings(SettingsPage.AI_MODELS)
}
