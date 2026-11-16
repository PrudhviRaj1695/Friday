package com.friday.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/** Offline Kokoro speech engine. Files are kept in app-private storage. */
class LocalVoiceEngine(private val context: Context) {
    companion object {
        const val ARCHIVE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2"
        private const val MODEL_DIR = "kokoro-int8-en-v0_19"
    }

    private val voiceDir = File(context.filesDir, "voice/$MODEL_DIR")
    private var tts: OfflineTts? = null
    private var currentTrack: AudioTrack? = null
    private val stopRequested = AtomicBoolean(false)

    fun isInstalled() =
        File(voiceDir, "model.int8.onnx").length() > 10_000_000L &&
        File(voiceDir, "voices.bin").length() > 1_000_000L &&
        File(voiceDir, "tokens.txt").exists() &&
        File(voiceDir, "espeak-ng-data").isDirectory

    fun download(progress: (Int) -> Unit) {
        val root = voiceDir.parentFile ?: error("Invalid voice model directory")
        root.mkdirs()
        val archive = File(root, "kokoro.tar.bz2.part")
        val connection = (URL(ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "FRIDAY-Android/0.3")
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "Kokoro download failed: HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            check(total in 20_000_000L..500_000_000L) { "Unexpected Kokoro archive size: $total bytes" }
            connection.inputStream.use { input ->
                archive.outputStream().buffered(1024 * 1024).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        progress(((copied * 100) / total).toInt().coerceIn(0, 100))
                    }
                    check(copied == total) { "Kokoro archive download was incomplete" }
                }
            }
        } finally { connection.disconnect() }

        val staging = File(root, "$MODEL_DIR.installing")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            BufferedInputStream(FileInputStream(archive)).use { buffered ->
                BZip2CompressorInputStream(buffered).use { bz2 ->
                    TarArchiveInputStream(bz2).use { tar ->
                        while (true) {
                            val entry = tar.nextTarEntry ?: break
                            val name = entry.name.replace('\\', '/')
                            check(!name.startsWith("/") && name.split('/').none { it == ".." }) { "Unsafe archive entry" }
                            val relative = name.substringAfter("$MODEL_DIR/", "")
                            if (relative.isBlank()) continue
                            val out = File(staging, relative)
                            check(out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Unsafe archive path" }
                            if (entry.isDirectory) out.mkdirs() else {
                                out.parentFile?.mkdirs()
                                out.outputStream().buffered().use { output -> tar.copyTo(output) }
                            }
                        }
                    }
                }
            }
            check(File(staging, "model.int8.onnx").length() > 10_000_000L) { "Kokoro INT8 model missing" }
            check(File(staging, "voices.bin").exists() && File(staging, "tokens.txt").exists()) { "Kokoro voice files missing" }
            check(File(staging, "espeak-ng-data").isDirectory) { "Kokoro phonemizer data missing" }
            voiceDir.deleteRecursively()
            check(staging.renameTo(voiceDir)) { "Could not install Kokoro voice model" }
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    @Synchronized fun initialize() {
        if (tts != null) return
        check(isInstalled()) { "Download the FRIDAY natural voice model first" }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(voiceDir, "model.int8.onnx").absolutePath,
                    voices = File(voiceDir, "voices.bin").absolutePath,
                    tokens = File(voiceDir, "tokens.txt").absolutePath,
                    dataDir = File(voiceDir, "espeak-ng-data").absolutePath,
                    lang = "eng"
                ),
                numThreads = 4,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1,
            silenceScale = 0.18f
        )
        tts = OfflineTts(config = config)
    }

    fun stop() {
        stopRequested.set(true)
        try { currentTrack?.pause(); currentTrack?.flush(); currentTrack?.stop() } catch (_: Exception) { }
    }

    fun speak(text: String, speakerId: Int = 0, speed: Float = 1.0f) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        stopRequested.set(false)
        val engine = checkNotNull(tts) { "Natural voice engine is not initialized" }
        val sampleRate = engine.sampleRate()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        currentTrack = track
        track.play()
        try {
            engine.generateWithCallback(clean, speakerId, speed) { samples ->
                if (stopRequested.get()) return@generateWithCallback 0
                val pcm = ShortArray(samples.size) { i -> (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
                var offset = 0
                while (offset < pcm.size && !stopRequested.get()) {
                    val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }
                if (stopRequested.get()) 0 else 1
            }
        } finally {
            try { track.stop() } catch (_: Exception) { }
            track.release()
            if (currentTrack === track) currentTrack = null
        }
    }

    @Synchronized fun release() {
        stop()
        try { tts?.free() } catch (_: Exception) { }
        tts = null
    }
}