package com.friday.app.core

/** Speech-to-text failures, mapped from platform error codes, with user-facing guidance. */
enum class RecognitionError(val message: String, val retryable: Boolean) {
    NO_MATCH("I didn't catch that. Tap the mic and try again.", true),
    SPEECH_TIMEOUT("No speech detected. Tap the mic and speak after the tone.", true),
    TIMEOUT("Listening timed out. Tap the mic to try again.", true),
    PERMISSION_DENIED("Microphone permission is off. Allow it to use voice commands.", false),
    AUDIO("The microphone couldn't record audio. Close other apps using it and retry.", true),
    NETWORK("The speech service needs a network connection for this language. Download the offline language pack or retry.", true),
    BUSY("The speech service is busy. Try again in a moment.", true),
    LANGUAGE_UNAVAILABLE("Speech recognition for this language isn't installed. Install the language in Android voice-typing settings.", false),
    SERVICE_UNAVAILABLE("No speech recognition service is installed on this device.", false),
    CLIENT("The speech service reported an internal error. Try again.", true)
}

interface RecognitionCallbacks {
    fun onReady()
    fun onPartial(text: String)
    fun onLevel(level: Float)
    fun onResult(text: String)
    fun onError(error: RecognitionError)
}

/** Microphone + speech recognizer stage. */
interface SpeechRecognizerPort {
    fun isAvailable(): Boolean
    fun start(language: AppLanguage, callbacks: RecognitionCallbacks)
    /** Stop capturing and deliver whatever was recognized so far. */
    fun stop()
    /** Abort without a result. */
    fun cancel()
}

interface SpeakCallbacks {
    fun onStart()
    fun onDone()
    fun onError(message: String)
}

/** Text-to-speech + audio playback stage. */
interface SpeechSynthesizerPort {
    fun speak(text: String, language: AppLanguage, callbacks: SpeakCallbacks)
    fun stop()
    fun pause(): Boolean
    fun resume(): Boolean
}

interface Cancellable { fun cancel() }
interface Scheduler { fun schedule(delayMs: Long, block: () -> Unit): Cancellable }

/**
 * Orchestrates Microphone → STT → (caller: routing/LLM) → TTS around [VoiceStateMachine].
 * Callbacks from stale sessions are ignored using a session token, so a late callback from a
 * cancelled recognizer or synthesizer can never corrupt the current state.
 */
