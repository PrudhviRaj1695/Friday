package com.friday.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStateMachineTest {
    @Test fun `happy path voice turn`() {
        val m = VoiceStateMachine()
        assertTrue(m.transition(VoiceState.LISTENING))
        assertTrue(m.transition(VoiceState.PROCESSING))
        assertTrue(m.transition(VoiceState.SPEAKING))
        assertTrue(m.transition(VoiceState.IDLE))
        assertEquals(VoiceState.IDLE, m.state)
    }

    @Test fun `duplicate listening start is rejected`() {
        val m = VoiceStateMachine()
        assertTrue(m.transition(VoiceState.LISTENING))
        assertFalse(m.transition(VoiceState.LISTENING))
    }

    @Test fun `cannot speak while listening or listen while processing`() {
        val m = VoiceStateMachine()
        m.transition(VoiceState.LISTENING)
        assertFalse(m.transition(VoiceState.SPEAKING))
        m.transition(VoiceState.PROCESSING)
        assertFalse(m.transition(VoiceState.LISTENING))
    }

    @Test fun `every state can recover to idle`() {
        VoiceState.entries.filter { it != VoiceState.IDLE }.forEach { s ->
            assertTrue("$s -> IDLE", VoiceStateMachine.canTransition(s, VoiceState.IDLE))
        }
    }

    @Test fun `listeners observe transitions and details`() {
        val m = VoiceStateMachine()
        val seen = mutableListOf<VoiceStateMachine.Snapshot>()
        m.addListener { seen += it }
        m.transition(VoiceState.LISTENING, "Listening…")
        m.updateDetail(VoiceState.LISTENING, "hello fri")
        assertFalse(m.updateDetail(VoiceState.SPEAKING, "ignored"))
        assertEquals(listOf("Listening…", "hello fri"), seen.map { it.detail })
    }
}
