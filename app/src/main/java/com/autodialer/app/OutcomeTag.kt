package com.autodialer.app

import android.graphics.Color

enum class OutcomeTag(val label: String, val shortTag: String, val colorHex: String) {
    RESUME("Resume", "RES", "#2196F3"),
    NO("No", "NO", "#E53935"),
    POSITIVE("Positive", "POS", "#43A047"),
    INFO("Info", "INFO", "#FB8C00");

    fun color(): Int = Color.parseColor(colorHex)

    companion object {
        fun fromNameOrNull(name: String?): OutcomeTag? =
            values().firstOrNull { it.name == name }
    }
}
