package com.friday.app.worker

import com.friday.app.core.GenerationSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Connection status shown in the UI. Each value maps to a distinct, actionable situation. */
enum class WorkerStatus(val label: String) {
    NOT_CONFIGURED("Not configured"),
    CONNECTING("Connecting…"),
    CONNECTED("Connected"),
    DISCONNECTED("Disconnected"),
    UNAUTHORIZED("Pairing token rejected"),
    WORKER_UNAVAILABLE("Worker unavailable"),
    MODEL_UNAVAILABLE("Model unavailable")
}

data class WorkerHealth(
    val status: WorkerStatus,
    val detail: String,
    val workerVersion: String = "",
    val defaultModel: String = "",
    val models: List<String> = emptyList(),
    val maxContext: Int = 8192,
    val latencyMs: Long = 0
)

/** Typed failure from the worker link. [code] matches the worker's error codes. */
class WorkerException(val code: String, override val message: String, val status: WorkerStatus, cause: Throwable? = null) : IOException(message, cause)

data class WorkerMessage(val role: String, val content: String)

data class WorkerReply(val requestId: String, val text: String, val model: String, val appliedOptions: Map<String, Any?>, val completionTokens: Int?)

/**
 * Blocking HTTP client for the FRIDAY Windows worker. Call from a background dispatcher.
 * The pairing token is sent as a Bearer header and is never logged.
 */
