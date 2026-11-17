package com.friday.app.core

/**
 * Single source of truth for FRIDAY's voice interaction state.
 *
 * Every UI indicator (orb animation, status text, mic button) is derived from [state], and
 * every pipeline stage must request a transition here before it touches the microphone or
 * the speaker. Invalid transitions are rejected, which is what prevents two recognizers or
 * two speech sessions from running at the same time.
 */
enum class VoiceState { IDLE, LISTENING, PROCESSING, SPEAKING, INTERRUPTED, ERROR }

class VoiceStateMachine(initial: VoiceState = VoiceState.IDLE) {
    data class Snapshot(val state: VoiceState, val detail: String = "", val sequence: Long = 0)

    companion object {
        /** Allowed transitions. Self transitions are never allowed (they indicate a duplicate start). */
        val TRANSITIONS: Map<VoiceState, Set<VoiceState>> = mapOf(
            VoiceState.IDLE to setOf(VoiceState.LISTENING, VoiceState.PROCESSING, VoiceState.SPEAKING, VoiceState.ERROR),
            VoiceState.LISTENING to setOf(VoiceState.PROCESSING, VoiceState.IDLE, VoiceState.ERROR, VoiceState.INTERRUPTED),
            VoiceState.PROCESSING to setOf(VoiceState.SPEAKING, VoiceState.IDLE, VoiceState.ERROR, VoiceState.INTERRUPTED),
            VoiceState.SPEAKING to setOf(VoiceState.IDLE, VoiceState.INTERRUPTED, VoiceState.ERROR, VoiceState.LISTENING),
            VoiceState.INTERRUPTED to setOf(VoiceState.IDLE, VoiceState.LISTENING, VoiceState.PROCESSING, VoiceState.SPEAKING),
            VoiceState.ERROR to setOf(VoiceState.IDLE, VoiceState.LISTENING, VoiceState.PROCESSING, VoiceState.SPEAKING)
        )

        fun canTransition(from: VoiceState, to: VoiceState): Boolean = TRANSITIONS[from]?.contains(to) == true
    }

    private val listeners = mutableListOf<(Snapshot) -> Unit>()
    @Volatile private var current = Snapshot(initial)

    val state: VoiceState get() = current.state
    val snapshot: Snapshot get() = current

    fun addListener(listener: (Snapshot) -> Unit) { synchronized(listeners) { listeners += listener } }

    /** Attempts a transition. Returns false (and changes nothing) when it is not allowed. */
    fun transition(to: VoiceState, detail: String = ""): Boolean {
        val next: Snapshot
        synchronized(this) {
            if (!canTransition(current.state, to)) return false
            next = Snapshot(to, detail, current.sequence + 1)
            current = next
        }
        notifyListeners(next)
        return true
    }

    /** Updates the detail text without changing state (e.g. partial transcription while listening). */
    fun updateDetail(expected: VoiceState, detail: String): Boolean {
        val next: Snapshot
        synchronized(this) {
            if (current.state != expected) return false
            next = current.copy(detail = detail, sequence = current.sequence + 1)
            current = next
        }
        notifyListeners(next)
        return true
    }

    /** Returns to IDLE from any state (used for hard resets such as lifecycle stop). */
    fun reset(detail: String = "") {
        val next: Snapshot
        synchronized(this) {
            next = Snapshot(VoiceState.IDLE, detail, current.sequence + 1)
            current = next
        }
        notifyListeners(next)
    }

    private fun notifyListeners(snapshot: Snapshot) {
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { it(snapshot) }
    }
}
