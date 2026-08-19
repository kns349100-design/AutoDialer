package com.autodialer.app

import android.content.Context

/** Stores the editable WhatsApp message template used when the INFO outcome button is tapped. */
class InfoMessageStore(context: Context) {

    private val prefs = context.getSharedPreferences("autodialer_info_message", Context.MODE_PRIVATE)

    fun getMessage(): String =
        prefs.getString("message", DEFAULT_MESSAGE) ?: DEFAULT_MESSAGE

    fun setMessage(message: String) {
        prefs.edit().putString("message", message).apply()
    }

    companion object {
        const val DEFAULT_MESSAGE =
            "Hi, thank you for the info. Please share your resume here on WhatsApp."
    }
}
