package com.autodialer.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

        const val PRICE_HOURLY12_PAISE = 900    // Rs 9
        const val PRICE_MONTHLY_PAISE = 29900   // Rs 299
        const val PRICE_YEARLY_PAISE = 99900    // Rs 999

        const val PRICE_MONTHLY = "Rs 299 / month"
        const val PRICE_YEARLY = "Rs 999 / year"
        const val PRICE_HOURLY12 = "Rs 9 / 12 hours"
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
    /** Starts the 24-hour free trial - but only if this PHONE NUMBER has genuinely never used
     * one before, checked with the backend (never just locally). A purely on-device flag would
     * get wiped the instant the app is uninstalled and reinstalled, letting the same phone
     * number claim endless "free 24 hours" - this closes that off, since the check is against
     * the phone number's row on the server, which survives a reinstall even though the app's
     * local storage doesn't.
     */
    fun startFreeTrial(phone: String, onResult: (success: Boolean, message: String) -> Unit) {
        if (SCRIPT_URL.startsWith("PASTE_")) {
            onResult(false, "Backend URL is not set - follow backend/SETUP.md")
            return
        }
        Thread {
            try {
                val url = "$SCRIPT_URL?action=startTrial" +
                    "&phone=${URLEncoder.encode(phone, "UTF-8")}" +
                    "&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}"
                val response = httpGet(url)
                val json = JSONObject(response)
                val startedAt = json.optLong("startedAt", 0L)
                if (startedAt > 0L) {
                    // Synced locally either way - a fresh grant, or confirmation it was
                    // already used (possibly on a previous install of the app) - so the app's
                    // own trial state always matches the server's true history for this number.
                    prefs.edit().putLong("trialStartedAt", startedAt).apply()
                }
                if (json.optString("status") == "ok") {
                    val msg = if (json.optBoolean("resumed", false)) "Your free trial is already running - continuing it" else "Free trial started!"
                    mainHandler.post { onResult(true, msg) }
                } else {
                    val message = json.optString("message", "Free trial already used on this account")
                    mainHandler.post { onResult(false, message) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, "Check your internet and try again") }
            }
        }.start()
    }

    /**
     * "Now", but immune to the phone's clock being turned BACK to stretch a trial or plan. Time is
     * only ever allowed to move forward: if the system clock is found earlier than what this app has
     * already seen, time keeps advancing from the last trusted moment using the phone's uptime
     * clock (which the user cannot change) instead of following the wrong clock.
     */
    internal fun secureNowAt(wall: Long, elapsed: Long): Long {
        val lastTrusted = prefs.getLong("tsTrusted", 0L)
        val lastElapsed = prefs.getLong("tsElapsed", -1L)
        if (lastTrusted <= 0L || lastElapsed < 0L) {
            prefs.edit().putLong("tsTrusted", wall).putLong("tsElapsed", elapsed).apply()
            return wall
        }
        val expected = if (elapsed >= lastElapsed) lastTrusted + (elapsed - lastElapsed) else lastTrusted
        if (wall >= expected - 120_000L) {
            // Clock looks normal (a couple of minutes of drift is tolerated). Re-anchor now and
            // then so a long gap between app opens (or a restart) is followed by the real clock.
            if (wall - lastTrusted > 60_000L || elapsed < lastElapsed) {
                prefs.edit().putLong("tsTrusted", wall).putLong("tsElapsed", elapsed).apply()
            }
            return wall
        }
        // Clock was set backwards - ignore it.
        return expected
    }

    private fun secureNow(): Long = secureNowAt(System.currentTimeMillis(), SystemClock.elapsedRealtime())

    private fun trialStartedAt(): Long = prefs.getLong("trialStartedAt", 0L)

    fun isTrialActive(): Boolean {
        val startedAt = trialStartedAt()
        if (startedAt == 0L) return false
        val elapsed = secureNow() - startedAt
        return elapsed in 0 until TRIAL_DURATION_MS
    }

    fun trialMillisRemaining(): Long =
        (TRIAL_DURATION_MS - (secureNow() - trialStartedAt())).coerceAtLeast(0)

    private fun cachedExpiry(): Long = prefs.getLong("cachedExpiry", 0L)
    private fun cachedPlanType(): String = prefs.getString("cachedPlanType", "") ?: ""

    fun isSubscribed(): Boolean = secureNow() < cachedExpiry()

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
            isSubscribed() -> (cachedExpiry() - secureNow()).coerceAtLeast(0)
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

    /** Call this on app start / resume when internet may be available. Silently no-ops if offline, URL not configured, or checked very recently (throttled to reduce background network load / perceived slowness).
     * Besides refreshing the plan it (1) restores a free trial that this phone number already has but this install doesn't know about (reinstall / new login) and (2) if the server reports a payment that was made but never confirmed, claims it - so a payment can never end without access. */
    fun refreshStatusInBackground(force: Boolean = false) {
        if (SCRIPT_URL.startsWith("PASTE_")) return
        val now = System.currentTimeMillis()
        val lastChecked = prefs.getLong("lastStatusCheck", 0L)
        if (!force && now - lastChecked < 3 * 60 * 1000) return
        prefs.edit().putLong("lastStatusCheck", now).apply()
        Thread {
            try {
                val url = "$SCRIPT_URL?action=check&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}"
                val json = JSONObject(httpGet(url))
                if (json.optString("status") == "ok") {
                    applyServerPlan(json)
                }
                if (json.optBoolean("hasUnclaimed", false)) {
                    syncUnclaimedPayments()
                }
            } catch (e: Exception) {
                // Offline or backend not reachable - keep using cached/local status.
            }
            restoreTrialFromServer()
        }.start()
    }

    private fun applyServerPlan(json: JSONObject) {
        val expiryAt = json.optLong("expiryAt", 0L)
        val planType = json.optString("planType", "")
        prefs.edit()
            .putLong("cachedExpiry", expiryAt)
            .putString("cachedPlanType", planType)
            .apply()
    }

    /** Asks the server to re-check every recent order of this device with Razorpay and activate the paid ones. Runs on the caller's (background) thread. */
    private fun syncUnclaimedPayments() {
        try {
            val url = "$SCRIPT_URL?action=razorpaySync&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}"
            val json = JSONObject(httpGet(url))
            if (json.optString("status") == "ok") {
                applyServerPlan(json)
                if (json.optInt("activated", 0) > 0) clearPendingPayment()
            }
        } catch (e: Exception) {
            // Will simply be retried on the next status refresh.
        }
    }

    /** If this phone number already used its free trial but this install has no record of it (app reinstalled / cleared, or logged in on a new phone), pick the original start time up from the server - so the remaining time continues instead of the trial looking like it never happened. Runs on the caller's (background) thread. */
    private fun restoreTrialFromServer() {
        try {
            if (prefs.contains("trialStartedAt")) return
            val phone = AuthManager(context).phoneNumber()
            if (phone.isBlank()) return
            val url = "$SCRIPT_URL?action=trialInfo&phone=${URLEncoder.encode(phone, "UTF-8")}"
            val json = JSONObject(httpGet(url))
            if (json.optString("status") == "ok" && json.optBoolean("used", false)) {
                val startedAt = json.optLong("startedAt", 0L)
                if (startedAt > 0L) prefs.edit().putLong("trialStartedAt", startedAt).apply()
            }
        } catch (e: Exception) {
            // Optional - ignored if offline.
        }
    }

    /** Redeems a coupon code against the server. Calls back on the main thread with a result message. */
    /** Grants the plan the instant an incoming bank/UPI SMS confirms the payment actually
     * landed (see SmsPaymentVerifier) - no server round-trip, so access unlocks immediately.
     *
     * Money-safety rule: this always EXTENDS from whichever is later - "now" or the current
     * expiry - it never resets/shortens existing time. Without this, someone with months left
     * on a longer plan who buys a shorter one (by mistake, or to add a top-up) would have
     * their remaining time silently wiped out and replaced by the shorter plan's duration -
     * that would mean real money they already paid for is lost. This guarantees a payment can
     * only ever add time, never remove it.
     */
    fun grantPlanLocally(planType: String) {
        val now = secureNow()
        val durationMs = planDurationMs(planType)
        val base = maxOf(cachedExpiry(), now)
        val newExpiry = base + durationMs
        prefs.edit()
            .putLong("cachedExpiry", newExpiry)
            .putString("cachedPlanType", planType)
            .apply()
    }

    private fun planDurationMs(planType: String): Long = when (planType) {
        "YEARLY" -> 365L * 24 * 60 * 60 * 1000
        "HOURLY12" -> 12L * 60 * 60 * 1000
        else -> 30L * 24 * 60 * 60 * 1000 // MONTHLY
    }

    /** Human-friendly plan name + price, for the "payment confirmed" screen and anywhere else
     * a plan needs to be shown clearly to the person who just paid for it. */
    fun planDisplayInfo(planType: String): Pair<String, String> = when (planType) {
        "HOURLY12" -> "12 Hour Access" to PRICE_HOURLY12
        "MONTHLY" -> "1 Month Access" to PRICE_MONTHLY
        "YEARLY" -> "1 Year Access" to PRICE_YEARLY
        else -> planType to ""
    }

    /** The exact date/time the current plan (or trial) runs out, e.g. "12 Sep 2026, 6:40 PM" -
     * shown on the congratulations screen so it's unambiguous exactly what was bought. */
    fun expiryDateLabel(): String {
        val expiry = when {
            isSubscribed() -> cachedExpiry()
            isTrialActive() -> trialStartedAt() + TRIAL_DURATION_MS
            else -> secureNow() // defensive fallback - should never be hit in practice
        }
        val fmt = java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(expiry))
    }

    // ---- Pending payment tracking (survives the app/Activity being killed while waiting) ----
    // If the app gets killed by Android while the user is off in their UPI app (very common -
    // switching apps often triggers this on low-RAM phones), a plain in-memory variable
    // tracking "a payment is in progress" would be lost, and the automatic SMS check would
    // never resume when they come back - they'd have paid with no way to know to follow up.
    // Persisting this to disk means the check always resumes correctly no matter what happens
    // to the app process in between.

    fun savePendingPayment(planType: String, amountRupees: Int, startedAt: Long, reference: String) {
        prefs.edit()
            .putString("pendingPayPlanType", planType)
            .putInt("pendingPayAmount", amountRupees)
            .putLong("pendingPayStartedAt", startedAt)
            .putString("pendingPayReference", reference)
            .apply()
    }

    fun clearPendingPayment() {
        prefs.edit()
            .remove("pendingPayPlanType")
            .remove("pendingPayAmount")
            .remove("pendingPayStartedAt")
            .remove("pendingPayReference")
            .apply()
    }

    data class PendingPayment(val planType: String, val amountRupees: Int, val startedAt: Long, val reference: String)

    /** Returns the in-progress payment (if any) that hasn't been confirmed or given up on yet.
     * A pending payment older than 7 days is treated as abandoned and cleared automatically. */
    fun loadPendingPayment(): PendingPayment? {
        val planType = prefs.getString("pendingPayPlanType", null) ?: return null
        val startedAt = prefs.getLong("pendingPayStartedAt", 0L)
        if (startedAt == 0L || System.currentTimeMillis() - startedAt > 7L * 24 * 60 * 60 * 1000) {
            clearPendingPayment()
            return null
        }
        val amount = prefs.getInt("pendingPayAmount", 0)
        val reference = prefs.getString("pendingPayReference", "") ?: ""
        return PendingPayment(planType, amount, startedAt, reference)
    }

    fun redeemCode(rawCode: String, onResult: (success: Boolean, message: String, planType: String?) -> Unit) {
        if (SCRIPT_URL.startsWith("PASTE_")) {
            onResult(false, "Backend URL is not set - follow backend/SETUP.md", null)
            return
        }
        val code = rawCode.trim()
        if (code.isEmpty()) {
            onResult(false, "Enter a code", null)
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
                    mainHandler.post { onResult(true, "Activated! Plan: $planType", planType) }
                } else {
                    val message = json.optString("message", "Invalid code")
                    mainHandler.post { onResult(false, message, null) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, "Check your internet and try again", null) }
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


    data class RazorpayOrder(val orderId: String, val amountPaise: Int, val keyId: String, val planLabel: String)

    /** Asks the backend to create a Razorpay order for the plan. The PRICE is decided on the server
     * (the app only names the plan), and the order is logged against this device before the payment
     * screen opens - which is what lets a paid order always be recovered. Calls back on the main thread. */
    fun createRazorpayOrder(planType: String, onResult: (order: RazorpayOrder?, message: String) -> Unit) {
        if (SCRIPT_URL.startsWith("PASTE_")) {
            onResult(null, "Backend URL is not set - follow backend/SETUP.md")
            return
        }
        Thread {
            try {
                val url = "$SCRIPT_URL?action=razorpayCreateOrder" +
                    "&plan=${URLEncoder.encode(planType, "UTF-8")}" +
                    "&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}"
                val json = JSONObject(httpGet(url))
                if (json.optString("status") == "ok") {
                    val order = RazorpayOrder(
                        json.getString("orderId"),
                        json.getInt("amount"),
                        json.getString("keyId"),
                        json.optString("planLabel", "")
                    )
                    mainHandler.post { onResult(order, "") }
                } else {
                    val message = json.optString("message", "Could not start payment")
                    mainHandler.post { onResult(null, message) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(null, "Check your internet and try again") }
            }
        }.start()
    }

    /** Asks the license backend to confirm (directly with Razorpay) that this order is paid, and
     * activates the plan on this device. Safe to call repeatedly - the server never grants the
     * same order twice. */
    fun confirmRazorpayPayment(orderId: String, onResult: (success: Boolean, planType: String?, message: String) -> Unit) {
        if (SCRIPT_URL.startsWith("PASTE_")) {
            onResult(false, null, "Backend URL is not set - follow backend/SETUP.md")
            return
        }
        Thread {
            try {
                val url = "$SCRIPT_URL?action=razorpayVerify" +
                    "&orderId=${URLEncoder.encode(orderId, "UTF-8")}" +
                    "&deviceId=${URLEncoder.encode(deviceId(), "UTF-8")}"
                val json = JSONObject(httpGet(url))
                if (json.optString("status") == "ok") {
                    val expiryAt = json.optLong("expiryAt", 0L)
                    val type = json.optString("planType", "")
                    prefs.edit()
                        .putLong("cachedExpiry", expiryAt)
                        .putString("cachedPlanType", type)
                        .apply()
                    mainHandler.post { onResult(true, type, "") }
                } else {
                    val message = json.optString("message", "Payment not confirmed yet")
                    mainHandler.post { onResult(false, null, message) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, null, "Could not confirm payment - check your internet") }
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
