package com.friday.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class FridayAssistantService : Service() {
    companion object {
        const val ACTION_START = "com.friday.app.START_BACKGROUND_ASSISTANT"
        const val ACTION_STOP = "com.friday.app.STOP_BACKGROUND_ASSISTANT"
        @Volatile var isRunning: Boolean = false
        private const val CHANNEL = "friday_always_on"
        private const val NOTIFICATION_ID = 7400
        private const val RATE = 16000
        private const val MODEL = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
        private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = AtomicBoolean(false)
    @Volatile private var recording = false
    @Volatile private var audio: AudioRecord? = null
    @Volatile private var spotter: KeywordSpotter? = null
    private var speech: SpeechRecognizer? = null
    private var assistant: FridayViewModel? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var commandInProgress = false
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "FRIDAY background assistant", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("friday_settings", MODE_PRIVATE).edit().putBoolean("background_assistant_enabled", false).apply()
            stopSelf(); return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        if (active.compareAndSet(false, true)) {
            isRunning = true
            getSharedPreferences("friday_settings", MODE_PRIVATE).edit().putBoolean("background_assistant_enabled", true).apply()
            val n = notification("Preparing offline wake-word detector…")
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIFICATION_ID, n)
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FRIDAY:WakeWord").apply { setReferenceCounted(false); acquire() }
            scope.launch { prepare() }
        }
        return START_STICKY
    }

    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, 7401, Intent(this, FridayAssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 7402, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("FRIDAY • background assistant").setContentText(message).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop listening", stop).build()).build()
    }
    private fun status(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private suspend fun prepare() {
        try {
            if (MainActivity.activeViewModel == null && assistant == null) assistant = FridayViewModel(application)
            status("Installing offline wake-word model (one time)…")
            val dir = withContext(Dispatchers.IO) { ensureModel() }
            spotter = KeywordSpotter(config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder-epoch-12-avg-2-chunk-16-left-64.onnx").absolutePath,
                        decoder = File(dir, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx").absolutePath,
                        joiner = File(dir, "joiner-epoch-12-avg-2-chunk-16-left-64.onnx").absolutePath
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath, modelType = "zipformer2", numThreads = 1, provider = "cpu"
                ),
                keywordsFile = File(dir, "keywords.txt").absolutePath, keywordsScore = 1.8f, keywordsThreshold = 0.25f
            ))
            if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                status("On-device speech recognition is unavailable. Install an offline speech pack, then restart FRIDAY.")
                active.set(false); isRunning = false; stopSelf(); return
            }
            status("Listening locally • say “Friday”")
            startWakeLoop()
        } catch (e: Exception) {
            status("Wake-word setup failed: ${e.message ?: e.javaClass.simpleName}")
            active.set(false); isRunning = false
            getSharedPreferences("friday_settings", MODE_PRIVATE).edit().putBoolean("background_assistant_enabled", false).apply()
            stopSelf()
        }
    }

    private fun ensureModel(): File {
        val root = File(filesDir, "wakeword")
        val target = File(root, MODEL)
        val names = listOf("encoder-epoch-12-avg-2-chunk-16-left-64.onnx", "decoder-epoch-12-avg-2-chunk-16-left-64.onnx", "joiner-epoch-12-avg-2-chunk-16-left-64.onnx", "tokens.txt", "keywords.txt")
        if (names.all { File(target, it).length() > 0 }) return target
        root.mkdirs()
        val archive = File(root, "$MODEL.tar.bz2")
        val part = File(root, "$MODEL.part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20000; conn.readTimeout = 120000; conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "FRIDAY-Android"); conn.connect()
            check(conn.responseCode in 200..299) { "Model download HTTP ${conn.responseCode}" }
            conn.inputStream.use { input -> part.outputStream().buffered().use { input.copyTo(it) } }
        } finally { conn.disconnect() }
        check(part.length() in 1..100_000_000L) { "Invalid wake-word model archive" }
        archive.delete(); check(part.renameTo(archive)) { "Could not finalize wake-word model" }
        val staging = File(root, "extract").apply { deleteRecursively(); mkdirs() }
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
                var entry = tar.nextTarEntry; var total = 0L
                while (entry != null) {
                    val out = File(staging, entry.name).canonicalFile
                    check(out.path.startsWith(staging.canonicalPath + File.separator)) { "Unsafe archive path" }
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { sink ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = tar.read(buffer); if (n < 0) break
                                if (n > 0) { total += n; check(total <= 250_000_000L); sink.write(buffer, 0, n) }
                            }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
            val extracted = File(staging, MODEL)
            check(names.all { File(extracted, it).isFile && File(extracted, it).length() > 0 }) { "Wake-word model files missing" }
            target.deleteRecursively(); check(extracted.renameTo(target)) { "Could not install wake-word model" }
        } finally { staging.deleteRecursively(); archive.delete(); part.delete() }
        return target
    }

    private fun startWakeLoop() {
        if (!active.get() || commandInProgress || recording) return
        val kws = spotter ?: return
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "Unsupported microphone format" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 8000))
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
        val stream = kws.createStream("Friday")
        audio = recorder; recorder.startRecording(); recording = true
        status("Listening locally • say “Friday”")
        worker = Thread({
            val buffer = ShortArray(1600)
            try {
                while (active.get() && recording) {
                    val n = recorder.read(buffer, 0, buffer.size); if (n <= 0) continue
                    stream.acceptWaveform(FloatArray(n) { buffer[it] / 32768.0f }, RATE)
                    while (kws.isReady(stream)) {
                        kws.decode(stream)
                        if (kws.getResult(stream).keyword.isNotBlank()) {
                            recording = false; main.postDelayed({ onWakeWord() }, 180); break
                        }
                    }
                }
            } catch (_: Exception) { if (active.get()) main.post { restartWakeLoop() } }
            finally {
                runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
                runCatching { recorder.release() }; runCatching { stream.release() }
                if (audio === recorder) audio = null
            }
        }, "FRIDAY-WakeWord").apply { isDaemon = true; start() }
    }

    private fun stopWakeLoop() {
        recording = false
        audio?.let { r -> runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() } }
    }

    private fun onWakeWord() {
        if (!active.get() || commandInProgress) return
        commandInProgress = true; stopWakeLoop()
        status("FRIDAY heard you • ask your question")
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            commandInProgress = false; restartWakeLoop(); return
        }
        val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(this); speech = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
            override fun onBeginningOfSpeech() { status("FRIDAY is listening to your question…") }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { status("FRIDAY is processing your question…") }
            override fun onError(error: Int) { releaseSpeech(); commandInProgress = false; restartWakeLoop() }
            override fun onResults(results: android.os.Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                releaseSpeech()
                val vm = MainActivity.activeViewModel ?: assistant ?: FridayViewModel(application).also { assistant = it }
                if (text.isBlank()) {
                    status("Didn't catch that • say “Friday” again")
                    commandInProgress = false; restartWakeLoop(); return
                }
                if (!vm.state.value.modelReady) {
                    status("Loading your local AI model…")
                    val ready = withTimeoutOrNull(90_000) {
                        vm.state.first { it.modelReady || it.error != null }.modelReady
                    } ?: false
                    if (!ready) {
                        status("Local AI model is not ready. Open FRIDAY and finish model setup.")
                        commandInProgress = false; restartWakeLoop(); return
                    }
                }
                if (vm.state.value.voiceModelReady && !vm.state.value.voiceStatus.contains("ready", ignoreCase = true)) {
                    withTimeoutOrNull(20_000) {
                        vm.state.first { it.voiceStatus.contains("ready", ignoreCase = true) || !it.voiceModelReady }
                    }
                }
                if (!vm.state.value.voiceModelReady) status("FRIDAY voice model is not installed • install Kokoro in setup")
                if (vm.state.value.busy) {
                    status("FRIDAY is finishing another request • say Friday again shortly")
                    commandInProgress = false; restartWakeLoop(); return
                }
                status("FRIDAY is thinking • local AI"); vm.send(text)
                scope.launch {
                    var sawBusy = false
                    withTimeoutOrNull(180_000) {
                        vm.state.first { s -> if (s.busy) sawBusy = true; sawBusy && !s.busy && !s.voicePlaying }
                    }
                    delay(700); commandInProgress = false
                    if (active.get()) startWakeLoop()
                }
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        })
        val lang = getSharedPreferences("friday_settings", MODE_PRIVATE).getString("response_language", "English")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == "Telugu") "te-IN" else "en-IN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
        }
        runCatching { sr.startListening(intent) }.onFailure { releaseSpeech(); commandInProgress = false; restartWakeLoop() }
    }

    private fun releaseSpeech() {
        speech?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }; speech = null
    }
    private fun restartWakeLoop() {
        if (active.get()) scope.launch { delay(700); runCatching { startWakeLoop() }.onFailure { status("Wake-word microphone error: ${it.message}") } }
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (MainActivity.activeViewModel == null && assistant == null) {
            // The activity's ViewModel is cleared when its task is dismissed.
            // Warm a service-owned runtime now so the next wake word can be answered.
            assistant = FridayViewModel(application)
        }
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        active.set(false); isRunning = false; recording = false; stopWakeLoop(); releaseSpeech()
        runCatching { worker?.join(1000) }; worker = null
        runCatching { spotter?.release() }; spotter = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        assistant?.shutdownForService(); assistant = null
        getSharedPreferences("friday_settings", MODE_PRIVATE).edit().putBoolean("background_assistant_enabled", false).apply()
        scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}