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
        const val SCRIPT_URL = "https://script.google.com/macros/s/AKfycbyHEnFWqibZeO774YRKcdlyWb_EgOWyFAi8gmiFDkbajNZQo5TiIL18yOdLp3g1KY9v/exec"

        const val TRIAL_DURATION_MS = 24L * 60 * 60 * 1000 // 1 day

        /** UPI ID payments go to directly (no gateway, no whitelisting delay - money lands
         * straight in this account). Used for the "Pay via UPI" flow: opens the user's UPI
         * app with the amount pre-filled, they pay, then send a screenshot on WhatsApp and
         * get a redeem code activated manually. */
        const val UPI_VPA = "kamleshshinde4748@okaxis"
        const val UPI_PAYEE_NAME = "Kamlesh Nana Shinde"

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

    /** True once the user has explicitly tapped "Start Free — 24 Hours" (whether or not it has
     * expired since). Used to hide that option from the plan list after it's been used once -
     * it's a one-time offer, not something that keeps reappearing. */
    fun hasStartedFreeTrial(): Boolean = prefs.contains("trialStartedAt")

    /** Starts the 24-hour free trial right now. No-ops if already started before (never
     * extends/restarts it). */
    fun startFreeTrial() {
        if (!prefs.contains("trialStartedAt")) {
            prefs.edit().putLong("trialStartedAt", System.currentTimeMillis()).apply()
        }
    }

    private fun trialStartedAt(): Long = prefs.getLong("trialStartedAt", 0L)

    fun isTrialActive(): Boolean {
        val startedAt = trialStartedAt()
        if (startedAt == 0L) return false
        val elapsed = System.currentTimeMillis() - startedAt
        return elapsed in 0 until TRIAL_DURATION_MS
    }

    fun trialMillisRemaining(): Long =
        (TRIAL_DURATION_MS - (System.currentTimeMillis() - trialStartedAt())).coerceAtLeast(0)

    private fun cachedExpiry(): Long = prefs.getLong("cachedExpiry", 0L)
    private fun cachedPlanType(): String = prefs.getString("cachedPlanType", "") ?: ""

    fun isSubscribed(): Boolean = System.currentTimeMillis() < cachedExpiry()

    fun hasAccess(): Boolean = isSubscribed() || isTrialActive()

    fun currentPlanLabel(): String = when {
        isSubscribed() -> "Active plan: ${cachedPlanType()}"
        isTrialActive() -> "Free trial"
        else -> "No active plan"
    }

    /** How much time is left on whichever is currently active (paid plan or free trial), in
     * plain words - e.g. "12 days 4h left", "23h 15m left", "45m left". Empty string if
     * nothing is active. */
    fun remainingTimeLabel(): String {
        val millisLeft = when {
            isSubscribed() -> (cachedExpiry() - System.currentTimeMillis()).coerceAtLeast(0)
            isTrialActive() -> trialMillisRemaining()
            else -> return ""
        }
        val totalMinutes = millisLeft / 60000
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "$days day${if (days > 1L) "s" else ""} ${hours}h left"
            hours > 0 -> "${hours}h ${minutes}m left"
            else -> "${minutes}m left"
        }
    }

    /** One-line summary combining plan name + remaining time, used on the Settings screen's
     * "Plan / Subscription" row so it's visible without even opening that screen. */
    fun planSummaryForSettings(): String = when {
        isSubscribed() -> "${cachedPlanType()} — ${remainingTimeLabel()}"
        isTrialActive() -> "Free trial — ${remainingTimeLabel()}"
        else -> "No active plan - tap to choose one"
    }

    /** Call this on app start / resume when internet may be available. Silently no-ops if offline, URL not configured, or checked very recently (throttled to reduce background network load / perceived slowness). */
    fun refreshStatusInBackground() {
        if (SCRIPT_URL.startsWith("PASTE_")) return
        val now = System.currentTimeMillis()
        val lastChecked = prefs.getLong("lastStatusCheck", 0L)
        if (now - lastChecked < 3 * 60 * 1000) return
        prefs.edit().putLong("lastStatusCheck", now).apply()
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
    /** Grants the plan immediately on this device without any server round-trip - used right
     * after an incoming bank/UPI SMS confirms the payment actually landed (see
     * SmsPaymentVerifier), so access unlocks the instant the SMS arrives with zero delay. */
    fun grantPlanLocally(planType: String) {
        val now = System.currentTimeMillis()
        val expiryAt = when (planType) {
            "YEARLY" -> now + (365L * 24 * 60 * 60 * 1000)
            "HOURLY12" -> now + (12L * 60 * 60 * 1000)
            else -> now + (30L * 24 * 60 * 60 * 1000) // MONTHLY
        }
        prefs.edit()
            .putLong("cachedExpiry", expiryAt)
            .putString("cachedPlanType", planType)
            .apply()
    }

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
     * Asks the backend to create a Cashfree Payment Link for the chosen plan (backend holds
     * the Cashfree App ID + Secret Key, never the app). Returns a link URL to open in the
     * browser/UPI app, plus the linkId needed later to check payment status.
     */
    fun createPaymentLink(planType: String, phone: String, onResult: (success: Boolean, linkUrl: String?, linkId: String?, message: String) -> Unit) {
        if (SCRIPT_URL.startsWith("PASTE_")) {
            onResult(false, null, null, "Backend URL is not set - follow backend/SETUP.md")
            return
        }
        Thread {
            try {
                val url = "$SCRIPT_URL?action=createPaymentLink" +
                    "&planType=${URLEncoder.encode(planType, "UTF-8")}" +
                    "&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}" +
                    "&phone=${URLEncoder.encode(phone, "UTF-8")}"
                val response = httpGet(url)
                val json = JSONObject(response)
                if (json.optString("status") == "ok") {
                    val linkUrl = json.optString("linkUrl")
                    val linkId = json.optString("linkId")
                    mainHandler.post { onResult(true, linkUrl, linkId, "") }
                } else {
                    val message = json.optString("message", "Could not start payment")
                    mainHandler.post { onResult(false, null, null, message) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, null, null, "Check your internet and try again") }
            }
        }.start()
    }

    /**
     * Checks a Cashfree payment link's status with the backend (which checks directly with
     * Cashfree's servers) and activates the plan automatically once paid - no code needed.
     */
    fun checkPayment(linkId: String, planType: String, onResult: (success: Boolean, message: String) -> Unit) {
        if (SCRIPT_URL.startsWith("PASTE_")) {
            onResult(false, "Backend URL is not set - follow backend/SETUP.md")
            return
        }
        Thread {
            try {
                val url = "$SCRIPT_URL?action=checkPayment" +
                    "&linkId=${URLEncoder.encode(linkId, "UTF-8")}" +
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
                    val message = json.optString("message", "Payment not completed yet")
                    mainHandler.post { onResult(false, message) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, "Could not check payment - try again") }
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
