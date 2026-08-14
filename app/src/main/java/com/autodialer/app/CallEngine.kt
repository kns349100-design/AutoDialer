package com.autodialer.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

interface CallEngineListener {
    fun onLeadUpdated(index: Int, status: CallSequencer.Status)
    fun onDialing(index: Int, lead: Lead)
    fun onCallEndedAwaitingOutcome(index: Int)
    fun onBatchComplete(callsDone: Int)
    fun onSessionPaused()
    fun onSessionResumed()
    fun onSessionStopped()
    fun onSessionComplete()
    fun onEngineError(message: String)
    fun onLog(message: String)
}

class CallEngine(
    private val activity: Activity,
    private val sessionStore: SessionStore,
    private val listener: CallEngineListener
) {
    var leads: MutableList<Lead> = mutableListOf()
        private set

    private var sequencer: CallSequencer? = null
    private var sessionName: String = "Untitled Session"

    /** 0 = unlimited (dial the whole list without stopping). */
    var batchTarget: Int = 0
    private var callsDialedThisBatch = 0

    /** Index of the lead whose call just ended and is waiting for the user to tag an outcome. */
    private var pendingOutcomeIndex: Int? = null

    private val telephonyManager =
        activity.getSystemService(Activity.TELEPHONY_SERVICE) as TelephonyManager
    private val handler = Handler(Looper.getMainLooper())

    private var wasOffHook = false
    private var listenerRegistered = false

    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> wasOffHook = true
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (wasOffHook) {
                        wasOffHook = false
                        handleCallEnded()
                    }
                }
            }
        }
    }

    fun loadLeads(newLeads: List<Lead>, name: String) {
        leads = newLeads.toMutableList()
        sessionName = name
        sequencer = CallSequencer(leads.size)
        persist()
    }

    fun restoreIfAvailable(): Boolean {
        val saved = sessionStore.load() ?: return false
        leads = saved.leads.toMutableList()
        sessionName = saved.sessionName
        batchTarget = saved.batchTarget
        sequencer = CallSequencer(leads.size)
        for (i in leads.indices) {
            if (leads[i].status == CallSequencer.Status.CALLING) {
                leads[i].status = CallSequencer.Status.PENDING // never guess - avoid auto-dial on uncertainty
            }
            sequencer!!.statuses[i] = leads[i].status
        }
        listener.onLog("Session restored: $sessionName (auto-dial NOT triggered)")
        return true
    }

    private fun registerListener() {
        if (!listenerRegistered) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            listenerRegistered = true
        }
    }

    private fun unregisterListener() {
        if (listenerRegistered) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            listenerRegistered = false
        }
    }

    /** Used for both the very first start AND continuing after a batch limit / manual pause. */
    fun start() {
        val seq = sequencer
        if (seq == null || leads.isEmpty()) {
            listener.onEngineError("List khali hai")
            return
        }
        registerListener()
        callsDialedThisBatch = 0
        val index = if (seq.currentIndex == -1) seq.start() else seq.resume()
        if (index != null) {
            dial(index)
        } else if (seq.isComplete()) {
            unregisterListener()
            listener.onSessionComplete()
        }
    }

    fun pause() {
        sequencer?.pause()
        listener.onSessionPaused()
        persist()
    }

    fun resume() = start()

    fun stop() {
        sequencer?.stop()
        handler.removeCallbacksAndMessages(null)
        unregisterListener()
        pendingOutcomeIndex = null
        listener.onSessionStopped()
        persist()
    }

    fun skip() {
        val seq = sequencer ?: return
        val index = seq.skip()
        for (i in leads.indices) {
            if (seq.statuses[i] == CallSequencer.Status.SKIPPED && leads[i].status != CallSequencer.Status.SKIPPED) {
                leads[i].status = CallSequencer.Status.SKIPPED
                listener.onLeadUpdated(i, CallSequencer.Status.SKIPPED)
            }
        }
        if (index != null) dial(index)
        persist()
    }

    private fun dial(index: Int) {
        if (index !in leads.indices) return
        val lead = leads[index]
        leads[index].status = CallSequencer.Status.CALLING
        listener.onLeadUpdated(index, CallSequencer.Status.CALLING)
        listener.onDialing(index, lead)
        listener.onLog("Calling ${lead.phone}")

        if (telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE) {
            listener.onEngineError("Phone already busy on another call - stopping to stay safe")
            stop()
            return
        }

        val intent = Intent(Intent.ACTION_CALL)
        intent.data = Uri.parse("tel:${lead.phone}")
        try {
            activity.startActivity(intent)
            callsDialedThisBatch += 1
        } catch (e: SecurityException) {
            listener.onEngineError("Call permission missing")
            stop()
        }
    }

    private fun handleCallEnded() {
        val seq = sequencer ?: return
        val ended = seq.onCallEnded()
        if (!ended) {
            listener.onLog("Duplicate call-state event ignored")
            return
        }
        val endedIndex = leads.indices.firstOrNull {
            seq.statuses[it] == CallSequencer.Status.COMPLETED && leads[it].status != CallSequencer.Status.COMPLETED
        }
        if (endedIndex != null) {
            leads[endedIndex].status = CallSequencer.Status.COMPLETED
            listener.onLeadUpdated(endedIndex, CallSequencer.Status.COMPLETED)
            pendingOutcomeIndex = endedIndex
        }
        persist()

        if (seq.paused || seq.stopped) return

        // Wait for the user to tap one of the 4 outcome boxes - no timer here.
        if (endedIndex != null) {
            listener.onCallEndedAwaitingOutcome(endedIndex)
        }
    }

    /** Called the instant the user taps one of the 4 outcome boxes. Dials the next number immediately. */
    fun selectOutcome(tag: OutcomeTag) {
        val seq = sequencer ?: return
        val index = pendingOutcomeIndex ?: return
        if (index in leads.indices) {
            leads[index].outcome = tag
            listener.onLeadUpdated(index, leads[index].status)
        }
        pendingOutcomeIndex = null
        persist()

        if (batchTarget > 0 && callsDialedThisBatch >= batchTarget) {
            seq.pause()
            unregisterListener()
            listener.onBatchComplete(callsDialedThisBatch)
            return
        }

        val next = seq.advance()
        if (next != null) {
            dial(next)
        } else {
            unregisterListener()
            listener.onSessionComplete()
        }
    }

    fun persist() {
        val seq = sequencer ?: return
        sessionStore.save(
            SavedSession(
                sessionName = sessionName,
                leads = leads,
                currentIndex = seq.currentIndex,
                delaySeconds = 0,
                batchTarget = batchTarget
            )
        )
    }

    fun teardown() {
        handler.removeCallbacksAndMessages(null)
        unregisterListener()
    }
}
