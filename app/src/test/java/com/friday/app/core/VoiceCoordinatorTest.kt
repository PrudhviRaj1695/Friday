package com.friday.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercises the full voice pipeline with fake microphone/STT and TTS stages. */
class VoiceCoordinatorTest {
    class FakeRecognizer : SpeechRecognizerPort {
        var available = true
        var starts = 0
        var cancels = 0
        var stops = 0
        var lastLanguage: AppLanguage? = null
        var callbacks: RecognitionCallbacks? = null
        override fun isAvailable() = available
        override fun start(language: AppLanguage, callbacks: RecognitionCallbacks) { starts++; lastLanguage = language; this.callbacks = callbacks }
        override fun stop() { stops++ }
        override fun cancel() { cancels++ }
    }

    class FakeSynth : SpeechSynthesizerPort {
        val spoken = mutableListOf<Pair<String, AppLanguage>>()
        var callbacks: SpeakCallbacks? = null
        var stops = 0
        override fun speak(text: String, language: AppLanguage, callbacks: SpeakCallbacks) { spoken += text to language; this.callbacks = callbacks; callbacks.onStart() }
        override fun stop() { stops++ }
        override fun pause() = true
        override fun resume() = true
    }

    class FakeScheduler : Scheduler {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
            val entry = delayMs to block
            tasks += entry
            return object : Cancellable { override fun cancel() { tasks.remove(entry) } }
        }
        fun fireAll() { tasks.toList().forEach { tasks.remove(it); it.second() } }
    }

    private lateinit var rec: FakeRecognizer
    private lateinit var tts: FakeSynth
    private lateinit var sched: FakeScheduler
    private var permission = true
    private var now = 10_000L
    private val utterances = mutableListOf<String>()
    private var permissionRequests = 0
    private lateinit var c: VoiceCoordinator

    @Before fun setUp() {
        rec = FakeRecognizer(); tts = FakeSynth(); sched = FakeScheduler()
        c = VoiceCoordinator(VoiceStateMachine(), rec, tts, sched, { permission }, { now })
        c.listener = object : VoiceCoordinator.Listener {
            override fun onUtterance(text: String) { utterances += text }
            override fun onPermissionRequired() { permissionRequests++ }
        }
    }

    private fun press() { now += 1000; c.onMicPressed() }

    @Test fun `voice input to response to voice output`() {
        press()
        assertEquals(VoiceState.LISTENING, c.state)
        rec.callbacks!!.onPartial("hey fri")
        assertEquals("hey fri", c.machine.snapshot.detail)
        rec.callbacks!!.onResult("Hey Friday, how are you?")
        assertEquals(VoiceState.PROCESSING, c.state)
        assertEquals(listOf("Hey Friday, how are you?"), utterances)
        c.onResponseReady("I'm **great**, thanks.", speakReply = true)
        assertEquals(VoiceState.SPEAKING, c.state)
        assertEquals("I'm great, thanks.", tts.spoken.single().first)
        tts.callbacks!!.onDone()
        assertEquals(VoiceState.IDLE, c.state)
    }

    @Test fun `permission denied shows actionable error`() {
        permission = false
        press()
        assertEquals(1, permissionRequests)
        assertEquals(0, rec.starts)
        c.onPermissionResult(false)
        assertEquals(VoiceState.ERROR, c.state)
        assertTrue(c.machine.snapshot.detail.contains("permission", ignoreCase = true))
    }

    @Test fun `permission granted starts listening`() {
        permission = false
        press()
        permission = true
        c.onPermissionResult(true)
        assertEquals(VoiceState.LISTENING, c.state)
        assertEquals(1, rec.starts)
    }

    @Test fun `rapid repeated mic clicks start exactly one recognizer`() {
        now += 1000
        repeat(10) { c.onMicPressed(); now += 50 }
        assertEquals(1, rec.starts)
        assertEquals(VoiceState.LISTENING, c.state)
    }

    @Test fun `second press while listening finishes capture instead of restarting`() {
        press(); press()
        assertEquals(1, rec.starts)
        assertEquals(1, rec.stops)
    }

    @Test fun `silence and no match produce retryable errors`() {
        press()
        rec.callbacks!!.onError(RecognitionError.SPEECH_TIMEOUT)
        assertEquals(VoiceState.ERROR, c.state)
        press()
        assertEquals(VoiceState.LISTENING, c.state)
        rec.callbacks!!.onResult("   ")
        assertEquals(VoiceState.ERROR, c.state)
        assertEquals(RecognitionError.NO_MATCH.message, c.machine.snapshot.detail)
    }

    @Test fun `recognizer that never answers is timed out by watchdog`() {
        press()
        sched.fireAll()
        assertEquals(VoiceState.ERROR, c.state)
        assertEquals(RecognitionError.TIMEOUT.message, c.machine.snapshot.detail)
        assertTrue(rec.cancels >= 1)
    }

    @Test fun `late callbacks from cancelled session are ignored`() {
        press()
        val stale = rec.callbacks!!
        c.cancelListening()
        stale.onResult("ghost")
        assertTrue(utterances.isEmpty())
        assertEquals(VoiceState.IDLE, c.state)
    }

    @Test fun `stop speaking interrupts and returns to idle`() {
        c.speak("A long answer. With several sentences.")
        assertEquals(VoiceState.SPEAKING, c.state)
        val stale = tts.callbacks!!
        c.interruptSpeech()
        assertEquals(VoiceState.IDLE, c.state)
        assertTrue(tts.stops >= 1)
        stale.onDone() // late completion from interrupted utterance
        assertEquals(VoiceState.IDLE, c.state)
    }

    @Test fun `mic press while speaking barges in`() {
        c.speak("Talking")
        press()
        assertTrue(tts.stops >= 1)
        assertEquals(VoiceState.LISTENING, c.state)
    }

    @Test fun `worker or model unavailable surfaces error state`() {
        press(); rec.callbacks!!.onResult("hello")
        c.onResponseFailed("Windows worker unavailable: connection refused")
        assertEquals(VoiceState.ERROR, c.state)
        assertTrue(c.machine.snapshot.detail.contains("unavailable"))
        press()
        assertEquals(VoiceState.LISTENING, c.state) // retry works
    }

    @Test fun `telugu replies are spoken with telugu voice`() {
        c.speak("నమస్తే, నేను బాగున్నాను.")
        assertEquals(AppLanguage.TELUGU, tts.spoken.single().second)
    }

    @Test fun `selected language is passed to recognizer`() {
        c.language = AppLanguage.TELUGU
        press()
        assertEquals(AppLanguage.TELUGU, rec.lastLanguage)
    }

    @Test fun `text only mode never opens the microphone or speaks`() {
        c.mode = VoiceMode.TEXT_ONLY
        press()
        assertEquals(0, rec.starts)
        assertEquals(VoiceState.ERROR, c.state)
        c.dismissError()
        assertTrue(c.beginProcessing())
        c.onResponseReady("hello", speakReply = true)
        assertTrue(tts.spoken.isEmpty())
        assertEquals(VoiceState.IDLE, c.state)
    }

    @Test fun `continuous mode relistens after speaking and pauses on silence`() {
        c.mode = VoiceMode.CONTINUOUS
        press(); rec.callbacks!!.onResult("hi")
        c.onResponseReady("Hello!", true)
        tts.callbacks!!.onDone()
        assertEquals(VoiceState.LISTENING, c.state)
        assertEquals(2, rec.starts)
        rec.callbacks!!.onError(RecognitionError.SPEECH_TIMEOUT)
        assertEquals(VoiceState.IDLE, c.state) // quiet pause, no error loop
    }

    @Test fun `background closes microphone`() {
        press()
        c.onBackground()
        assertEquals(VoiceState.IDLE, c.state)
        assertTrue(rec.cancels >= 1)
    }

    @Test fun `unavailable speech service`() {
        rec.available = false
        press()
        assertEquals(VoiceState.ERROR, c.state)
        assertFalse(rec.starts > 0)
    }

    @Test fun `code blocks are not read aloud`() {
        c.speak("Here it is:\n```python\nprint('x')\n```\nDone.")
        assertEquals("Here it is: I've shown the code on screen. Done.", tts.spoken.single().first)
    }
}
