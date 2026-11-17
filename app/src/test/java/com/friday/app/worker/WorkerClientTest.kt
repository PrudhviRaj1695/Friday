package com.friday.app.worker

import com.friday.app.core.GenerationSettings
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** WorkerClient against an in-process HTTP server that mimics the worker protocol. */
class WorkerClientTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val bodies = CopyOnWriteArrayList<JSONObject>()
    var health = """{"status":"ok","worker_version":"2.0.0","ollama":"ok","default_model":"qwen2.5:3b","model_available":true,"models":["qwen2.5:3b"],"max_context":8192}"""
    var chatHandler: (JSONObject) -> MockResponse = { json("""{"request_id":"r","reply":"Hello!","model":"qwen2.5:3b","applied_options":{"temperature":0.2}}""") }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun ndjson(vararg lines: String) = MockResponse().setHeader("Content-Type", "application/x-ndjson").setBody(lines.joinToString("") { it + "\n" })

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.getHeader("Authorization") != "Bearer good") return json("""{"error":{"code":"unauthorized","message":"Missing or invalid pairing token."}}""", 401)
                return when (request.path) {
                    "/health" -> json(health)
                    "/v1/chat" -> JSONObject(request.body.readUtf8()).also { bodies += it }.let(chatHandler)
                    else -> json("{}", 404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private val url get() = server.url("/").toString().trimEnd('/')
    private fun client(token: String = "good", readTimeout: Int = 5_000) = WorkerClient(url, token, readTimeoutMs = readTimeout)
    private val hello = listOf(WorkerMessage("user", "hi"))

    @Test fun `health connected`() {
        val h = client().health()
        assertEquals(WorkerStatus.CONNECTED, h.status)
        assertEquals("qwen2.5:3b", h.defaultModel)
    }

    @Test fun `health model unavailable and ollama down are distinguished`() {
        health = """{"ollama":"ok","default_model":"qwen2.5:7b","model_available":false}"""
        val h = client().health()
        assertEquals(WorkerStatus.MODEL_UNAVAILABLE, h.status)
        assertTrue(h.detail.contains("ollama pull qwen2.5:7b"))
        health = """{"ollama":"unavailable","default_model":"x","model_available":false}"""
        assertTrue(client().health().detail.contains("Ollama"))
    }

    @Test fun `bad token reports unauthorized`() {
        assertEquals(WorkerStatus.UNAUTHORIZED, client("bad").health().status)
    }

    @Test fun `worker down reports worker unavailable quickly`() {
        val port = ServerSocket(0).use { it.localPort } // nothing listening
        val started = System.currentTimeMillis()
        val h = WorkerClient("http://127.0.0.1:$port", "good").health()
        assertEquals(WorkerStatus.WORKER_UNAVAILABLE, h.status)
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test fun `non streaming chat sends settings history and auth`() {
        val s = GenerationSettings(temperature = 0.2f, topP = 0.8f, topK = 12, maxOutputTokens = 256, contextLength = 2048, streaming = false)
        val deltas = mutableListOf<String>()
        val reply = client().chat("qwen2.5:3b", "SYSTEM", listOf(WorkerMessage("user", "hi"), WorkerMessage("assistant", "yo"), WorkerMessage("user", "again")), s) { deltas += it }
        assertEquals("Hello!", reply.text)
        assertEquals(listOf("Hello!"), deltas)
        val body = bodies.single()
        assertEquals(false, body.getBoolean("stream"))
        assertEquals("SYSTEM", body.getString("system"))
        assertEquals(3, body.getJSONArray("messages").length())
        val o = body.getJSONObject("options")
        assertEquals(0.2, o.getDouble("temperature"), 1e-6)
        assertEquals(0.8, o.getDouble("top_p"), 1e-6)
        assertEquals(12, o.getInt("top_k"))
        assertEquals(256, o.getInt("num_predict"))
        assertEquals(2048, o.getInt("num_ctx"))
        val rid = requests.last().getHeader("X-Request-ID")!!
        assertTrue(rid.startsWith("and-"))
        assertEquals(body.getString("request_id"), rid)
    }

    @Test fun `streaming chat delivers deltas in order including telugu`() {
        chatHandler = { body ->
            ndjson(
                """{"type":"start","request_id":"${body.getString("request_id")}","model":"qwen2.5:3b","applied_options":{"num_ctx":4096}}""",
                """{"type":"delta","text":"నమ"}""", """{"type":"delta","text":"స్తే"}""", """{"type":"delta","text":"!"}""",
                """{"type":"done","usage":{"completion_tokens":3}}"""
            ).throttleBody(40, 20, TimeUnit.MILLISECONDS)
        }
        val deltas = mutableListOf<String>()
        val reply = client().chat(null, "S", hello, GenerationSettings(streaming = true)) { deltas += it }
        assertEquals(listOf("నమ", "స్తే", "!"), deltas)
        assertEquals("నమస్తే!", reply.text)
        assertEquals(3, reply.completionTokens)
        assertEquals(4096, reply.appliedOptions["num_ctx"])
        assertEquals(true, bodies.single().getBoolean("stream"))
    }

    @Test fun `stream error event becomes typed exception`() {
        chatHandler = { ndjson("""{"type":"start"}""", """{"type":"error","code":"timeout","message":"The local model timed out."}""") }
        try { client().chat(null, "S", hello, GenerationSettings()) {}; fail() }
        catch (e: WorkerException) { assertEquals("timeout", e.code); assertEquals(WorkerStatus.WORKER_UNAVAILABLE, e.status) }
    }

    @Test fun `truncated stream is reported`() {
        chatHandler = { ndjson("""{"type":"delta","text":"par"}""") }
        try { client().chat(null, "S", hello, GenerationSettings()) {}; fail() }
        catch (e: WorkerException) { assertEquals("stream_truncated", e.code) }
    }

    @Test fun `model unavailable error from worker`() {
        chatHandler = { json("""{"error":{"code":"model_unavailable","message":"Model 'x' is not installed in Ollama. Run: ollama pull x"}}""", 404) }
        try { client().chat("x", "S", hello, GenerationSettings(streaming = false)) {}; fail() }
        catch (e: WorkerException) {
            assertEquals(WorkerStatus.MODEL_UNAVAILABLE, e.status)
            assertTrue(e.message.contains("ollama pull x"))
        }
    }

    @Test fun `busy worker error is surfaced`() {
        chatHandler = { json("""{"error":{"code":"busy","message":"The worker is busy"}}""", 429) }
        try { client().chat(null, "S", hello, GenerationSettings(streaming = false)) {}; fail() }
        catch (e: WorkerException) { assertEquals("busy", e.code) }
    }

    @Test fun `slow worker times out`() {
        chatHandler = { json("{}").setHeadersDelay(3, TimeUnit.SECONDS) }
        try { client(readTimeout = 500).chat(null, "S", hello, GenerationSettings(streaming = false)) {}; fail() }
        catch (e: WorkerException) { assertEquals("timeout", e.code) }
    }

    @Test fun `cancel aborts in-flight stream`() {
        chatHandler = { ndjson(*Array(300) { """{"type":"delta","text":"x"}""" }).throttleBody(28, 20, TimeUnit.MILLISECONDS) }
        val c = client()
        val t = Thread { Thread.sleep(300); c.cancel() }.apply { start() }
        var count = 0
        try { c.chat(null, "S", hello, GenerationSettings()) { count++ }; fail() }
        catch (e: WorkerException) { assertEquals("cancelled", e.code) }
        t.join()
        assertTrue("received $count deltas before cancel", count in 1..299)
    }
}
