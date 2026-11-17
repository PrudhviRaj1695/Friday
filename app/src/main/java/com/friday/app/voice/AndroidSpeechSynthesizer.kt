package com.friday.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.friday.app.KokoroVoiceEngine
import com.friday.app.core.AppLanguage
import com.friday.app.core.SpeakCallbacks
import com.friday.app.core.SpeechSynthesizerPort
import com.friday.app.core.SpeechText
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class VoiceEnginePreference(val label: String, val description: String) {
    AUTO("Auto", "Kokoro neural voice for English, Android system voice for Telugu"),
    KOKORO_ONLY("Kokoro only", "Offline neural English voice only; Telugu replies are not spoken"),
    SYSTEM_ONLY("System voice", "Android's installed text-to-speech for every language")
}

data class VoiceOutputSettings(
    val engine: VoiceEnginePreference = VoiceEnginePreference.AUTO,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f
)

/**
 * TTS + playback stage. English → Kokoro (sentence-pipelined: the next sentence is synthesized
 * while the current one plays). Telugu, or English without Kokoro → Android system TTS.
 * Holds transient audio focus while speaking and stops if another app takes focus.
 */
class AndroidSpeechSynthesizer(
    context: Context,
    private val kokoro: KokoroVoiceEngine,
    private val system: SystemTts,
    var settings: () -> VoiceOutputSettings
) : SpeechSynthesizerPort {
    private val tag = "FridayTTS"
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val generation = AtomicLong(0)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var track: AudioTrack? = null
    @Volatile private var paused = false
    @Volatile private var usingKokoro = false
    private var focusRequest: AudioFocusRequest? = null

    /** Only Kokoro playback (our own AudioTrack) can pause; Android TextToSpeech has no pause API. */
    val pausable: Boolean get() = usingKokoro

    /** Engine that would be used for [language] right now, or null with a reason. */
    fun resolveEngine(language: AppLanguage): Pair<String?, String> {
        val pref = settings().engine
        val kokoroOk = kokoro.isReady
        return when {
            language == AppLanguage.ENGLISH && pref != VoiceEnginePreference.SYSTEM_ONLY && kokoroOk -> "kokoro" to "Kokoro neural voice (offline)"
            language == AppLanguage.ENGLISH && pref == VoiceEnginePreference.KOKORO_ONLY -> null to "Kokoro voice is not installed or failed to initialize. Download it in Settings → Voice, or choose Auto."
            language == AppLanguage.TELUGU && pref == VoiceEnginePreference.KOKORO_ONLY -> null to "Kokoro has no Telugu voice. Choose Auto or System voice in Settings → Voice to hear Telugu replies."
            else -> "system" to system.availabilityMessage(language)
        }
    }

    override fun speak(text: String, language: AppLanguage, callbacks: SpeakCallbacks) {
        stopPlayback()
        val gen = generation.incrementAndGet()
        val (engine, reason) = resolveEngine(language)
        Log.i(tag, "speak gen=$gen lang=${language.bcp47} engine=$engine chars=${text.length}")
        if (engine == null) { callbacks.onError(reason); return }
        val chunks = SpeechText.chunks(text)
        if (chunks.isEmpty()) { callbacks.onDone(); return }
        if (!requestFocus()) Log.w(tag, "audio focus not granted; speaking anyway")
        // Callbacks are delivered on the main thread so the voice coordinator stays single-threaded.
        val done = { main.post { if (generation.get() == gen) { abandonFocus(); callbacks.onDone() } }; Unit }
        val failed = { msg: String -> main.post { if (generation.get() == gen) { abandonFocus(); Log.w(tag, "speech failed: $msg"); callbacks.onError(msg) } }; Unit }
        usingKokoro = engine == "kokoro"
        callbacks.onStart()
        val s = settings()
        if (engine == "system") system.speak(chunks, language, s.speed, s.pitch, done, failed)
        else speakKokoro(gen, chunks, s.speed, done, failed)
    }

    private fun speakKokoro(gen: Long, chunks: List<String>, speed: Float, onDone: () -> Unit, onError: (String) -> Unit) {
        val queue = LinkedBlockingQueue<Any>(2) // bounded: at most one sentence synthesized ahead
        val end = Any()
        Thread({
            try {
                for (chunk in chunks) {
                    if (generation.get() != gen) return@Thread
                    val audio = kokoro.generate(chunk, speed) ?: continue
                    while (generation.get() == gen && !queue.offer(audio, 100, TimeUnit.MILLISECONDS)) Unit
                }
                while (generation.get() == gen && !queue.offer(end, 100, TimeUnit.MILLISECONDS)) Unit
            } catch (e: Throwable) {
                Log.e(tag, "kokoro synthesis failed", e)
                queue.clear(); queue.offer(e)
            }
        }, "friday-kokoro-synth").start()
        Thread({
            try {
                while (generation.get() == gen) {
                    val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    when (item) {
                        end -> { onDone(); return@Thread }
                        is Throwable -> { onError("Kokoro voice failed: ${item.javaClass.simpleName}"); return@Thread }
                        is KokoroVoiceEngine.Audio -> play(gen, item)
                    }
                }
            } catch (e: Throwable) {
                Log.e(tag, "kokoro playback failed", e)
                onError("Audio playback failed: ${e.javaClass.simpleName}")
            } finally { releaseTrack() }
        }, "friday-kokoro-play").start()
    }

    private fun play(gen: Long, audio: KokoroVoiceEngine.Audio) {
        val rate = audio.sampleRate
        val current = track?.takeIf { it.sampleRate == rate } ?: run {
            releaseTrack()
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setBufferSizeInBytes((min * 2).coerceAtLeast(8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().also { track = it; it.play() }
        }
        val startHead = current.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        var offset = 0
        while (offset < audio.samples.size && generation.get() == gen) {
            if (paused) { Thread.sleep(40); continue }
            val written = current.write(audio.samples, offset, minOf(4096, audio.samples.size - offset), AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
        // Wait for this chunk to finish playing so "done" is reported when audio actually ends.
        while (generation.get() == gen && ((current.playbackHeadPosition.toLong() and 0xFFFFFFFFL) - startHead) < audio.samples.size) Thread.sleep(20)
    }

    override fun stop() {
        generation.incrementAndGet()
        stopPlayback()
        abandonFocus()
    }

    private fun stopPlayback() {
        paused = false
        system.stop()
        releaseTrack()
    }

    @Synchronized private fun releaseTrack() {
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush(); t.stop() }
        runCatching { t.release() }
    }

    override fun pause(): Boolean {
        if (!usingKokoro) return false // Android TextToSpeech has no pause API
        val t = track ?: return false
        paused = true
        runCatching { t.pause() }
        return true
    }

    override fun resume(): Boolean {
        if (!usingKokoro || !paused) return false
        paused = false
        runCatching { track?.play() }
        return true
    }

    private fun requestFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    Log.i(tag, "audio focus lost ($change); stopping speech")
                    main.post { onFocusLost?.invoke() }
                }
            }.build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** Set by the owner to route focus loss (incoming call, other media) to the voice coordinator. */
    var onFocusLost: (() -> Unit)? = null

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    fun shutdown() {
        stop()
        system.shutdown()
    }
}
