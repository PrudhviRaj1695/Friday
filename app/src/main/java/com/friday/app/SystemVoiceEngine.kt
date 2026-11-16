package com.friday.app

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uses the speech engine already installed on Android. This gives FRIDAY
 * immediate speech output without requiring the optional Kokoro download.
 */
class SystemVoiceEngine(
    context: Context,
    private val onStatus: (Boolean, String) -> Unit
) {
    private val initialized = AtomicBoolean(false)
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var pendingText: String? = null

    init {
        engine = TextToSpeech(context.applicationContext) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val tts = engine
                val languageResult = tts?.setLanguage(Locale.getDefault())
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setSpeechRate(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onStatus(true, "Speaking • Android system voice")
                    }
                    override fun onDone(utteranceId: String?) {
                        onStatus(true, "Voice ready")
                    }
                    @Deprecated("Deprecated in Android API")
                    override fun onError(utteranceId: String?) {
                        onStatus(false, "Android speech playback failed")
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        onStatus(false, "Android speech error • code $errorCode")
                    }
                })
                val ready = languageResult != null &&
                    languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                initialized.set(ready)
                onStatus(ready, if (ready) "Android system voice ready" else "Install a supported Android TTS voice in system settings")
                if (ready) pendingText?.also { pendingText = null; speak(it) }
            } else {
                initialized.set(false)
                onStatus(false, "Android text-to-speech engine failed to initialize")
            }
        }
    }

    fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        if (!initialized.get()) {
            pendingText = text
            return true
        }
        val result = engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "friday-${System.nanoTime()}")
        return result == TextToSpeech.SUCCESS
    }

    fun stop() {
        pendingText = null
        engine?.stop()
    }

    fun shutdown() {
        stop()
        engine?.shutdown()
        engine = null
        initialized.set(false)
    }
}
