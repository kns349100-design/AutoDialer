package com.autodialer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CallSequencerTest {

    @Test
    fun `start dials the first number`() {
        val seq = CallSequencer(3)
        assertEquals(0, seq.start())
        assertEquals(CallSequencer.Status.CALLING, seq.statuses[0])
    }

    @Test
    fun `call ended then advance dials exactly the next number once`() {
        val seq = CallSequencer(3)
        seq.start()
        assertTrue(seq.onCallEnded())
        assertEquals(CallSequencer.Status.COMPLETED, seq.statuses[0])
        assertEquals(1, seq.advance())
        assertEquals(CallSequencer.Status.CALLING, seq.statuses[1])
    }

    @Test
    fun `duplicate call-ended events never cause a second dial`() {
        // This is the most important guarantee in the whole app.
        val seq = CallSequencer(3)
        seq.start()
        assertTrue(seq.onCallEnded())   // real event
        assertFalse(seq.onCallEnded())  // duplicate phone-state event - must be ignored
        assertFalse(seq.onCallEnded())  // another duplicate - must be ignored
        val next = seq.advance()
        assertEquals(1, next)
        assertEquals(CallSequencer.Status.CALLING, seq.statuses[1])
    }

    @Test
    fun `cannot advance while a call is active`() {
        val seq = CallSequencer(3)
        seq.start()
        assertNull(seq.advance()) // callActive true -> advance must refuse
    }

    @Test
    fun `pause prevents advance from dialing`() {
        val seq = CallSequencer(2)
        seq.start()
        seq.onCallEnded()
        seq.pause()
        assertNull(seq.advance())
        assertEquals(CallSequencer.Status.PENDING, seq.statuses[1])
    }

    @Test
    fun `resume continues from correct number`() {
        val seq = CallSequencer(2)
        seq.start()
        seq.onCallEnded()
        seq.pause()
        val resumed = seq.resume()
        assertEquals(1, resumed)
        assertEquals(CallSequencer.Status.CALLING, seq.statuses[1])
    }

    @Test
    fun `stop prevents any further dials permanently`() {
        val seq = CallSequencer(3)
        seq.start()
        seq.onCallEnded()
        seq.stop()
        assertNull(seq.advance())
        assertNull(seq.resume())
    }

    @Test
    fun `cannot force-skip an actively ringing or connected call`() {
        val seq = CallSequencer(3)
        seq.start()
        assertNull(seq.skip()) // callActive true -> must refuse
    }

    @Test
    fun `skip while waiting marks skipped and does not auto-dial when paused`() {
        val seq = CallSequencer(3)
        seq.start()
        seq.onCallEnded()
        assertEquals(1, seq.advance())
        seq.onCallEnded()
        seq.pause()
        val next = seq.skip()
        assertEquals(CallSequencer.Status.SKIPPED, seq.statuses[1])
        assertNull(next) // paused, so skip should not auto-dial
    }

    @Test
    fun `session completes cleanly after last number`() {
        val seq = CallSequencer(2)
        assertEquals(0, seq.start())
        seq.onCallEnded()
        assertEquals(1, seq.advance())
        seq.onCallEnded()
        assertNull(seq.advance())
        assertTrue(seq.isComplete())
    }

    @Test
    fun `continueSequence resumes after stop without re-dialing completed numbers`() {
        val seq = CallSequencer(3)
        seq.start()                 // dials 0
        seq.onCallEnded()           // 0 -> COMPLETED
        seq.advance()               // dials 1
        seq.stop()                  // user pressed Stop mid-call
        assertNull(seq.advance())   // stopped - nothing should happen

        // User pressed Start again - must continue from number 1, not re-dial number 0.
        val next = seq.continueSequence()
        assertEquals(1, next)
        assertEquals(CallSequencer.Status.COMPLETED, seq.statuses[0])
        assertEquals(CallSequencer.Status.CALLING, seq.statuses[1])
        assertFalse(seq.stopped)
    }

    @Test
    fun `continueSequence never re-dials an already completed number`() {
        val seq = CallSequencer(3)
        seq.start()
        seq.onCallEnded() // 0 completed
        seq.advance()     // dials 1
        seq.onCallEnded() // 1 completed
        seq.stop()

        val next = seq.continueSequence()
        assertEquals(2, next) // must skip straight to the only remaining PENDING number
        assertEquals(CallSequencer.Status.COMPLETED, seq.statuses[0])
        assertEquals(CallSequencer.Status.COMPLETED, seq.statuses[1])
    }
}
