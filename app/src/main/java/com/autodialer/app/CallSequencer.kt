package com.autodialer.app

/**
 * Pure state machine for sequential dialing. No Android dependencies here on purpose,
 * so it can be unit tested directly on the JVM (see CallSequencerTest).
 *
 * The single most important guarantee this class provides:
 *   A CALL_ENDED signal (even if delivered twice due to duplicate phone-state events)
 *   can only cause the sequence to advance ONCE.
 */
class CallSequencer(val total: Int) {

    enum class Status { PENDING, CALLING, COMPLETED, SKIPPED }

    val statuses = MutableList(total) { Status.PENDING }

    var currentIndex = -1
        private set
    var paused = false
        private set
    var stopped = false
        private set
    var callActive = false
        private set

    /** Begins the session. Returns the index to dial, or null if nothing to dial. */
    fun start(): Int? {
        stopped = false
        paused = false
        currentIndex = nextPending(-1)
        return tryBeginCall()
    }

    private fun tryBeginCall(): Int? {
        if (stopped || paused) return null
        if (callActive) return null // guard: never start a second call while one is active
        if (currentIndex == -1) return null
        callActive = true
        statuses[currentIndex] = Status.CALLING
        return currentIndex
    }

    /**
     * Call when the phone truly transitioned OFFHOOK -> IDLE for the active call.
     * Returns true exactly once for a real call end; returns false for any
     * duplicate/spurious event (e.g. a second IDLE broadcast for the same call).
     */
    fun onCallEnded(): Boolean {
        if (!callActive) return false
        callActive = false
        if (currentIndex in statuses.indices) statuses[currentIndex] = Status.COMPLETED
        return true
    }

    /**
     * Call after the post-call delay elapses to move to the next number and dial it.
     * Returns the index to dial, or null if paused/stopped/finished.
     */
    fun advance(): Int? {
        if (stopped || paused) return null
        currentIndex = nextPending(currentIndex)
        return tryBeginCall()
    }

    fun pause() {
        paused = true
    }

    /** Resumes a paused session. Returns index to dial now, or null (e.g. a call is still active). */
    fun resume(): Int? {
        if (!paused) return null
        paused = false
        if (callActive) return null // will continue naturally when the active call ends
        return advance()
    }

    fun stop() {
        stopped = true
        paused = false
        callActive = false
    }

    /** Skips the current pending/waiting number. Cannot skip a call that is actively in progress. */
    fun skip(): Int? {
        if (callActive) return null
        if (currentIndex in statuses.indices && statuses[currentIndex] == Status.PENDING) {
            statuses[currentIndex] = Status.SKIPPED
        } else if (currentIndex in statuses.indices) {
            statuses[currentIndex] = Status.SKIPPED
        }
        return advance()
    }

    fun nextPending(from: Int): Int {
        for (i in (from + 1) until total) {
            if (statuses[i] == Status.PENDING) return i
        }
        return -1
    }

    fun isComplete(): Boolean = currentIndex == -1 && !callActive

    fun counts(): Map<Status, Int> = Status.values().associateWith { s -> statuses.count { it == s } }
}
