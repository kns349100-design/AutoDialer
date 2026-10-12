package com.autodialer.app

/**
 * Finds phone numbers in pasted / imported / OCR'd text. Same rules as before (this is the code
 * that used to live inside MainActivity) - moved here so the three regular expressions are built
 * ONCE instead of being re-compiled for every single line and every single match. On a pasted
 * list of a few thousand numbers that re-compiling was the main reason tapping "Load" froze the
 * screen for a while.
 */
object NumberParser {

    private val plainRun = Regex("\\d{10,13}")
    private val spaced = Regex("(\\+?\\d[\\d\\-\\s.()]{7,16}\\d)")
    private val separators = Regex("[\\s\\-.()]")

    /**
     * Two-pass extraction so far fewer numbers get missed:
     *  1) Plain digit runs (10-13 digits) exactly as they were read - covers the common case
     *     of a number printed with no separators.
     *  2) Digits allowed to have spaces/hyphens/dots/brackets between them (spaced-out or
     *     STD-code-style numbers).
     * Both passes tolerate the OCR noise (bullets, pipes, extra spacing) that was making real
     * numbers slip through before.
     */
    fun extractPhoneNumbers(text: String): Set<String> {
        val raw = linkedSetOf<String>()

        // Pass 1: clean, unbroken digit runs.
        plainRun.findAll(text).forEach { match ->
            raw.add(match.value)
        }

        // Pass 2: numbers with spacing/punctuation mixed in (e.g. "+91 90750-34748",
        // "90750 34748", "(+91) 9075034748").
        spaced.findAll(text).forEach { match ->
            val cleaned = match.value.replace(separators, "")
            val digitsOnly = cleaned.replace("+", "")
            if (digitsOnly.length in 10..13 && digitsOnly.all { it.isDigit() }) {
                raw.add(cleaned)
            }
        }

        // A number written as "+91 9876543210" gets matched by BOTH passes above as two
        // different-looking strings ("9876543210" from pass 1, "+919876543210" from pass 2) -
        // collapsing by the actual normalized number means the same real number is only ever
        // reported once, however it was written.
        val seenNormalized = mutableSetOf<String>()
        val results = linkedSetOf<String>()
        for (candidate in raw) {
            val normalized = PhoneUtils.normalize(candidate)
            if (seenNormalized.add(normalized)) results.add(candidate)
        }

        return results
    }

    /**
     * Finds every phone-number-shaped token on one line, and the name next to it (on either
     * side, or none - then it gets an auto label like "Lead 7").
     */
    fun extractLeadsFromLine(line: String, startIndex: Int): List<Lead> {
        val candidates = extractPhoneNumbers(line).toList()
        if (candidates.isEmpty()) return emptyList()

        var leftover = line
        candidates.forEach { leftover = leftover.replace(it, " ") }
        leftover = leftover.trim(' ', ',', '-', '|', '\t', ':', ';').trim()
        val label = leftover.split(",").map { it.trim() }.firstOrNull { it.isNotBlank() && it.any { c -> c.isLetter() } }

        return candidates.mapIndexedNotNull { i, token ->
            val digitsOnly = token.filter { it.isDigit() }
            // Reject anything that can't possibly be a real phone number (a stray 4-digit
            // code, a partial OCR fragment, etc) - never load/dial something that isn't
            // actually a phone number.
            if (digitsOnly.length !in 10..13) return@mapIndexedNotNull null
            val finalLabel = when {
                !label.isNullOrBlank() && candidates.size == 1 -> label
                !label.isNullOrBlank() -> "$label ${i + 1}"
                else -> "Lead ${startIndex + i}"
            }
            Lead(finalLabel, token)
        }
    }
}
