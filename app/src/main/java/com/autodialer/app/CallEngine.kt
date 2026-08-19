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
    private val callLogStore: CallLogStore,
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

    /** The call-log row created the instant a number is dialed, so it counts as "called" even if no outcome ever gets tagged (app closed, crash, Stop pressed, etc). */
    private var pendingLogDate: String? = null
    private var pendingLogIndex: Int? = null

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
        // Restore any outcome that was still waiting to be tagged when the app was
        // stopped/closed last time, so the user gets asked for it again before anything
        // else can be dialed - it must never get silently skipped.
        if (saved.pendingOutcomeIndex >= 0 && saved.pendingOutcomeIndex in leads.indices) {
            pendingOutcomeIndex = saved.pendingOutcomeIndex
            pendingLogDate = saved.pendingLogDate
            pendingLogIndex = if (saved.pendingLogIndex >= 0) saved.pendingLogIndex else null
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
            listener.onEngineError("List is empty")
            return
        }
        // A previous call is still waiting for an outcome tag (Resume/Positive/etc) - this can
        // happen if Stop was pressed, or the app was closed/killed, right after a call ended.
        // Never dial the next number until that one is tagged.
        if (pendingOutcomeIndex != null) {
            registerListener()
            listener.onCallEndedAwaitingOutcome(pendingOutcomeIndex!!)
            return
        }
        registerListener()
        callsDialedThisBatch = 0
        val index = if (seq.currentIndex == -1) seq.start() else seq.continueSequence()
        if (index != null) {
            dial(index)
        } else if (seq.isComplete()) {
            finishSession()
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
        // Deliberately NOT clearing pendingOutcomeIndex here: if a call ended and its outcome
        // hasn't been tagged yet, Stop must not lose that. The next Start (even after the app
        // was fully closed) will ask for that outcome again before dialing anything else.
        listener.onSessionStopped()
        persist()
    }

    fun skip() {
        val seq = sequencer ?: return
        val index = seq.currentIndex
        val leadForLog = if (index in leads.indices) leads[index] else null
        val result = seq.skip()
        for (i in leads.indices) {
            if (seq.statuses[i] == CallSequencer.Status.SKIPPED && leads[i].status != CallSequencer.Status.SKIPPED) {
                leads[i].status = CallSequencer.Status.SKIPPED
                listener.onLeadUpdated(i, CallSequencer.Status.SKIPPED)
            }
        }
        if (leadForLog != null) {
            val date = pendingLogDate
            val logIndex = pendingLogIndex
            if (date != null && logIndex != null) {
                callLogStore.updateEntry(date, logIndex, "Skipped", null)
            } else {
                callLogStore.addEntry(
                    callLogStore.todayKey(),
                    CallLogEntry(callLogStore.nowTime(), leadForLog.name, leadForLog.phone, "Skipped", null)
                )
            }
        }
        pendingLogDate = null
        pendingLogIndex = null
        if (result != null) dial(result)
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
            // Log the number as "called" right now, at dial time - not after an outcome is
            // picked. This is what keeps it out of future lists even if the outcome never
            // gets tagged (app closed mid-call, Stop pressed, crash, etc).
            val date = callLogStore.todayKey()
            val logIndex = callLogStore.addEntry(
                date,
                CallLogEntry(callLogStore.nowTime(), lead.name, lead.phone, "Dialed", null)
            )
            pendingLogDate = date
            pendingLogIndex = logIndex
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

    /** The lead currently awaiting an outcome tag (call just ended, box still on screen). */
    fun pendingLead(): Lead? {
        val index = pendingOutcomeIndex ?: return null
        return leads.getOrNull(index)
    }

    /** Called the instant the user taps one of the outcome boxes. Dials the next number immediately. */
    fun selectOutcome(outcomeId: String) {
        val seq = sequencer ?: return
        val index = pendingOutcomeIndex ?: return
        if (index in leads.indices) {
            leads[index].outcome = outcomeId
            listener.onLeadUpdated(index, leads[index].status)
            val date = pendingLogDate
            val logIndex = pendingLogIndex
            if (date != null && logIndex != null) {
                callLogStore.updateEntry(date, logIndex, "Completed", outcomeId)
            } else {
                val lead = leads[index]
                callLogStore.addEntry(
                    callLogStore.todayKey(),
                    CallLogEntry(callLogStore.nowTime(), lead.name, lead.phone, "Completed", outcomeId)
                )
            }
        }
        pendingOutcomeIndex = null
        pendingLogDate = null
        pendingLogIndex = null
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
            finishSession()
        }
    }

    /** True while there's a loaded list that isn't fully finished yet (still has numbers to
     * call, or a call is waiting for its outcome to be tagged). Used to stop a new list from
     * being loaded on top of one that's still in progress, and to know when to show
     * "Remove Current List". */
    fun hasActiveList(): Boolean {
        if (leads.isEmpty()) return false
        if (pendingOutcomeIndex != null) return true
        return leads.any { it.status == CallSequencer.Status.PENDING || it.status == CallSequencer.Status.CALLING }
    }

    /** Discards the currently loaded list entirely (user doesn't want these numbers anymore).
     * Numbers already dialed stay correctly recorded in the call log/sheet either way. */
    fun removeCurrentList() {
        unregisterListener()
        handler.removeCallbacksAndMessages(null)
        leads = mutableListOf()
        sequencer = null
        pendingOutcomeIndex = null
        pendingLogDate = null
        pendingLogIndex = null
        sessionStore.clear()
    }

    /** Called once every number in the list has a final status (Completed/Skipped). Clears the
     * list automatically - it has already been logged number-by-number in the call log/sheet
     * as each call happened, so nothing is lost. */
    private fun finishSession() {
        unregisterListener()
        leads = mutableListOf()
        sequencer = null
        pendingOutcomeIndex = null
        pendingLogDate = null
        pendingLogIndex = null
        sessionStore.clear()
        listener.onSessionComplete()
    }

    fun persist() {
        val seq = sequencer ?: return
        sessionStore.save(
            SavedSession(
                sessionName = sessionName,
                leads = leads,
                currentIndex = seq.currentIndex,
                delaySeconds = 0,
                batchTarget = batchTarget,
                pendingOutcomeIndex = pendingOutcomeIndex ?: -1,
                pendingLogDate = pendingLogDate,
                pendingLogIndex = pendingLogIndex ?: -1
            )
        )
    }

    fun teardown() {
        handler.removeCallbacksAndMessages(null)
        unregisterListener()
    }
}
