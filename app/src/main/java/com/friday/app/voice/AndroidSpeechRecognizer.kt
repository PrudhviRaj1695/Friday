package com.friday.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.friday.app.core.AppLanguage
import com.friday.app.core.Cancellable
import com.friday.app.core.RecognitionCallbacks
import com.friday.app.core.RecognitionError
import com.friday.app.core.Scheduler
import com.friday.app.core.SpeechRecognizerPort

/**
 * Microphone capture + speech-to-text using Android's SpeechRecognizer.
 *
 * Fixes over the previous implementation:
 *  - EXTRA_LANGUAGE is a BCP-47 *String* ("en-US" / "te-IN"); a java.util.Locale object was
 *    passed before, which recognizers ignore, so the selected language was never applied.
 *  - The on-device recognizer is tried first only when requested, and FRIDAY automatically
 *    falls back to the standard recognizer when it reports that the language pack is missing
 *    (errors 12/13) or fails to bind — previously this surfaced as "SPEECH ERROR • 13" forever.
 *  - A fresh recognizer instance is created per session and destroyed afterwards, avoiding
 *    ERROR_RECOGNIZER_BUSY / ERROR_CLIENT after an interrupted session.
 * All SpeechRecognizer calls happen on the main thread as the platform requires.
 */
class AndroidSpeechRecognizer(
    private val context: Context,
    var preferOnDevice: () -> Boolean = { true },
    var preferOffline: () -> Boolean = { true }
) : SpeechRecognizerPort {
    private val tag = "FridayVoice"
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var session = 0L
    var lastEngine: String = "none"; private set

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable()

    fun onDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    override fun start(language: AppLanguage, callbacks: RecognitionCallbacks) {
        main.post { begin(language, callbacks, allowOnDevice = preferOnDevice() && onDeviceAvailable()) }
    }

    private fun begin(language: AppLanguage, callbacks: RecognitionCallbacks, allowOnDevice: Boolean) {
        destroyRecognizer()
        val token = ++session
        val instance = try {
            if (allowOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lastEngine = "on-device"; SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
                lastEngine = "system"; SpeechRecognizer.createSpeechRecognizer(context)
            } else null
        } catch (e: Exception) {
            Log.e(tag, "recognizer creation failed", e); null
        }
        if (instance == null) { callbacks.onError(RecognitionError.SERVICE_UNAVAILABLE); return }
        recognizer = instance
        Log.i(tag, "start engine=$lastEngine language=${language.bcp47}")
        var heardSpeech = false
        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (token == session) callbacks.onReady() }
            override fun onBeginningOfSpeech() { heardSpeech = true }
            override fun onRmsChanged(rmsdB: Float) { if (token == session) callbacks.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                if (token != session) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(callbacks::onPartial)
            }
            override fun onResults(results: Bundle?) {
                if (token != session) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                Log.i(tag, "result engine=$lastEngine chars=${text.length}")
                destroyRecognizer()
                callbacks.onResult(text)
            }
            override fun onError(error: Int) {
                if (token != session) return
                Log.w(tag, "error engine=$lastEngine code=$error heardSpeech=$heardSpeech")
                val onDeviceFailure = lastEngine == "on-device" && !heardSpeech && error in FALLBACK_CODES
                destroyRecognizer()
                if (onDeviceFailure && SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.i(tag, "on-device recognizer unavailable for ${language.bcp47}; falling back to system recognizer")
                    begin(language, callbacks, allowOnDevice = false)
                    return
                }
                callbacks.onError(map(error))
            }
        })
        try {
            instance.startListening(intent(language))
        } catch (e: Exception) {
            Log.e(tag, "startListening failed", e)
            destroyRecognizer()
            callbacks.onError(RecognitionError.CLIENT)
        }
    }

    private fun intent(language: AppLanguage) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.bcp47)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.bcp47)
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline())
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Silence detection: end the utterance after ~1.6 s of silence.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
    }

    override fun stop() { main.post { runCatching { recognizer?.stopListening() } } }

    override fun cancel() {
        main.post {
            session++
            destroyRecognizer()
        }
    }

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    companion object {
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
        private const val ERROR_TOO_MANY_REQUESTS = 10
        private val FALLBACK_CODES = setOf(ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE, ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER)

        fun map(code: Int): RecognitionError = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> RecognitionError.NO_MATCH
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionError.SPEECH_TIMEOUT
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionError.PERMISSION_DENIED
            SpeechRecognizer.ERROR_AUDIO -> RecognitionError.AUDIO
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> RecognitionError.NETWORK
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS -> RecognitionError.BUSY
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> RecognitionError.LANGUAGE_UNAVAILABLE
            else -> RecognitionError.CLIENT
        }
    }
}

/** Main-thread scheduler used for the listening watchdog. */
class MainScheduler : Scheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
        val r = Runnable(block)
        handler.postDelayed(r, delayMs)
        return object : Cancellable { override fun cancel() = handler.removeCallbacks(r) }
    }
}
