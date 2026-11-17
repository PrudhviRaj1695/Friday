package com.friday.app

import android.content.Context
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
        private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2"
        private const val MODEL_ARCHIVE = "kokoro-int8-en-v0_19.tar.bz2"
        private const val MODEL_FOLDER = "kokoro-int8-en-v0_19"
        // English model speaker 6 is am_michael (male American English).
        const val DEFAULT_VOICE_ID = 6
    }

    @Volatile private var tts: OfflineTts? = null

    fun isInstalled(): Boolean {
        val dir = File(context.filesDir, MODEL_FOLDER)
        return File(dir, "model.int8.onnx").length() > 50_000_000L &&
            File(dir, "voices.bin").length() > 1_000_000L &&
            File(dir, "tokens.txt").exists() &&
            File(dir, "espeak-ng-data").isDirectory && File(dir, "lexicon-en-us.txt").length() > 1_000_000L
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
            val lexicon = File(target, "lexicon-en-us.txt")
            val lexiconConnection = (URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/lexicon-en-us.txt").openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
            }
            try {
                lexiconConnection.connect()
                check(lexiconConnection.responseCode in 200..299) { "English pronunciation dictionary download failed" }
                lexiconConnection.inputStream.use { input -> FileOutputStream(lexicon).use { output -> input.copyTo(output) } }
            } finally {
                lexiconConnection.disconnect()
            }
            check(lexicon.length() > 1_000_000L) { "English pronunciation dictionary is incomplete" }
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
                        lexicon = File(dir, "lexicon-en-us.txt").absolutePath
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
        } catch (e: Exception) {
            release()
            throw IllegalStateException("Kokoro initialization failed: " + e.javaClass.simpleName + ": " + (e.message ?: "no details"), e)
        }
    }

    val isReady: Boolean get() = tts != null

    class Audio(val samples: FloatArray, val sampleRate: Int)

    /**
     * Synthesizes one chunk of English text. Blocking (CPU inference) — call off the main thread.
     * Playback is handled by [com.friday.app.voice.AndroidSpeechSynthesizer] so speech can be
     * streamed sentence by sentence, paused and interrupted.
     */
    @Synchronized
    fun generate(text: String, speed: Float = 1.0f, voiceId: Int = DEFAULT_VOICE_ID): Audio? {
        val engine = tts ?: return null
        val clean = text.replace(Regex("[*_#>]"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return null
        val audio = engine.generate(clean, sid = voiceId.coerceIn(0, (engine.numSpeakers() - 1).coerceAtLeast(0)), speed = speed.coerceIn(0.5f, 2.0f))
        if (audio.samples.isEmpty()) return null
        return Audio(audio.samples, audio.sampleRate.takeIf { it > 0 } ?: engine.sampleRate())
    }

    fun release() {
        runCatching { tts?.release() }
        tts = null
    }
}

private class BufferedInputStreamCompat(input: FileInputStream) : java.io.BufferedInputStream(input)
