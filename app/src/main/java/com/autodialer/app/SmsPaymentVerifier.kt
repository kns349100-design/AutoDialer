package com.autodialer.app

import android.content.Context
import android.net.Uri

/**
 * Scans the phone's own SMS inbox for a bank/UPI "money received" message that matches the
 * amount and arrived after a payment was started - this is what makes payment verification
 * automatic without any payment gateway (which would need a whitelisting approval that isn't
 * available yet). Needs READ_SMS permission, requested from MainActivity/SubscriptionActivity.
 *
 * This is a heuristic, not a cryptographic proof - bank SMS wording varies, so it looks for a
 * combination of a credit-type keyword ("credited", "received", "credit of") and the exact
 * amount as a number. If no matching SMS is found (permission denied, SMS delayed, an unusual
 * bank SMS format), the app falls back to the WhatsApp-screenshot + manual redeem-code flow
 * that already exists - nothing is ever silently stuck with no way to activate.
 */
object SmsPaymentVerifier {

    private val CREDIT_KEYWORDS = listOf("credited", "credit of", "received", "credit alert")

    /**
     * Returns true if an SMS arrived at or after [sinceMillis] that looks like a credit of
     * exactly [amountRupees]. Reads only the inbox, never sends or modifies anything.
     */
    fun foundMatchingCreditSms(context: Context, sinceMillis: Long, amountRupees: Int): Boolean {
        val amountPatterns = listOf(
            "Rs.$amountRupees", "Rs $amountRupees", "Rs. $amountRupees",
            "INR $amountRupees", "INR.$amountRupees",
            "₹$amountRupees", "₹ $amountRupees",
            "$amountRupees.00", "$amountRupees.0"
        )

        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("body", "date")
        val selection = "date >= ?"
        val selectionArgs = arrayOf(sinceMillis.toString())

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, "date DESC")
                ?.use { cursor ->
                    val bodyIndex = cursor.getColumnIndex("body")
                    if (bodyIndex == -1) return false
                    while (cursor.moveToNext()) {
                        val body = cursor.getString(bodyIndex) ?: continue
                        val lower = body.lowercase()
                        val hasKeyword = CREDIT_KEYWORDS.any { lower.contains(it) }
                        val hasAmount = amountPatterns.any { body.contains(it, ignoreCase = true) }
                        if (hasKeyword && hasAmount) return true
                    }
                }
        } catch (e: SecurityException) {
            return false // permission not granted - caller falls back to manual flow
        } catch (e: Exception) {
            return false
        }
        return false
    }
}
