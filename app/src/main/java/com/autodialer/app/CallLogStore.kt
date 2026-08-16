package com.autodialer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CallLogEntry(
    val time: String,
    val name: String?,
    val phone: String,
    val status: String,
    val outcome: String?,
    val note: String? = null
)

/**
 * Stores call history grouped by day ("sheets"), like an Excel workbook with one
 * sheet per day. Supports deleting a single row or an entire day's sheet.
 */
class CallLogStore(context: Context) {

    private val prefs = context.getSharedPreferences("autodialer_call_log", Context.MODE_PRIVATE)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun todayKey(): String = dayFormat.format(Date())
    fun nowTime(): String = timeFormat.format(Date())

    fun addEntry(date: String, entry: CallLogEntry) {
        val day = loadDay(date).toMutableList()
        day.add(entry)
        saveDay(date, day)
        val days = getDays().toMutableSet()
        days.add(date)
        prefs.edit().putStringSet("days", days).apply()
    }

    /** Days with at least one logged call, most recent first. */
    fun getDays(): List<String> {
        val set = prefs.getStringSet("days", emptySet()) ?: emptySet()
        return set.sortedDescending()
    }

    fun loadDay(date: String): List<CallLogEntry> {
        val json = prefs.getString("day_$date", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<CallLogEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    CallLogEntry(
                        time = obj.optString("time", ""),
                        name = obj.optString("name", "").ifEmpty { null },
                        phone = obj.optString("phone", ""),
                        status = obj.optString("status", ""),
                        outcome = obj.optString("outcome", "").ifEmpty { null }
                    )
                )
            }
            list
        } catch (e: Exception) {
            // Corrupted data for this day should never crash the app - just treat as empty.
            emptyList()
        }
    }

    /** All phone numbers ever logged across every day's sheet, for duplicate-call warnings. */
    fun allCalledPhones(): Set<String> {
        return getDays().flatMap { loadDay(it) }.map { it.phone }.toSet()
    }

    private fun saveDay(date: String, entries: List<CallLogEntry>) {
        val array = JSONArray()
        entries.forEach { e ->
            val obj = JSONObject()
            obj.put("time", e.time)
            obj.put("name", e.name ?: "")
            obj.put("phone", e.phone)
            obj.put("status", e.status)
            obj.put("outcome", e.outcome ?: "")
            array.put(obj)
        }
        prefs.edit().putString("day_$date", array.toString()).apply()
    }

    /** Deletes a single row from a day's sheet. */
    fun deleteEntry(date: String, index: Int) {
        val day = loadDay(date).toMutableList()
        if (index in day.indices) {
            day.removeAt(index)
            saveDay(date, day)
        }
    }

    /** Deletes an entire day's sheet. */
    fun deleteDay(date: String) {
        prefs.edit().remove("day_$date").apply()
        val days = getDays().toMutableSet()
        days.remove(date)
        prefs.edit().putStringSet("days", days).apply()
    }
}
