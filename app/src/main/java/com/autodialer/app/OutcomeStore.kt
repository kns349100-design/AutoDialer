package com.autodialer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class OutcomeStore(context: Context) {

    private val prefs = context.getSharedPreferences("autodialer_outcomes", Context.MODE_PRIVATE)

    fun allOutcomes(): List<Outcome> = Outcome.DEFAULTS + customOutcomes()

    fun customOutcomes(): List<Outcome> {
        val json = prefs.getString("custom", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Outcome(
                    id = obj.getString("id"),
                    label = obj.getString("label"),
                    colorHex = obj.getString("colorHex"),
                    textColorHex = obj.getString("textColorHex"),
                    isCustom = true
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun findById(id: String?): Outcome? {
        if (id == null) return null
        return allOutcomes().firstOrNull { it.id == id }
    }

    /** Adds a new custom outcome with the given label. Color is picked automatically. */
    fun addCustom(rawLabel: String): Outcome? {
        val label = rawLabel.trim()
        if (label.isEmpty()) return null

        val existing = allOutcomes()
        var id = label.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')
        if (id.isEmpty()) id = "OPTION"
        var suffix = 1
        var uniqueId = id
        while (existing.any { it.id == uniqueId }) {
            suffix += 1
            uniqueId = "${id}_$suffix"
        }

        val paletteIndex = customOutcomes().size % Outcome.CUSTOM_PALETTE.size
        val (colorHex, textColorHex) = Outcome.CUSTOM_PALETTE[paletteIndex]

        val newOutcome = Outcome(uniqueId, label, colorHex, textColorHex, isCustom = true)
        val updated = customOutcomes() + newOutcome
        saveCustom(updated)
        return newOutcome
    }

    fun deleteCustom(id: String) {
        val updated = customOutcomes().filter { it.id != id }
        saveCustom(updated)
    }

    private fun saveCustom(list: List<Outcome>) {
        val array = JSONArray()
        list.forEach { o ->
            val obj = JSONObject()
            obj.put("id", o.id)
            obj.put("label", o.label)
            obj.put("colorHex", o.colorHex)
            obj.put("textColorHex", o.textColorHex)
            array.put(obj)
        }
        prefs.edit().putString("custom", array.toString()).apply()
    }
}
