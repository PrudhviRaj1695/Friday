package com.friday.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.friday.app.core.AppLanguage
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's installed text-to-speech engine (Google / Samsung). Used for Telugu, which the
 * offline Kokoro model cannot speak, and as the English fallback when Kokoro is not installed.
 * Voices marked as network-only are skipped so speech stays on the device.
 */
class SystemTts(context: Context) {
    enum class Availability { READY, INITIALIZING, MISSING_DATA, NOT_SUPPORTED, ENGINE_FAILED }

    private val tag = "FridayTTS"
    @Volatile private var engine: TextToSpeech? = null
    @Volatile var initState: Availability = Availability.INITIALIZING; private set
    private val pending = mutableListOf<() -> Unit>()
    private val callbacks = ConcurrentHashMap<String, Pair<() -> Unit, (String) -> Unit>>()

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { utteranceId?.let { id -> callbacks.remove(id)?.first?.invoke() } }
                    @Deprecated("Deprecated in Android API")
                    override fun onError(utteranceId: String?) { onError(utteranceId, -1) }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.w(tag, "utterance error code=$errorCode")
                        utteranceId?.let { id -> callbacks.remove(id)?.second?.invoke("System voice playback failed (code $errorCode).") }
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { callbacks.remove(it) } }
                })
                initState = Availability.READY
                Log.i(tag, "system TTS ready engine=${engine?.defaultEngine}")
            } else {
                initState = Availability.ENGINE_FAILED
                Log.e(tag, "system TTS init failed status=$status")
            }
            val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
            queued.forEach { it() }
        }
    }

    private fun locale(language: AppLanguage) = Locale.forLanguageTag(language.bcp47)

    fun availability(language: AppLanguage): Availability {
        val tts = engine ?: return Availability.ENGINE_FAILED
        if (initState != Availability.READY) return initState
        return when (tts.isLanguageAvailable(locale(language))) {
            TextToSpeech.LANG_MISSING_DATA -> Availability.MISSING_DATA
            TextToSpeech.LANG_NOT_SUPPORTED -> Availability.NOT_SUPPORTED
            else -> Availability.READY
        }
    }

    fun availabilityMessage(language: AppLanguage): String = when (availability(language)) {
        Availability.READY -> "${language.displayName} system voice ready"
        Availability.INITIALIZING -> "System voice is starting…"
        Availability.MISSING_DATA -> "${language.displayName} voice data is not downloaded. Open Android Settings → Text-to-speech → Preferred engine → Install voice data → ${language.displayName}."
        Availability.NOT_SUPPORTED -> "The installed text-to-speech engine has no ${language.displayName} voice. Install Speech Services by Google and add ${language.displayName}."
        Availability.ENGINE_FAILED -> "Android text-to-speech engine failed to start."
    }

    /** Runs [block] once the engine has initialized (immediately if it already has). */
    fun whenReady(block: () -> Unit) {
        val run = synchronized(pending) { if (initState == Availability.INITIALIZING) { pending += block; false } else true }
        if (run) block()
    }

    /**
     * Speaks [chunks] in order. [onDone] fires after the last chunk; [onError] on any failure.
     */
    fun speak(chunks: List<String>, language: AppLanguage, rate: Float, pitch: Float, onDone: () -> Unit, onError: (String) -> Unit) {
        whenReady {
            val tts = engine
            val availability = availability(language)
            if (tts == null || availability != Availability.READY) { onError(availabilityMessage(language)); return@whenReady }
            tts.language = locale(language)
            // Prefer an installed (offline) voice for the language.
            runCatching {
                tts.voices?.filter { it.locale.language == locale(language).language && !it.isNetworkConnectionRequired }
                    ?.maxByOrNull { it.quality }?.let { tts.voice = it }
            }
            tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
            tts.setPitch(pitch.coerceIn(0.5f, 2.0f))
            val group = "g" + System.nanoTime()
            chunks.forEachIndexed { i, chunk ->
                val id = "$group-$i"
                val last = i == chunks.lastIndex
                callbacks[id] = Pair({ if (last) onDone() }, onError)
                val result = tts.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
                if (result != TextToSpeech.SUCCESS) { callbacks.remove(id); onError("System voice rejected the text (code $result)."); return@whenReady }
            }
        }
    }

    fun stop() {
        callbacks.clear()
        runCatching { engine?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
    }
}
