package com.autodialer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
 *
 * Speed: every day's rows are kept in memory (shared by every screen in the app) after the first
 * read, together with a ready-made set of the numbers already called. So "has this number been
 * called?" is instant, and loading a new list no longer re-reads and re-parses every past day's
 * file. Changes are written to disk on a background thread (several quick changes become one
 * write), so tapping an outcome or dialing the next number never waits on file I/O.
 */
class CallLogStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("autodialer_call_log", Context.MODE_PRIVATE)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private class DayData(val entries: MutableList<CallLogEntry>) {
        val phones = HashSet<String>()

        init {
            rebuildPhones()
        }

        fun rebuildPhones() {
            phones.clear()
            for (e in entries) phones.add(PhoneUtils.normalize(e.phone))
        }
    }

    init {
        appCtx = appContext
    }

    companion object {
        private val lock = Any()
        private val cache = HashMap<String, DayData>()
        private var allPhones: HashSet<String>? = null
        private val dirty = LinkedHashSet<String>()
        private var writeScheduled = false

        @Volatile
        private var appCtx: Context? = null

        private val writer = Executors.newSingleThreadExecutor { r ->
            Thread(r, "calllog-writer").also { it.isDaemon = true }
        }
    }

    private fun dayFileName(date: String) = "calllog_$date.json"

    fun todayKey(): String = dayFormat.format(Date())
    fun nowTime(): String = timeFormat.format(Date())

    /** Must be called with [lock] held. */
    private fun dayData(date: String): DayData {
        val existing = cache[date]
        if (existing != null) return existing
        val data = DayData(readDayFromDisk(date).toMutableList())
        cache[date] = data
        return data
    }

    /** Must be called with [lock] held. Queues one background write that covers every change
     * made until it actually runs. */
    private fun markDirty(date: String) {
        dirty.add(date)
        if (!writeScheduled) {
            writeScheduled = true
            writer.execute { writePending() }
        }
    }

    private fun writePending() {
        while (true) {
            val job: Pair<String, List<CallLogEntry>>? = synchronized(lock) {
                var found: Pair<String, List<CallLogEntry>>? = null
                while (found == null && dirty.isNotEmpty()) {
                    val date = dirty.first()
                    dirty.remove(date)
                    val data = cache[date]
                    if (data != null) found = Pair(date, ArrayList(data.entries))
                }
                if (found == null) writeScheduled = false
                found
            }
            if (job == null) return
            val ctx = appCtx ?: return
            try {
                FileStore.writeString(ctx, dayFileName(job.first), serialize(job.second))
            } catch (e: Exception) {
                // Best-effort - never crash the app because a background save failed.
            }
        }
    }

    /** Waits (briefly) until every change made so far is on disk. Cheap when nothing is pending. */
    fun flush() {
        val idle = synchronized(lock) { dirty.isEmpty() && !writeScheduled }
        if (idle) return
        try {
            writer.submit(Runnable { }).get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Ignore - the background write will still finish on its own.
        }
    }

    /** Adds an entry and returns the index it was stored at, so it can be updated later. */
    fun addEntry(date: String, entry: CallLogEntry): Int {
        val normalized = PhoneUtils.normalize(entry.phone)
        val index = synchronized(lock) {
            val data = dayData(date)
            data.entries.add(entry)
            data.phones.add(normalized)
            allPhones?.add(normalized)
            markDirty(date)
            data.entries.size - 1
        }
        val days = prefs.getStringSet("days", emptySet()) ?: emptySet()
        if (!days.contains(date)) {
            val updated = HashSet(days)
            updated.add(date)
            prefs.edit().putStringSet("days", updated).apply()
        }
        return index
    }

    /** Updates the status/outcome of an already-logged entry (used to fill in the outcome once tagged, without adding a duplicate row). */
    fun updateEntry(date: String, index: Int, status: String, outcome: String?) {
        synchronized(lock) {
            val data = dayData(date)
            if (index in data.entries.indices) {
                val old = data.entries[index]
                data.entries[index] = old.copy(status = status, outcome = outcome ?: old.outcome)
                markDirty(date)
            }
        }
    }

    /** Days with at least one logged call, most recent first. */
    fun getDays(): List<String> {
        val set = prefs.getStringSet("days", emptySet()) ?: emptySet()
        return set.sortedDescending()
    }

    fun loadDay(date: String): List<CallLogEntry> {
        return synchronized(lock) { ArrayList(dayData(date).entries) }
    }

    private fun readDayFromDisk(date: String): List<CallLogEntry> {
        // A day with a large list of calls can hold thousands of rows - kept in its own file
        // rather than SharedPreferences (which rewrites its entire file on every change).
        var json = FileStore.readString(appContext, dayFileName(date))
        if (json == null) {
            // Fall back to the old SharedPreferences-based storage (from before this day's
            // data was moved to a file) so nothing already logged gets silently lost.
            val legacy = prefs.getString("day_$date", null)
            if (legacy != null) {
                FileStore.writeString(appContext, dayFileName(date), legacy)
                prefs.edit().remove("day_$date").apply()
                json = legacy
            }
        }
        if (json == null) return emptyList()
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
            // Corrupted data for this day should never crash the app - treat as empty, but keep
            // a copy of the unreadable file so nothing is destroyed for good.
            FileStore.writeString(appContext, dayFileName(date) + ".corrupt", json)
            emptyList()
        }
    }

    /** All phone numbers ever logged across every day's sheet, for duplicate-call warnings. */
    fun allCalledPhones(): Set<String> {
        return synchronized(lock) {
            val existing = allPhones
            if (existing != null) {
                HashSet(existing)
            } else {
                val built = HashSet<String>()
                for (day in getDays()) built.addAll(dayData(day).phones)
                allPhones = built
                HashSet(built)
            }
        }
    }

    /** Phone numbers logged within the last [days] days (default 60 ~ 2 months), for auto-excluding recent calls when loading a new list. Normalized so the same number in a different format (with/without +91, leading 0, etc) is still caught. */
    fun calledPhonesWithinDays(days: Int): Set<String> {
        val cutoff = Date(Date().time - days.toLong() * 24 * 60 * 60 * 1000)
        val cutoffKey = dayFormat.format(cutoff)
        val result = HashSet<String>()
        synchronized(lock) {
            for (day in getDays()) {
                if (day >= cutoffKey) result.addAll(dayData(day).phones)
            }
        }
        return result
    }

    /** True if this exact number has already been dialed today (any status - Dialed/Completed/Skipped all count, since a call was already placed to it). This is the last-line-of-defense check right before actually placing a call. */
    fun calledToday(normalizedPhone: String): Boolean {
        val today = todayKey()
        return synchronized(lock) { dayData(today).phones.contains(normalizedPhone) }
    }

    private fun serialize(entries: List<CallLogEntry>): String {
        val array = JSONArray()
        for (e in entries) {
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
        return array.toString()
    }

    /** Toggles the manual "Collected" mark on one row (e.g. after checking WhatsApp yourself). */
    fun toggleCollected(date: String, index: Int) {
        synchronized(lock) {
            val data = dayData(date)
            if (index in data.entries.indices) {
                val old = data.entries[index]
                data.entries[index] = old.copy(collected = !old.collected)
                markDirty(date)
            }
        }
    }

    /** Deletes a single row from a day's sheet. */
    fun deleteEntry(date: String, index: Int) {
        synchronized(lock) {
            val data = dayData(date)
            if (index in data.entries.indices) {
                data.entries.removeAt(index)
                data.rebuildPhones()
                allPhones = null
                markDirty(date)
            }
        }
    }

    /** Deletes an entire day's sheet. */
    fun deleteDay(date: String) {
        synchronized(lock) {
            cache.remove(date)
            dirty.remove(date)
            allPhones = null
            FileStore.delete(appContext, dayFileName(date))
        }
        val days = getDays().toMutableSet()
        days.remove(date)
        prefs.edit().putStringSet("days", days).apply()
    }
}
