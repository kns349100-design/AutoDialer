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
    val note: String? = null,
    val collected: Boolean = false
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

    /** Adds an entry and returns the index it was stored at, so it can be updated later. */
    fun addEntry(date: String, entry: CallLogEntry): Int {
        val day = loadDay(date).toMutableList()
        day.add(entry)
        saveDay(date, day)
        val days = getDays().toMutableSet()
        days.add(date)
        prefs.edit().putStringSet("days", days).apply()
        return day.size - 1
    }

    /** Updates the status/outcome of an already-logged entry (used to fill in the outcome once tagged, without adding a duplicate row). */
    fun updateEntry(date: String, index: Int, status: String, outcome: String?) {
        val day = loadDay(date).toMutableList()
        if (index in day.indices) {
            day[index] = day[index].copy(status = status, outcome = outcome ?: day[index].outcome)
            saveDay(date, day)
        }
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
                        outcome = obj.optString("outcome", "").ifEmpty { null },
                        note = obj.optString("note", "").ifEmpty { null },
                        collected = obj.optBoolean("collected", false)
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
        return getDays().flatMap { loadDay(it) }.map { PhoneUtils.normalize(it.phone) }.toSet()
    }

    /** Phone numbers logged within the last [days] days (default 60 ~ 2 months), for auto-excluding recent calls when loading a new list. Normalized so the same number in a different format (with/without +91, leading 0, etc) is still caught. */
    fun calledPhonesWithinDays(days: Int): Set<String> {
        val cutoff = Date(Date().time - days.toLong() * 24 * 60 * 60 * 1000)
        val cutoffKey = dayFormat.format(cutoff)
        return getDays()
            .filter { it >= cutoffKey }
            .flatMap { loadDay(it) }
            .map { PhoneUtils.normalize(it.phone) }
            .toSet()
    }

    /** True if this exact number has already been dialed today (any status - Dialed/Completed/Skipped all count, since a call was already placed to it). This is the last-line-of-defense check right before actually placing a call. */
    fun calledToday(normalizedPhone: String): Boolean {
        return loadDay(todayKey()).any { PhoneUtils.normalize(it.phone) == normalizedPhone }
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
            obj.put("note", e.note ?: "")
            obj.put("collected", e.collected)
            array.put(obj)
        }
        prefs.edit().putString("day_$date", array.toString()).apply()
    }

    /** Toggles the manual "Collected" mark on one row (e.g. after checking WhatsApp yourself). */
    fun toggleCollected(date: String, index: Int) {
        val day = loadDay(date).toMutableList()
        if (index in day.indices) {
            day[index] = day[index].copy(collected = !day[index].collected)
            saveDay(date, day)
        }
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
