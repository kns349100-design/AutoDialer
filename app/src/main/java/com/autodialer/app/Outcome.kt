package com.autodialer.app

import android.graphics.Color

data class Outcome(
    val id: String,
    val label: String,
    val colorHex: String,
    val textColorHex: String,
    val isCustom: Boolean = false
) {
    fun color(): Int = Color.parseColor(colorHex)
    fun textColor(): Int = Color.parseColor(textColorHex)

    companion object {
        val RESUME = Outcome("RESUME", "Resume", "#2E7CF6", "#FFFFFF")
        val NO = Outcome("NO", "No", "#FF3B5C", "#FFFFFF")
        val POSITIVE = Outcome("POSITIVE", "Positive", "#2ED47A", "#06341A")
        val INFO = Outcome("INFO", "Info", "#FFB020", "#412402")

        val DEFAULTS = listOf(RESUME, NO, POSITIVE, INFO)

        // Colors auto-assigned to new custom options, cycling through this palette.
        val CUSTOM_PALETTE = listOf(
            "#9C27B0" to "#FFFFFF", // purple
            "#00BCD4" to "#04333A", // cyan
            "#E91E63" to "#FFFFFF", // pink
            "#8BC34A" to "#1B2E0A", // lime
            "#3F51B5" to "#FFFFFF", // indigo
            "#FF5722" to "#FFFFFF", // deep orange
            "#009688" to "#FFFFFF", // teal
            "#CDDC39" to "#33350A", // yellow-lime
            "#607D8B" to "#FFFFFF", // blue grey
            "#795548" to "#FFFFFF"  // brown
        )
    }
}
