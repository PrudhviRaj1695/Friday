package com.friday.app.worker

import com.friday.app.core.GenerationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Android client ↔ real Python worker integration test. Skipped unless FRIDAY_E2E_URL and
 * FRIDAY_E2E_TOKEN are set (see windows-worker/tests/run_e2e.sh, which starts the worker
 * against a fake Ollama and then runs this test).
 */
class WorkerEndToEndTest {
    private val url = System.getenv("FRIDAY_E2E_URL")
    private val token = System.getenv("FRIDAY_E2E_TOKEN")

    @Test fun `health streaming and non streaming against real worker`() {
        assumeTrue("FRIDAY_E2E_URL not set", !url.isNullOrBlank())
        val c = WorkerClient(url, token)
        val h = c.health()
        assertEquals(h.detail, WorkerStatus.CONNECTED, h.status)

        val deltas = mutableListOf<String>()
        val streamed = c.chat(null, "You are FRIDAY", listOf(WorkerMessage("user", "Hello")), GenerationSettings(temperature = 0.3f, streaming = true)) { deltas += it }
        assertTrue(deltas.size > 1)
        assertEquals(deltas.joinToString(""), streamed.text)
        assertEquals(0.3, (streamed.appliedOptions["temperature"] as Number).toDouble(), 1e-6)

        val full = c.chat(null, "You are FRIDAY", listOf(WorkerMessage("user", "Hello")), GenerationSettings(streaming = false)) {}
        assertTrue(full.text.isNotBlank())

        assertEquals(WorkerStatus.UNAUTHORIZED, WorkerClient(url, "wrong").health().status)
        try { c.chat("not-installed:1b", "S", listOf(WorkerMessage("user", "x")), GenerationSettings(streaming = false)) {}; throw AssertionError("expected failure") }
        catch (e: WorkerException) { assertEquals(WorkerStatus.MODEL_UNAVAILABLE, e.status) }
    }
}
