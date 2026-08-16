package com.autodialer.app

import android.content.Context

class AuthStore(context: Context) {
    private val prefs = context.getSharedPreferences("autodialer_auth", Context.MODE_PRIVATE)

    fun isLoggedIn(): Boolean = prefs.getBoolean("loggedIn", false)

    fun phoneNumber(): String = prefs.getString("phone", "") ?: ""

    fun setLoggedIn(phone: String) {
        prefs.edit().putBoolean("loggedIn", true).putString("phone", phone).apply()
    }

    fun logout() {
        prefs.edit().clear().apply()
    }
}
