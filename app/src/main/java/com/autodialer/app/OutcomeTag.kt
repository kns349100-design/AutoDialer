package com.autodialer.app

import android.graphics.Color

enum class OutcomeTag(val label: String, val shortTag: String, val colorHex: String, val textColorHex: String) {
    RESUME("Resume", "Resume", "#2E7CF6", "#FFFFFF"),
    NO("No", "No", "#FF3B5C", "#FFFFFF"),
    POSITIVE("Positive", "Positive", "#2ED47A", "#06341A"),
    INFO("Info", "Info", "#FFB020", "#412402");

    fun color(): Int = Color.parseColor(colorHex)
    fun textColor(): Int = Color.parseColor(textColorHex)

    companion object {
        fun fromNameOrNull(name: String?): OutcomeTag? =
            values().firstOrNull { it.name == name }
    }
}
