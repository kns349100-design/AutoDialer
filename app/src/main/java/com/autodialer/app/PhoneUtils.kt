package com.autodialer.app

object PhoneUtils {
    /**
     * Normalizes a phone number to a canonical "91XXXXXXXXXX" digits-only form so the SAME
     * number typed/scanned in different formats (9075034748, +919075034748, 09075034748,
     * 91-9075034748, etc) is always recognized as the same number. This is the single source
     * of truth used everywhere a number needs to be compared - duplicate detection inside a
     * pasted list, "already called in last N days" checks, and the final safety check right
     * before dialing. Never used for what's actually dialed - the original phone string is
     * still what gets passed to ACTION_CALL.
     */
    fun normalize(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "91$digits"
            digits.length == 11 && digits.startsWith("0") -> "91${digits.substring(1)}"
            digits.length == 12 && digits.startsWith("91") -> digits
            digits.length == 13 && digits.startsWith("091") -> "91${digits.substring(2)}"
            else -> digits
        }
    }
}
