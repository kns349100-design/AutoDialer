package com.autodialer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Lead(
    val name: String?,
    val phone: String,
    var status: CallSequencer.Status = CallSequencer.Status.PENDING,
    var outcome: String? = null
)

data class SavedSession(
    val sessionName: String,
    val leads: List<Lead>,
    val currentIndex: Int,
    val delaySeconds: Int,
    val batchTarget: Int = 0,
    /** -1 means "no call is awaiting an outcome tag". */
    val pendingOutcomeIndex: Int = -1,
    val pendingLogDate: String? = null,
    val pendingLogIndex: Int = -1,
    /** True right after the app sent the user to WhatsApp for the INFO outcome and is
     * waiting for them to come back before dialing the next number. Persisted so that if
     * Android kills the app in the background while WhatsApp is open (very common - WhatsApp
     * is heavy), the app still knows to resume this exact step on its own instead of the
     * flag silently resetting and leaving the flow stuck. */
    val awaitingWhatsAppReturn: Boolean = false
)

/**
 * Simple, dependency-free persistence using SharedPreferences + JSON.
 * Deliberately not using Room/SQLite to keep the build minimal and reliable
 * for this scope. Enough to survive rotation, backgrounding, and process death.
 */
class SessionStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("autodialer_session", Context.MODE_PRIVATE)
    private val leadsFile = "session_leads.json"

    private companion object {
        private val pendingLock = Any()
        private var pendingLeads: List<Lead>? = null
        private var pendingFile: String? = null

        private val writer = Executors.newSingleThreadExecutor { r ->
            Thread(r, "session-writer").also { it.isDaemon = true }
        }
    }

    /** Waits (briefly) for any queued list save to reach disk. Cheap when nothing is pending. */
    fun flush() {
        try {
            writer.submit(Runnable { }).get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Ignore - the background write will still finish on its own.
        }
    }

    private fun writeLatestLeads() {
        val leads: List<Lead>
        val filename: String
        synchronized(pendingLock) {
            leads = pendingLeads ?: return
            filename = pendingFile ?: return
            pendingLeads = null
        }
        val leadsArray = JSONArray()
        for (lead in leads) {
            val obj = JSONObject()
            obj.put("name", lead.name ?: "")
            obj.put("phone", lead.phone)
            obj.put("status", lead.status.name)
            obj.put("outcome", lead.outcome ?: "")
            leadsArray.put(obj)
        }
        FileStore.writeString(appContext, filename, leadsArray.toString())
    }

    fun save(session: SavedSession) {
        // The leads list can run into the thousands - keep it out of SharedPreferences (which
        // rewrites its entire file on every save) and in its own plain file instead. The file is
        // written on a background thread from a quick snapshot (several rapid saves collapse into
        // one write of the newest state), so saving never makes the screen wait.
        val snapshot = ArrayList<Lead>(session.leads.size)
        for (lead in session.leads) snapshot.add(Lead(lead.name, lead.phone, lead.status, lead.outcome))
        val needsSchedule: Boolean
        synchronized(pendingLock) {
            needsSchedule = pendingLeads == null
            pendingLeads = snapshot
            pendingFile = leadsFile
        }
        if (needsSchedule) writer.execute { writeLatestLeads() }

        prefs.edit()
            .putString("sessionName", session.sessionName)
            .putInt("currentIndex", session.currentIndex)
            .putInt("delaySeconds", session.delaySeconds)
            .putInt("batchTarget", session.batchTarget)
            .putInt("pendingOutcomeIndex", session.pendingOutcomeIndex)
            .putString("pendingLogDate", session.pendingLogDate ?: "")
            .putInt("pendingLogIndex", session.pendingLogIndex)
            .putBoolean("awaitingWhatsAppReturn", session.awaitingWhatsAppReturn)
            .putBoolean("hasSession", true)
            .apply()
    }

    fun load(): SavedSession? {
        if (!prefs.getBoolean("hasSession", false)) return null
        flush()
        return try {
            loadInternal()
        } catch (e: Exception) {
            // An unreadable saved list must never crash the app on every launch - treat it as
            // "nothing to restore" (the numbers already called are safe in the call log).
            null
        }
    }

    private fun loadInternal(): SavedSession? {
        var leadsJson = FileStore.readString(appContext, leadsFile)
        if (leadsJson == null) {
            // Fall back to the old SharedPreferences-based storage so an in-progress list
            // isn't silently lost right after this update.
            val legacy = prefs.getString("leads", null)
            if (legacy != null) {
                FileStore.writeString(appContext, leadsFile, legacy)
                prefs.edit().remove("leads").apply()
                leadsJson = legacy
            }
        }
        if (leadsJson == null) return null
        val array = JSONArray(leadsJson)
        val leads = mutableListOf<Lead>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.optString("name", "").ifEmpty { null }
            val phone = obj.getString("phone")
            val status = try {
                CallSequencer.Status.valueOf(obj.getString("status"))
            } catch (e: Exception) {
                CallSequencer.Status.PENDING
            }
            val outcome = obj.optString("outcome", "").ifEmpty { null }
            leads.add(Lead(name, phone, status, outcome))
        }
        return SavedSession(
            sessionName = prefs.getString("sessionName", "") ?: "",
            leads = leads,
            currentIndex = prefs.getInt("currentIndex", -1),
            delaySeconds = prefs.getInt("delaySeconds", 2),
            batchTarget = prefs.getInt("batchTarget", 0),
            pendingOutcomeIndex = prefs.getInt("pendingOutcomeIndex", -1),
            pendingLogDate = prefs.getString("pendingLogDate", "")?.ifEmpty { null },
            pendingLogIndex = prefs.getInt("pendingLogIndex", -1),
            awaitingWhatsAppReturn = prefs.getBoolean("awaitingWhatsAppReturn", false)
        )
    }

    fun clear() {
        synchronized(pendingLock) {
            pendingLeads = null
        }
        prefs.edit().clear().apply()
        // Queued behind any write already running, so a late save can't bring the file back.
        writer.execute { FileStore.delete(appContext, leadsFile) }
    }

    fun hasSavedSession(): Boolean = prefs.getBoolean("hasSession", false)
}
