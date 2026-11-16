package com.friday.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fully offline Kokoro-82M neural voice using Sherpa-ONNX.
 * The model is downloaded once into app-private storage; inference and playback are local.
 */
class KokoroVoiceEngine(private val context: Context) {
    companion object {
        private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2"
        private const val MODEL_ARCHIVE = "kokoro-int8-multi-lang-v1_1.tar.bz2"
        private const val MODEL_FOLDER = "kokoro-int8-multi-lang-v1_1"
        // Kokoro multilingual speaker 16 is am_michael (male American English).
        const val DEFAULT_VOICE_ID = 16
    }

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var stopped = false

    fun isInstalled(): Boolean {
        val dir = File(context.filesDir, MODEL_FOLDER)
        return File(dir, "model.int8.onnx").length() > 50_000_000L &&
            File(dir, "voices.bin").length() > 1_000_000L &&
            File(dir, "tokens.txt").exists() &&
            File(dir, "espeak-ng-data").isDirectory && File(dir, "lexicon-us-en.txt").isFile
    }

    suspend fun download(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val root = context.filesDir
        val target = File(root, MODEL_FOLDER)
        if (isInstalled()) return@withContext true
        val archive = File(root, MODEL_ARCHIVE)
        val temp = File(root, "$MODEL_ARCHIVE.part")
        try {
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 120000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "FRIDAY-Android")
            }
            connection.connect()
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var count: Int
                    var received = 0L
                    while (input.read(buffer).also { count = it } >= 0) {
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        received += count
                        if (total > 0) onProgress(((received * 65) / total).toInt().coerceIn(0, 65))
                    }
                }
            }
            connection.disconnect()
            if (!temp.renameTo(archive)) throw IllegalStateException("Could not finalize voice model download")
            target.deleteRecursively()
            val staging = File(root, "kokoro-extract").apply { deleteRecursively(); mkdirs() }
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStreamCompat(FileInputStream(archive)))).use { tar ->
                var entry = tar.nextTarEntry
                var expanded = 0L
                while (entry != null) {
                    val output = File(staging, entry.name).canonicalFile
                    if (!output.path.startsWith(staging.canonicalPath + File.separator)) {
                        throw SecurityException("Unsafe path in voice model archive")
                    }
                    if (entry.isDirectory) output.mkdirs() else {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).use { out ->
                            val buffer = ByteArray(128 * 1024)
                            var n: Int
                            while (tar.read(buffer).also { n = it } >= 0) {
                                if (n > 0) { out.write(buffer, 0, n); expanded += n }
                                if (expanded > 1_500_000_000L) throw IllegalStateException("Voice archive exceeded safety limit")
                            }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
            val extracted = File(staging, MODEL_FOLDER)
            if (!extracted.isDirectory) throw IllegalStateException("Voice model archive has an unexpected layout")
            if (!extracted.renameTo(target)) throw IllegalStateException("Could not install voice model")
            staging.deleteRecursively()
            archive.delete()
            onProgress(100)
            isInstalled()
        } catch (e: Exception) {
            temp.delete()
            archive.delete()
            target.deleteRecursively()
            throw e
        }
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (!isInstalled()) return@withContext false
        try {
            release()
            val dir = File(context.filesDir, MODEL_FOLDER)
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = File(dir, "model.int8.onnx").absolutePath,
                        voices = File(dir, "voices.bin").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = File(dir, "espeak-ng-data").absolutePath,
                        lexicon = File(dir, "lexicon-us-en.txt").absolutePath
                    ),
                    numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4),
                    provider = "cpu",
                    debug = false
                ),
                maxNumSentences = 1,
                silenceScale = 0.25f
            )
            tts = OfflineTts(assetManager = null, config = config)
            true
        } catch (_: Exception) {
            release()
            false
        }
    }

    suspend fun speak(text: String, voiceId: Int = DEFAULT_VOICE_ID, speed: Float = 1.0f): Boolean =
        withContext(Dispatchers.Default) {
            val engine = tts ?: return@withContext false
            stopped = false
            val clean = text.replace(Regex("[*_#>]"), " ").replace(Regex("\\s+"), " ").trim()
            if (clean.isBlank()) return@withContext false
            try {
                val audio = engine.generate(clean, sid = voiceId.coerceIn(0, (engine.numSpeakers() - 1).coerceAtLeast(0)), speed = speed)
                if (stopped || audio.samples.isEmpty()) return@withContext false
                val rate = audio.sampleRate.takeIf { it > 0 } ?: engine.sampleRate()
                val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setBufferSizeInBytes((min * 2).coerceAtLeast(8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack = track
                track.play()
                var offset = 0
                while (offset < audio.samples.size && !stopped) {
                    val written = track.write(audio.samples, offset, minOf(4096, audio.samples.size - offset), AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }
                if (!stopped) {
                    while (!stopped && track.playbackHeadPosition.toLong() < audio.samples.size) Thread.sleep(25)
                }
                stop()
                true
            } catch (_: Exception) {
                stop()
                false
            }
        }

    fun stop() {
        stopped = true
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }

    fun release() {
        stop()
        runCatching { tts?.release() }
        tts = null
    }
}

private class BufferedInputStreamCompat(input: FileInputStream) : java.io.BufferedInputStream(input)
