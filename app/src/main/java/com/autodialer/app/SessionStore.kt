package com.autodialer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Lead(
    val name: String?,
    val phone: String,
    var status: CallSequencer.Status = CallSequencer.Status.PENDING,
    var outcome: OutcomeTag? = null
)

data class SavedSession(
    val sessionName: String,
    val leads: List<Lead>,
    val currentIndex: Int,
    val delaySeconds: Int,
    val batchTarget: Int = 0
)

/**
 * Simple, dependency-free persistence using SharedPreferences + JSON.
 * Deliberately not using Room/SQLite to keep the build minimal and reliable
 * for this scope. Enough to survive rotation, backgrounding, and process death.
 */
class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences("autodialer_session", Context.MODE_PRIVATE)

    fun save(session: SavedSession) {
        val leadsArray = JSONArray()
        session.leads.forEach { lead ->
            val obj = JSONObject()
            obj.put("name", lead.name ?: "")
            obj.put("phone", lead.phone)
            obj.put("status", lead.status.name)
            obj.put("outcome", lead.outcome?.name ?: "")
            leadsArray.put(obj)
        }
        prefs.edit()
            .putString("sessionName", session.sessionName)
            .putString("leads", leadsArray.toString())
            .putInt("currentIndex", session.currentIndex)
            .putInt("delaySeconds", session.delaySeconds)
            .putInt("batchTarget", session.batchTarget)
            .putBoolean("hasSession", true)
            .apply()
    }

    fun load(): SavedSession? {
        if (!prefs.getBoolean("hasSession", false)) return null
        val leadsJson = prefs.getString("leads", null) ?: return null
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
            val outcome = OutcomeTag.fromNameOrNull(obj.optString("outcome", "").ifEmpty { null })
            leads.add(Lead(name, phone, status, outcome))
        }
        return SavedSession(
            sessionName = prefs.getString("sessionName", "") ?: "",
            leads = leads,
            currentIndex = prefs.getInt("currentIndex", -1),
            delaySeconds = prefs.getInt("delaySeconds", 2),
            batchTarget = prefs.getInt("batchTarget", 0)
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun hasSavedSession(): Boolean = prefs.getBoolean("hasSession", false)
}
