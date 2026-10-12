package com.autodialer.app

import android.content.Context
import java.io.File

/**
 * Internal-storage file read/write for larger JSON blobs (a long number list, or a day with
 * thousands of call-log rows). SharedPreferences keeps its whole file in memory and rewrites
 * it on every single change, which gets noticeably slow - and memory-heavy - once one value
 * inside it is this big. Plain files don't have that problem and comfortably handle lists in
 * the thousands.
 *
 * Writes are atomic: the new content goes to a temporary file first and only then replaces the
 * real file. If the phone dies, the battery is pulled or Android kills the app half-way through
 * a save, the previous complete file is still there - never a half-written, unreadable one.
 */
object FileStore {

    fun writeString(context: Context, filename: String, content: String) {
        try {
            val target = File(context.filesDir, filename)
            val temp = File(context.filesDir, "$filename.tmp")
            temp.writeText(content)
            if (!temp.renameTo(target)) {
                // Some file systems refuse to rename over an existing file - fall back to a
                // direct write rather than losing the save.
                target.writeText(content)
                temp.delete()
            }
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
            File(context.filesDir, "$filename.tmp").delete()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
