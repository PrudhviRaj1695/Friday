package com.friday.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FridayState(
    val messages: List<ChatLine> = emptyList(),
    val modelReady: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val status: String = "Model not installed",
    val cloudApprovalEnabled: Boolean = false,
    val speakReplies: Boolean = true
)

class FridayViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        const val MODEL_NAME = "Qwen3-0.6B.litertlm"
        const val MODEL_SIZE_BYTES = 586_000_000L
        const val MODEL_URL = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm"
        private const val SYSTEM_PROMPT = "You are FRIDAY, a private personal AI assistant. Be practical, accurate, calm and concise. Do not claim you performed actions you did not perform. You run locally on the user's phone."
    }
    private val store = LocalChatStore(app)
    private val prefs = app.getSharedPreferences("friday_settings", 0)
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(FridayState(
        messages = store.readAll(),
        cloudApprovalEnabled = prefs.getBoolean("cloud_approval", false),
        speakReplies = prefs.getBoolean("speak_replies", true)
    ))
    val state: StateFlow<FridayState> = _state.asStateFlow()
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    init {
        val model = modelFile()
        if (model.exists() && model.length() in (MODEL_SIZE_BYTES - 20_000_000L)..(MODEL_SIZE_BYTES + 20_000_000L)) {
            loadModel(model.absolutePath)
        } else _state.update { it.copy(status = "Download the on-device model to begin") }
    }

    private fun modelFile(): File {
        val dir = File(getApplication<Application>().filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, MODEL_NAME)
    }

    fun downloadModel() {
        if (_state.value.downloading || _state.value.modelReady) return
        scope.launch {
            _state.update { it.copy(downloading = true, downloadProgress = 0, error = null, status = "Downloading model…") }
            try {
                withContext(Dispatchers.IO) {
                    val target = modelFile()
                    val temp = File(target.parentFile, "$MODEL_NAME.part")
                    val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 120_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "FRIDAY-Android/0.2")
                    }
                    try {
                        connection.connect()
                        val code = connection.responseCode
                        check(code in 200..299) { "Model download failed with HTTP $code" }
                        val total = connection.contentLengthLong
                        check(total in (MODEL_SIZE_BYTES - 20_000_000L)..(MODEL_SIZE_BYTES + 20_000_000L)) {
                            "Unexpected model size: " + total + " bytes. Expected approximately 586 MB."
                        }
                        connection.inputStream.use { input ->
                            temp.outputStream().buffered(1024 * 1024).use { output ->
                                val buffer = ByteArray(1024 * 1024)
                                var copied = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    copied += count
                                    val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                                    _state.update { it.copy(downloadProgress = percent) }
                                }
                            }
                        }
                        check(temp.length() == total) { "Downloaded file size does not match the model card." }
                        if (target.exists()) target.delete()
                        check(temp.renameTo(target)) { "Could not move model into private app storage." }
                    } finally {
                        connection.disconnect()
                        if (temp.exists()) temp.delete()
                    }
                }
                _state.update { it.copy(downloading = false, downloadProgress = 100, status = "Model downloaded. Loading…") }
                loadModel(modelFile().absolutePath)
            } catch (e: Exception) {
                _state.update { it.copy(downloading = false, error = e.message ?: "Model download failed", status = "Model download failed") }
            }
        }
    }

    fun loadModel(path: String) {
        scope.launch {
            _state.update { it.copy(status = "Loading on-device AI…", error = null) }
            try {
                closeCurrent()
                val cachePath = getApplication<Application>().cacheDir.absolutePath
                var loaded: Engine? = null
                var backendName = "GPU"
                try {
                    val candidate = Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = cachePath))
                    candidate.initialize()
                    loaded = candidate
                } catch (_: Exception) {
                    backendName = "CPU"
                    val candidate = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = cachePath))
                    candidate.initialize()
                    loaded = candidate
                }
                engine = loaded
                val history = store.readRecent(20).map { if (it.role == "user") Message.user(it.text) else Message.model(it.text) }
                conversation = checkNotNull(engine).createConversation(
                    ConversationConfig(systemInstruction = Contents.of(SYSTEM_PROMPT), initialMessages = history)
                )
                _state.update { it.copy(modelReady = true, status = "On-device model ready • " + backendName, error = null) }
            } catch (e: Exception) {
                conversation = null
                engine = null
                _state.update { it.copy(modelReady = false, status = "Model could not be loaded", error = e.message ?: "Unknown LiteRT-LM error") }
            }
        }
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || !_state.value.modelReady || _state.value.busy) return
        scope.launch {
            _state.update { it.copy(busy = true, error = null, messages = it.messages + ChatLine("user", clean) + ChatLine("assistant", "")) }
            store.append("user", clean)
            val response = StringBuilder()
            try {
                checkNotNull(conversation).sendMessageAsync(clean).collect { chunk ->
                    response.append(chunk.toString())
                    _state.update { current ->
                        val updated = current.messages.toMutableList()
                        if (updated.isNotEmpty() && updated.last().role == "assistant") updated[updated.lastIndex] = ChatLine("assistant", response.toString())
                        current.copy(messages = updated)
                    }
                }
                val answer = response.toString().trim()
                if (answer.isNotEmpty()) store.append("assistant", answer)
                _state.update { it.copy(busy = false) }
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "Local model inference failed") }
            }
        }
    }

    fun setCloudApproval(enabled: Boolean) {
        prefs.edit().putBoolean("cloud_approval", enabled).apply()
        _state.update { it.copy(cloudApprovalEnabled = enabled) }
    }
    fun setSpeakReplies(enabled: Boolean) {
        prefs.edit().putBoolean("speak_replies", enabled).apply()
        _state.update { it.copy(speakReplies = enabled) }
    }
    fun clearMemory() {
        store.clear()
        _state.update { it.copy(messages = emptyList()) }
    }
    private fun closeCurrent() {
        try { conversation?.close() } catch (_: Exception) { }
        conversation = null
        try { engine?.close() } catch (_: Exception) { }
        engine = null
    }
    override fun onCleared() {
        scope.launch {
            closeCurrent()
            dispatcher.close()
            executor.shutdown()
        }.invokeOnCompletion { scope.cancel() }
        super.onCleared()
    }
}