class VoiceCoordinator(
    val machine: VoiceStateMachine,
    private val recognizer: SpeechRecognizerPort,
    private val synthesizer: SpeechSynthesizerPort,
    private val scheduler: Scheduler,
    private val hasMicPermission: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {}
) {
    interface Listener {
        /** Final recognized utterance, ready for command routing / LLM. */
        fun onUtterance(text: String)
        fun onPermissionRequired()
        fun onLevel(level: Float) {}
    }

    var listener: Listener? = null
    var mode: VoiceMode = VoiceMode.PUSH_TO_TALK
    var language: AppLanguage = AppLanguage.ENGLISH
    var listenTimeoutMs: Long = 15_000
    var lastRecognized: String = ""; private set
    var paused = false; private set

    private var session = 0L
    private var lastMicPress = 0L
    private var watchdog: Cancellable? = null
    private var continuousMisses = 0

    val state: VoiceState get() = machine.state

    /** Mic button handler. Safe to call repeatedly: rapid presses are debounced. */
    fun onMicPressed() {
        val now = clock()
        if (now - lastMicPress < 400) { log("mic press debounced"); return }
        lastMicPress = now
        continuousMisses = 0
        when (machine.state) {
            VoiceState.LISTENING -> { log("mic pressed while listening -> finish"); recognizer.stop() }
            VoiceState.SPEAKING -> { interruptSpeech(); startListening() }
            VoiceState.PROCESSING -> machine.updateDetail(VoiceState.PROCESSING, "Still working on the last request…")
            else -> startListening()
        }
    }

    fun startListening(): Boolean {
        if (mode == VoiceMode.TEXT_ONLY) { fail("Voice input is off in Text-only mode. Change the voice mode in Settings → Voice."); return false }
        if (!recognizer.isAvailable()) { fail(RecognitionError.SERVICE_UNAVAILABLE.message); return false }
        if (!hasMicPermission()) { listener?.onPermissionRequired(); return false }
        if (!machine.transition(VoiceState.LISTENING, "Listening…")) { log("startListening rejected from ${machine.state}"); return false }
        val token = ++session
        lastRecognized = ""
        log("listening session=$token language=${language.bcp47}")
        armWatchdog(token)
        recognizer.start(language, object : RecognitionCallbacks {
            override fun onReady() { if (token == session) machine.updateDetail(VoiceState.LISTENING, "Listening…") }
            override fun onPartial(text: String) {
                if (token != session || text.isBlank()) return
                lastRecognized = text
                machine.updateDetail(VoiceState.LISTENING, text)
            }
            override fun onLevel(level: Float) { if (token == session) listener?.onLevel(level) }
            override fun onResult(text: String) {
                if (token != session) return
                disarmWatchdog()
                val clean = text.trim()
                if (clean.isEmpty()) { handleRecognitionError(RecognitionError.NO_MATCH); return }
                continuousMisses = 0
                lastRecognized = clean
                if (machine.transition(VoiceState.PROCESSING, clean)) listener?.onUtterance(clean)
            }
            override fun onError(error: RecognitionError) {
                if (token != session) return
                disarmWatchdog()
                handleRecognitionError(error)
            }
        })
        return true
    }

    /** Called when the user grants or denies microphone permission after [Listener.onPermissionRequired]. */
    fun onPermissionResult(granted: Boolean) {
        if (granted) startListening() else fail(RecognitionError.PERMISSION_DENIED.message)
    }

    private fun handleRecognitionError(error: RecognitionError) {
        log("recognition error $error")
        session++ // invalidate any further callbacks from this recognizer session
        recognizer.cancel()
        val quiet = mode == VoiceMode.CONTINUOUS && (error == RecognitionError.NO_MATCH || error == RecognitionError.SPEECH_TIMEOUT || error == RecognitionError.TIMEOUT)
        if (quiet) {
            continuousMisses++
            machine.transition(VoiceState.IDLE, "Continuous listening paused. Tap the mic to resume.")
        } else {
            machine.transition(VoiceState.ERROR, error.message)
        }
    }

    private fun armWatchdog(token: Long) {
        disarmWatchdog()
        watchdog = scheduler.schedule(listenTimeoutMs) {
            if (token == session && machine.state == VoiceState.LISTENING) handleRecognitionError(RecognitionError.TIMEOUT)
        }
    }

    private fun disarmWatchdog() { watchdog?.cancel(); watchdog = null }

    /** Typed requests enter the pipeline here. Cancels listening if the user typed instead. */
    fun beginProcessing(detail: String = "Thinking…"): Boolean {
        if (machine.state == VoiceState.LISTENING) cancelListening()
        if (machine.state == VoiceState.SPEAKING) interruptSpeech()
        if (machine.state == VoiceState.PROCESSING) return true
        return machine.transition(VoiceState.PROCESSING, detail)
    }

    /** LLM/command finished. Speaks the reply when voice output is enabled. */
    fun onResponseReady(reply: String, speakReply: Boolean) {
        if (machine.state != VoiceState.PROCESSING) { log("response arrived in ${machine.state}; ignored for voice"); return }
        if (speakReply && mode != VoiceMode.TEXT_ONLY && reply.isNotBlank()) speak(reply)
        else machine.transition(VoiceState.IDLE)
    }

    fun onResponseFailed(message: String) {
        if (machine.state == VoiceState.PROCESSING || machine.state == VoiceState.LISTENING) machine.transition(VoiceState.ERROR, message)
    }

    /** Speaks text (reply, "repeat that", voice test). Any current speech is replaced. */
    fun speak(text: String, language: AppLanguage = Script.dominantLanguage(text)) {
        if (machine.state == VoiceState.SPEAKING) interruptSpeech()
        if (machine.state == VoiceState.LISTENING) cancelListening()
        val speakable = SpeechText.toSpeakable(text, language)
        if (speakable.isBlank()) { machine.transition(VoiceState.IDLE); return }
        if (!machine.transition(VoiceState.SPEAKING, "Speaking…")) { log("speak rejected from ${machine.state}"); return }
        val token = ++session
        paused = false
        synthesizer.speak(speakable, language, object : SpeakCallbacks {
            override fun onStart() { if (token == session) machine.updateDetail(VoiceState.SPEAKING, "Speaking…") }
            override fun onDone() {
                if (token != session) return
                paused = false
                if (mode == VoiceMode.CONTINUOUS && continuousMisses < 2 && hasMicPermission() && recognizer.isAvailable()) {
                    machine.transition(VoiceState.IDLE)
                    startListening()
                } else machine.transition(VoiceState.IDLE)
            }
            override fun onError(message: String) {
                if (token != session) return
                paused = false
                machine.transition(VoiceState.ERROR, message)
            }
        })
    }

    /** Stop button / "stop speaking" command. */
    fun interruptSpeech() {
        if (machine.state != VoiceState.SPEAKING) { synthesizer.stop(); return }
        session++
        synthesizer.stop()
        paused = false
        machine.transition(VoiceState.INTERRUPTED, "Stopped")
        machine.transition(VoiceState.IDLE, "Stopped")
    }

    fun togglePause(): Boolean {
        if (machine.state != VoiceState.SPEAKING) return false
        val ok = if (paused) synthesizer.resume() else synthesizer.pause()
        if (ok) { paused = !paused; machine.updateDetail(VoiceState.SPEAKING, if (paused) "Paused" else "Speaking…") }
        return ok
    }

    fun cancelListening() {
        if (machine.state != VoiceState.LISTENING) return
        session++
        disarmWatchdog()
        recognizer.cancel()
        machine.transition(VoiceState.INTERRUPTED, "Listening cancelled")
        machine.transition(VoiceState.IDLE)
    }

    /** User pressed stop while the LLM was generating. */
    fun cancelProcessing() {
        if (machine.state == VoiceState.PROCESSING) { machine.transition(VoiceState.INTERRUPTED, "Stopped"); machine.transition(VoiceState.IDLE) }
    }

    /** App moved to background: never keep the microphone open. */
    fun onBackground() {
        if (machine.state == VoiceState.LISTENING) cancelListening()
    }

    fun dismissError() { if (machine.state == VoiceState.ERROR) machine.transition(VoiceState.IDLE) }

    private fun fail(message: String) {
        if (!machine.transition(VoiceState.ERROR, message)) {
            // Already in ERROR: refresh the message.
            machine.updateDetail(VoiceState.ERROR, message)
        }
    }

    fun shutdown() {
        session++
        disarmWatchdog()
        recognizer.cancel()
        synthesizer.stop()
        machine.reset()
    }
}
