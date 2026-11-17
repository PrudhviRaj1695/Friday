package com.friday.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import com.friday.app.ChatLine
import com.friday.app.FridayState
import com.friday.app.core.AppLanguage
import com.friday.app.core.BuiltInPersonalities
import com.friday.app.core.GenerationSettings
import com.friday.app.core.PersonalityProfile
import com.friday.app.core.VoiceMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ForcedSize
import java.io.File

/** Records every UI action so tests can assert the screen called the right thing. */
class RecordingActions : FridayActions {
    val calls = mutableListOf<String>()
    override fun send(text: String) { calls += "send:$text" }
    override fun onMicPressed() { calls += "mic" }
    override fun stopSpeaking() { calls += "stopSpeaking" }
    override fun stopGeneration() { calls += "stopGeneration" }
    override fun cancelListening() { calls += "cancelListening" }
    override fun dismissVoiceError() { calls += "dismissVoiceError" }
    override fun setLanguage(language: AppLanguage) { calls += "language:${language.name}" }
    override fun activatePersonality(id: String) { calls += "personality:$id" }
    override fun setVoiceMode(mode: VoiceMode) { calls += "voiceMode:${mode.name}" }
    override fun updateGeneration(settings: GenerationSettings) { calls += "generation:${settings.temperature}" }
    override fun savePersonality(profile: PersonalityProfile) { calls += "savePersonality:${profile.name}" }
    override fun speakMessage(text: String) { calls += "speak" }
}

fun baseState(vararg messages: ChatLine, language: AppLanguage = AppLanguage.ENGLISH) = FridayState(
    messages = messages.toList(),
    modelReady = true,
    status = "On-device model ready • GPU • Qwen3 0.6B",
    personalities = BuiltInPersonalities.ALL,
    activePersonalityId = "jarvis",
    language = language
)

val LONG_REPLY = """
## Python list comprehension

A list comprehension builds a new list from an iterable in **one readable line**. Use it when the transformation is simple.

1. Start with the expression you want in the new list.
2. Add a `for` clause.
3. Optionally add an `if` filter.

```python
squares = [n * n for n in range(10) if n % 2 == 0]
print(squares)
```

| Approach | Speed | Readability |
|---|---|---|
| Loop | Baseline | Verbose |
| Comprehension | Faster | Concise |

> Keep comprehensions short — nest them only when it stays obvious.
""".trimIndent()

const val TELUGU_REPLY = "నమస్తే! పైథాన్‌లో **లిస్ట్ కాంప్రహెన్షన్** అంటే ఒకే లైన్‌లో కొత్త లిస్ట్ తయారు చేయడం. ఉదాహరణకు `squares` అనే లిస్ట్."

/**
 * Saves a PNG of the current screen for manual review. Pull them with:
 * adb pull /sdcard/Android/data/com.friday.app/files/ui-screenshots
 */
fun ComposeContentTestRule.screenshot(name: String) {
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    val dir = File(context.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** Renders [content] as if on a screen of the given size (small phone / tablet layouts). */
@androidx.compose.runtime.Composable
fun Sized(widthDp: Int, heightDp: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.ui.test.DeviceConfigurationOverride(
        androidx.compose.ui.test.DeviceConfigurationOverride.ForcedSize(androidx.compose.ui.unit.DpSize(widthDp.dp, heightDp.dp))
    ) { FridayTheme(content) }
}
