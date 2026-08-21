package com.autodialer.app

import android.content.Context

/**
 * Holds whatever is currently sitting in the "paste numbers" box but hasn't been
 * loaded into a list yet. Kept in SharedPreferences (not just the EditText) because:
 *  - Picking images/files launches another app, and Android can kill this app's
 *    process in the background while that's open (common on low-RAM phones,
 *    especially when several images are being OCR'd). When the user comes back,
 *    the Activity gets recreated - this is the safety net that restores exactly
 *    what was there before, instead of it looking "missing".
 *  - OCR runs in the background and can finish AFTER the Activity has already been
 *    recreated. Writing straight here (instead of only to the on-screen box) means
 *    the result is never lost even if that exact race happens.
 */
class NumberDraftStore(context: Context) {

    private val prefs = context.getSharedPreferences("autodialer_number_draft", Context.MODE_PRIVATE)

    fun getDraft(): String = prefs.getString("draft", "") ?: ""

    fun setDraft(text: String) {
        prefs.edit().putString("draft", text).apply()
    }

    fun clear() {
        prefs.edit().remove("draft").apply()
    }
}
