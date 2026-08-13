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
    fun onWaitingForNext(seconds: Int)
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
    var delaySeconds: Int = 2

    private val telephonyManager =
        activity.getSystemService(Activity.TELEPHONY_SERVICE) as TelephonyManager
    private val handler = Handler(Looper.getMainLooper())

    // Guards against duplicate OFFHOOK->IDLE transitions being reported twice by Android.
    private var wasOffHook = false
    private var listenerRegistered = false

    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    wasOffHook = true
                }
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

    /** Restores a previously saved (interrupted) session WITHOUT auto-dialing anything. */
    fun restoreIfAvailable(): Boolean {
        val saved = sessionStore.load() ?: return false
        leads = saved.leads.toMutableList()
        sessionName = saved.sessionName
        delaySeconds = saved.delaySeconds
        sequencer = CallSequencer(leads.size)
        // Replay statuses into the sequencer so counts/UI are correct.
        for (i in leads.indices) {
            if (leads[i].status == CallSequencer.Status.CALLING) {
                // We can't safely know if that call is still active after a restart.
                // Mark it back to PENDING rather than guessing - never auto-dial on uncertainty.
                leads[i].status = CallSequencer.Status.PENDING
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

    fun start() {
        val seq = sequencer ?: return
        if (leads.isEmpty()) {
            listener.onEngineError("List khali hai")
            return
        }
        registerListener()
        val index = seq.start()
        if (index != null) {
            dial(index)
        } else {
            listener.onSessionComplete()
        }
    }

    fun pause() {
        sequencer?.pause()
        handler.removeCallbacksAndMessages(null)
        listener.onSessionPaused()
        persist()
    }

    fun resume() {
        val seq = sequencer ?: return
        val index = seq.resume()
        listener.onSessionResumed()
        if (index != null) {
            dial(index)
        }
        persist()
    }

    fun stop() {
        sequencer?.stop()
        handler.removeCallbacksAndMessages(null)
        unregisterListener()
        listener.onSessionStopped()
        persist()
    }

    fun skip() {
        val seq = sequencer ?: return
        val index = seq.skip()
        val skippedIndex = seq.currentIndex // best-effort for UI refresh of the skipped row
        listener.onLog("Skip requested")
        if (index != null) {
            dial(index)
        } else {
            // Refresh UI for whichever row got marked SKIPPED even if we didn't dial next.
            for (i in leads.indices) {
                if (seq.statuses[i] == CallSequencer.Status.SKIPPED && leads[i].status != CallSequencer.Status.SKIPPED) {
                    leads[i].status = CallSequencer.Status.SKIPPED
                    listener.onLeadUpdated(i, CallSequencer.Status.SKIPPED)
                }
            }
        }
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
        } catch (e: SecurityException) {
            listener.onEngineError("Call permission missing")
            stop()
        }
    }

    private fun handleCallEnded() {
        val seq = sequencer ?: return
        val ended = seq.onCallEnded() // returns false automatically on duplicate events
        if (!ended) {
            listener.onLog("Duplicate call-state event ignored")
            return
        }
        val endedIndex = seq.let { s -> leads.indices.firstOrNull { s.statuses[it] == CallSequencer.Status.COMPLETED && leads[it].status != CallSequencer.Status.COMPLETED } }
        if (endedIndex != null) {
            leads[endedIndex].status = CallSequencer.Status.COMPLETED
            listener.onLeadUpdated(endedIndex, CallSequencer.Status.COMPLETED)
        }
        persist()

        if (seq.paused || seq.stopped) {
            return
        }

        listener.onWaitingForNext(delaySeconds)
        handler.postDelayed({
            val nextIndex = seq.advance()
            if (nextIndex != null) {
                dial(nextIndex)
            } else {
                unregisterListener()
                listener.onSessionComplete()
            }
        }, delaySeconds * 1000L)
    }

    fun persist() {
        val seq = sequencer ?: return
        sessionStore.save(
            SavedSession(
                sessionName = sessionName,
                leads = leads,
                currentIndex = seq.currentIndex,
                delaySeconds = delaySeconds
            )
        )
    }

    fun teardown() {
        handler.removeCallbacksAndMessages(null)
        unregisterListener()
    }
}
