package com.autodialer.app

import android.content.Context
import java.io.File

/**
 * Internal-storage file read/write for larger JSON blobs (a long number list, or a day with
 * thousands of call-log rows). SharedPreferences keeps its whole file in memory and rewrites
 * it on every single change, which gets noticeably slow - and memory-heavy - once one value
 * inside it is this big. Plain files don't have that problem and comfortably handle lists in
 * the thousands.
 */
object FileStore {

    fun writeString(context: Context, filename: String, content: String) {
        try {
            File(context.filesDir, filename).writeText(content)
        } catch (e: Exception) {
            // Best-effort - a failed save should never crash the app.
        }
    }

    fun readString(context: Context, filename: String): String? {
        return try {
            val file = File(context.filesDir, filename)
            if (!file.exists()) null else file.readText()
        } catch (e: Exception) {
            null
        }
    }

    fun delete(context: Context, filename: String) {
        try {
            File(context.filesDir, filename).delete()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