class WorkerClient(
    private val baseUrl: String,
    private val token: String,
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 120_000,
    private val log: (String) -> Unit = {}
) {
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)

    fun health(): WorkerHealth {
        val started = System.nanoTime()
        return try {
            val json = JSONObject(request("GET", "/health", null, timeoutMs = 6_000))
            val latency = (System.nanoTime() - started) / 1_000_000
            val models = json.optJSONArray("models")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val defaultModel = json.optString("default_model")
            val status = when {
                json.optString("ollama") != "ok" -> WorkerStatus.MODEL_UNAVAILABLE
                !json.optBoolean("model_available") -> WorkerStatus.MODEL_UNAVAILABLE
                else -> WorkerStatus.CONNECTED
            }
            val detail = when {
                json.optString("ollama") != "ok" -> "Worker is running but Ollama is not reachable on the PC. Start Ollama."
                !json.optBoolean("model_available") -> "Model '$defaultModel' is not installed. On the PC run: ollama pull $defaultModel"
                else -> "Worker ${json.optString("worker_version")} • $defaultModel • ${latency}ms"
            }
            WorkerHealth(status, detail, json.optString("worker_version"), defaultModel, models, json.optInt("max_context", 8192), latency)
        } catch (e: WorkerException) {
            WorkerHealth(e.status, e.message)
        }
    }

    fun buildChatBody(
        requestId: String,
        model: String?,
        system: String,
        messages: List<WorkerMessage>,
        settings: GenerationSettings,
        stream: Boolean
    ): JSONObject = JSONObject()
        .put("request_id", requestId)
        .apply { if (!model.isNullOrBlank()) put("model", model) }
        .put("system", system)
        .put("stream", stream)
        .put("messages", JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } })
        .put("options", JSONObject()
            .put("temperature", round3(settings.temperature))
            .put("top_p", round3(settings.topP))
            .put("top_k", settings.topK)
            .put("num_predict", settings.maxOutputTokens.coerceIn(16, 8192))
            .put("num_ctx", settings.contextLength))

    /**
     * Sends a chat request. When [settings].streaming is true, [onDelta] receives text as it is
     * generated; otherwise it is called once with the whole reply.
     */
    fun chat(
        model: String?,
        system: String,
        messages: List<WorkerMessage>,
        settings: GenerationSettings,
        onDelta: (String) -> Unit
    ): WorkerReply {
        val requestId = "and-" + UUID.randomUUID().toString().take(12)
        val body = buildChatBody(requestId, model, system, messages, settings, settings.streaming).toString()
        log("worker chat rid=$requestId messages=${messages.size} stream=${settings.streaming} chars=${messages.sumOf { it.content.length }}")
        if (!settings.streaming) {
            val json = JSONObject(request("POST", "/v1/chat", body, requestId = requestId))
            val text = json.optString("reply")
            onDelta(text)
            return WorkerReply(json.optString("request_id", requestId), text, json.optString("model"), toMap(json.optJSONObject("options") ?: json.optJSONObject("applied_options")), json.optJSONObject("usage")?.optInt("completion_tokens"))
        }
        val conn = open("POST", "/v1/chat", readTimeoutMs, requestId)
        val text = StringBuilder()
        var applied: Map<String, Any?> = emptyMap()
        var modelName = model.orEmpty()
        var completion: Int? = null
        try {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            checkStatus(conn)
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val event = JSONObject(line)
                    when (event.optString("type")) {
                        "start" -> { modelName = event.optString("model", modelName); applied = toMap(event.optJSONObject("applied_options")) }
                        "delta" -> { val d = event.optString("text"); text.append(d); onDelta(d) }
                        "done" -> { completion = event.optJSONObject("usage")?.let { if (it.isNull("completion_tokens")) null else it.optInt("completion_tokens") }; return WorkerReply(requestId, text.toString(), modelName, applied, completion) }
                        "error" -> throw WorkerException(event.optString("code"), event.optString("message", "Worker error"), statusFor(event.optString("code")))
                    }
                }
            }
            throw WorkerException("stream_truncated", "The worker connection closed before the reply finished.", WorkerStatus.DISCONNECTED)
        } catch (e: WorkerException) {
            throw e
        } catch (e: IOException) {
            if (cancelled) throw WorkerException("cancelled", "Request cancelled", WorkerStatus.CONNECTED, e)
            throw classify(e)
        } finally {
            activeConnection.compareAndSet(conn, null)
            conn.disconnect()
        }
    }

    @Volatile private var cancelled = false

    /** Aborts the in-flight request (the worker stops generating when the stream closes). */
    fun cancel() {
        cancelled = true
        activeConnection.getAndSet(null)?.disconnect()
    }

    private fun request(method: String, path: String, body: String?, timeoutMs: Int = readTimeoutMs, requestId: String? = null): String {
        val conn = open(method, path, timeoutMs, requestId)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            checkStatus(conn)
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: WorkerException) {
            throw e
        } catch (e: IOException) {
            if (cancelled) throw WorkerException("cancelled", "Request cancelled", WorkerStatus.CONNECTED, e)
            throw classify(e)
        } finally {
            activeConnection.compareAndSet(conn, null)
            conn.disconnect()
        }
    }

    private fun open(method: String, path: String, timeoutMs: Int, requestId: String?): HttpURLConnection {
        cancelled = false
        val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json, application/x-ndjson")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (requestId != null) setRequestProperty("X-Request-ID", requestId)
        }
        activeConnection.set(conn)
        return conn
    }

    private fun checkStatus(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code in 200..299) return
        val raw = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        val err = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull()
        val errCode = err?.optString("code").takeUnless { it.isNullOrBlank() } ?: when (code) {
            401, 403 -> "unauthorized"; 404 -> "not_found"; 429 -> "busy"; 503 -> "ollama_unavailable"; 504 -> "timeout"; else -> "http_$code"
        }
        val message = err?.optString("message").takeUnless { it.isNullOrBlank() } ?: when (code) {
            401, 403 -> "The worker rejected the pairing token. Copy the token printed in the worker window."
            else -> "Worker returned HTTP $code"
        }
        throw WorkerException(errCode, message, statusFor(errCode))
    }

    private fun classify(e: IOException): WorkerException = when (e) {
        is SocketTimeoutException -> WorkerException("timeout", "The worker did not respond in time. Check that the PC is awake and on the same Wi-Fi.", WorkerStatus.WORKER_UNAVAILABLE, e)
        is ConnectException, is NoRouteToHostException -> WorkerException("unreachable", "Can't reach the worker at $baseUrl. Start start-friday.bat on the PC and allow it through Windows Firewall (Private networks).", WorkerStatus.WORKER_UNAVAILABLE, e)
        is UnknownHostException -> WorkerException("unknown_host", "The worker host name could not be resolved.", WorkerStatus.WORKER_UNAVAILABLE, e)
        else -> WorkerException("io", "Connection to the worker failed: ${e.javaClass.simpleName}", WorkerStatus.DISCONNECTED, e)
    }

    private fun toMap(o: JSONObject?): Map<String, Any?> = o?.keys()?.asSequence()?.associateWith { o.opt(it) }.orEmpty()

    companion object {
        /** Float -> Double without binary noise (0.3f must reach the worker as 0.3, not 0.30000001). */
        fun round3(v: Float): Double = Math.round(v * 1000.0) / 1000.0

        fun statusFor(code: String): WorkerStatus = when (code) {
            "unauthorized" -> WorkerStatus.UNAUTHORIZED
            "model_unavailable", "ollama_unavailable" -> WorkerStatus.MODEL_UNAVAILABLE
            "timeout", "unreachable", "unknown_host" -> WorkerStatus.WORKER_UNAVAILABLE
            else -> WorkerStatus.CONNECTED // request-level error; the link itself is fine
        }
    }
}
