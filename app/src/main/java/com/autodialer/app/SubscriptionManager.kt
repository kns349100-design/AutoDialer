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
 * Licensing with server-side verification via a free Google Apps Script backend
 * (see /backend/SETUP.md). Codes live in a Google Sheet, NOT inside the APK, so
 * they can be added/revoked anytime without rebuilding the app, and one device's
 * access can be individually cut off.
 *
 * The device caches its last known status locally so the app keeps working
 * without internet - it just won't see revocations until it can reach the
 * server again.
 *
 * IMPORTANT: paste your deployed Apps Script Web App URL below (see SETUP.md
 * step 5-6). Nothing works until this is set.
 */
class SubscriptionManager(private val context: Context) {

    companion object {
        // TODO: replace with your deployed Apps Script Web App URL
        const val SCRIPT_URL = "PASTE_YOUR_APPS_SCRIPT_URL_HERE"

        // TODO: replace with your real Razorpay Key ID (safe to embed - it's the PUBLIC key)
        const val RAZORPAY_KEY_ID = "PASTE_YOUR_RAZORPAY_KEY_ID_HERE"

        const val TRIAL_DURATION_MS = 24L * 60 * 60 * 1000 // 1 day

        const val PRICE_HOURLY12_PAISE = 1000   // Rs 10
        const val PRICE_MONTHLY_PAISE = 30000   // Rs 300
        const val PRICE_YEARLY_PAISE = 100000   // Rs 1000

        const val PRICE_MONTHLY = "Rs 300 / month"
        const val PRICE_YEARLY = "Rs 1000 / year"
        const val PRICE_HOURLY12 = "Rs 10 / 12 hours"
    }

    private val prefs = context.getSharedPreferences("autodialer_license", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun deviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    fun ensureFirstLaunchRecorded() {
        if (!prefs.contains("firstLaunch")) {
            prefs.edit().putLong("firstLaunch", System.currentTimeMillis()).apply()
        }
    }

    private fun firstLaunchTime(): Long = prefs.getLong("firstLaunch", System.currentTimeMillis())

    fun isTrialActive(): Boolean {
        val elapsed = System.currentTimeMillis() - firstLaunchTime()
        return elapsed in 0 until TRIAL_DURATION_MS
    }

    fun trialMillisRemaining(): Long =
        (TRIAL_DURATION_MS - (System.currentTimeMillis() - firstLaunchTime())).coerceAtLeast(0)

    private fun cachedExpiry(): Long = prefs.getLong("cachedExpiry", 0L)
    private fun cachedPlanType(): String = prefs.getString("cachedPlanType", "") ?: ""

    fun isSubscribed(): Boolean = System.currentTimeMillis() < cachedExpiry()

    fun hasAccess(): Boolean = isSubscribed() || isTrialActive()

    fun currentPlanLabel(): String = when {
        isSubscribed() -> "Active plan: ${cachedPlanType()}"
        isTrialActive() -> "Free trial"
        else -> "No active plan"
    }

    /** Call this on app start / resume when internet may be available. Silently no-ops if offline or URL not configured. */
    fun refreshStatusInBackground() {
        if (SCRIPT_URL.startsWith("PASTE_")) return
        Thread {
            try {
                val url = "$SCRIPT_URL?action=check&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}"
                val response = httpGet(url)
                val json = JSONObject(response)
                if (json.optString("status") == "ok") {
                    val expiryAt = json.optLong("expiryAt", 0L)
                    val planType = json.optString("planType", "")
                    prefs.edit()
                        .putLong("cachedExpiry", expiryAt)
                        .putString("cachedPlanType", planType)
                        .apply()
                }
            } catch (e: Exception) {
                // Offline or backend not reachable - keep using cached/local status.
            }
        }.start()
    }

    /** Redeems a coupon code against the server. Calls back on the main thread with a result message. */
    fun redeemCode(rawCode: String, onResult: (success: Boolean, message: String) -> Unit) {
        if (SCRIPT_URL.startsWith("PASTE_")) {
            onResult(false, "Backend URL is not set - follow backend/SETUP.md")
            return
        }
        val code = rawCode.trim()
        if (code.isEmpty()) {
            onResult(false, "Enter a code")
            return
        }
        Thread {
            try {
                val url = "$SCRIPT_URL?action=redeem" +
                    "&code=${URLEncoder.encode(code, "UTF-8")}" +
                    "&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}"
                val response = httpGet(url)
                val json = JSONObject(response)
                if (json.optString("status") == "ok") {
                    val expiryAt = json.optLong("expiryAt", 0L)
                    val planType = json.optString("planType", "")
                    prefs.edit()
                        .putLong("cachedExpiry", expiryAt)
                        .putString("cachedPlanType", planType)
                        .apply()
                    mainHandler.post { onResult(true, "Activated! Plan: $planType") }
                } else {
                    val message = json.optString("message", "Invalid code")
                    mainHandler.post { onResult(false, message) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, "Check your internet and try again") }
            }
        }.start()
    }

    /**
     * Verifies a completed Razorpay payment with the backend (which checks the payment
     * directly with Razorpay's servers) and activates the plan automatically - no code needed.
     */
    fun verifyPayment(paymentId: String, planType: String, onResult: (success: Boolean, message: String) -> Unit) {
        if (SCRIPT_URL.startsWith("PASTE_")) {
            onResult(false, "Backend URL is not set - follow backend/SETUP.md")
            return
        }
        Thread {
            try {
                val url = "$SCRIPT_URL?action=verifyPayment" +
                    "&paymentId=${URLEncoder.encode(paymentId, "UTF-8")}" +
                    "&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}" +
                    "&planType=${URLEncoder.encode(planType, "UTF-8")}"
                val response = httpGet(url)
                val json = JSONObject(response)
                if (json.optString("status") == "ok") {
                    val expiryAt = json.optLong("expiryAt", 0L)
                    val type = json.optString("planType", "")
                    prefs.edit()
                        .putLong("cachedExpiry", expiryAt)
                        .putString("cachedPlanType", type)
                        .apply()
                    mainHandler.post { onResult(true, "Payment confirmed! Plan is active.") }
                } else {
                    val message = json.optString("message", "Payment could not be verified")
                    mainHandler.post { onResult(false, message) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, "Payment went through but verification had an issue - reopen the Plan screen, it will check automatically") }
            }
        }.start()
    }

    private fun httpGet(urlString: String): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = true
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
