package com.autodialer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
    val pendingLogIndex: Int = -1
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

    fun save(session: SavedSession) {
        val leadsArray = JSONArray()
        session.leads.forEach { lead ->
            val obj = JSONObject()
            obj.put("name", lead.name ?: "")
            obj.put("phone", lead.phone)
            obj.put("status", lead.status.name)
            obj.put("outcome", lead.outcome ?: "")
            leadsArray.put(obj)
        }
        // The leads list can run into the thousands - keep it out of SharedPreferences (which
        // rewrites its entire file on every save) and in its own plain file instead.
        FileStore.writeString(appContext, leadsFile, leadsArray.toString())
        prefs.edit()
            .putString("sessionName", session.sessionName)
            .putInt("currentIndex", session.currentIndex)
            .putInt("delaySeconds", session.delaySeconds)
            .putInt("batchTarget", session.batchTarget)
            .putInt("pendingOutcomeIndex", session.pendingOutcomeIndex)
            .putString("pendingLogDate", session.pendingLogDate ?: "")
            .putInt("pendingLogIndex", session.pendingLogIndex)
            .putBoolean("hasSession", true)
            .apply()
    }

    fun load(): SavedSession? {
        if (!prefs.getBoolean("hasSession", false)) return null
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
            pendingLogIndex = prefs.getInt("pendingLogIndex", -1)
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
        FileStore.delete(appContext, leadsFile)
    }

    fun hasSavedSession(): Boolean = prefs.getBoolean("hasSession", false)
}
