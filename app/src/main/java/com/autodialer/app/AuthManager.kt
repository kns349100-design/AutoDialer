package com.autodialer.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free phone number + PIN login (no SMS, no billing) backed by the same Google
 * Apps Script sheet used for subscriptions. Rules:
 *  - A phone number's PIN is set the first time it ever logs in.
 *  - After that, the same PIN is required to log in again.
 *  - "Forgot PIN" lets the number set a brand new PIN without needing the old one.
 *  - Logging in successfully makes that device the ONLY active device for the
 *    number - any other device that was logged in gets signed out (detected via
 *    checkSessionInBackground, called periodically while the app is open).
 */
class AuthManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("autodialer_auth", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun deviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    fun isLoggedIn(): Boolean = prefs.getBoolean("loggedIn", false)
    fun phoneNumber(): String = prefs.getString("phone", "") ?: ""

    fun setLoggedIn(phone: String) {
        prefs.edit().putBoolean("loggedIn", true).putString("phone", phone).apply()
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    /**
     * Attempts to log in with phone+PIN. If this phone number has never logged in
     * before, this PIN becomes its PIN (registered = true in the callback).
     */
    fun login(phone: String, pin: String, onResult: (success: Boolean, registered: Boolean, message: String) -> Unit) {
        if (SubscriptionManager.SCRIPT_URL.startsWith("PASTE_")) {
            onResult(false, false, "Backend not set up yet - follow backend/SETUP.md")
            return
        }
        Thread {
            try {
                val url = "${SubscriptionManager.SCRIPT_URL}?action=login" +
                    "&phone=${URLEncoder.encode(phone, "UTF-8")}" +
                    "&pin=${URLEncoder.encode(pin, "UTF-8")}" +
                    "&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}"
                val response = httpGet(url)
                val json = JSONObject(response)
                if (json.optString("status") == "ok") {
                    val registered = json.optBoolean("registered", false)
                    mainHandler.post {
                        onResult(true, registered, if (registered) "PIN set! You're logged in." else "Logged in.")
                    }
                } else {
                    mainHandler.post { onResult(false, false, json.optString("message", "Login failed")) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, false, "Check your internet and try again") }
            }
        }.start()
    }

    /** Sets a brand new PIN for a phone number without requiring the old one. */
    fun resetPin(phone: String, newPin: String, onResult: (success: Boolean, message: String) -> Unit) {
        if (SubscriptionManager.SCRIPT_URL.startsWith("PASTE_")) {
            onResult(false, "Backend not set up yet - follow backend/SETUP.md")
            return
        }
        Thread {
            try {
                val url = "${SubscriptionManager.SCRIPT_URL}?action=resetPin" +
                    "&phone=${URLEncoder.encode(phone, "UTF-8")}" +
                    "&newPin=${URLEncoder.encode(newPin, "UTF-8")}" +
                    "&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}"
                val response = httpGet(url)
                val json = JSONObject(response)
                if (json.optString("status") == "ok") {
                    mainHandler.post { onResult(true, "PIN reset! You can log in with the new PIN now.") }
                } else {
                    mainHandler.post { onResult(false, json.optString("message", "Reset failed")) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, "Check your internet and try again") }
            }
        }.start()
    }

    /** Checks whether this device is still the active session for the logged-in phone number.
     * Throttled to at most once every 3 minutes - this hits the (slow, free) backend, so
     * calling it on every single onResume made the app feel sluggish overall for no benefit. */
    fun checkSessionInBackground(onLoggedOutElsewhere: () -> Unit) {
        if (SubscriptionManager.SCRIPT_URL.startsWith("PASTE_")) return
        val phone = phoneNumber()
        if (phone.isEmpty()) return
        val now = System.currentTimeMillis()
        val lastChecked = prefs.getLong("lastSessionCheck", 0L)
        if (now - lastChecked < 3 * 60 * 1000) return
        prefs.edit().putLong("lastSessionCheck", now).apply()
        Thread {
            try {
                val url = "${SubscriptionManager.SCRIPT_URL}?action=checkSession" +
                    "&phone=${URLEncoder.encode(phone, "UTF-8")}" +
                    "&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}"
                val response = httpGet(url)
                val json = JSONObject(response)
                if (json.optString("status") == "ok" && !json.optBoolean("active", true)) {
                    mainHandler.post { onLoggedOutElsewhere() }
                }
            } catch (e: Exception) {
                // Offline - don't force a logout, just skip this check.
            }
        }.start()
    }

    private fun httpGet(urlString: String): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        // Apps Script "cold starts" after being idle can genuinely take several seconds - this
        // just needs to be long enough not to cut off a real (if slow) response.
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return stream?.bufferedReader()?.use { it.readText() } ?: "{\"status\":\"error\",\"message\":\"Empty response (HTTP $code)\"}"
        } finally {
            conn.disconnect()
        }
    }
}
