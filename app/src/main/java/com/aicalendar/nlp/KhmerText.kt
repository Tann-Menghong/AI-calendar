package com.aicalendar.nlp

/**
 * Khmer script helpers.
 *
 * Every transform here is length preserving on purpose: the parser records character
 * ranges against the normalised string and then slices the *original* string to build
 * the event title, so the two must stay index-aligned.
 */
object KhmerText {

    private const val KHMER_ZERO = '០' // ០
    private const val KHMER_NINE = '៩' // ៩

    /** Khmer letters, signs and digits (U+1780..U+17FF). */
    fun isKhmer(c: Char): Boolean = c.code in 0x1780..0x17FF

    fun containsKhmer(text: String): Boolean = text.any { isKhmer(it) }

    /**
     * Fraction of Khmer letters among all letters. Used to pick a speech-recogniser
     * locale and to decide which OCR engine's output to trust.
     */
    fun khmerRatio(text: String): Float {
        var khmer = 0
        var total = 0
        for (c in text) {
            if (c.isLetter()) {
                total++
                if (isKhmer(c)) khmer++
            }
        }
        return if (total == 0) 0f else khmer.toFloat() / total
    }

    /** ០១២៣៤៥៦៧៨៩ -> 0123456789, leaving everything else untouched. */
    fun toAsciiDigits(text: String): String {
        if (text.none { it in KHMER_ZERO..KHMER_NINE }) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(if (c in KHMER_ZERO..KHMER_NINE) ('0' + (c - KHMER_ZERO)) else c)
        }
        return sb.toString()
    }

    /** 0123456789 -> ០១២៣៤៥៦៧៨៩, for rendering numbers in Khmer UI. */
    fun toKhmerDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(if (c in '0'..'9') (KHMER_ZERO + (c - '0')) else c)
        }
        return sb.toString()
    }

    /**
     * Normalises a string for matching: ASCII digits, lower-cased Latin, canonical
     * Khmer sub-consonant sign, and single spaces — all without changing its length.
     */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val mapped = when {
                c in KHMER_ZERO..KHMER_NINE -> '0' + (c - KHMER_ZERO)
                c.isWhitespace() -> ' '
                // Zero-width joiners/BOM would misalign spans, so map them to spaces.
                c == '​' || c == '‌' || c == '‍' || c == '﻿' -> ' '
                // Char-wise lowercase only: String.lowercase() can change length.
                else -> c.lowercaseChar()
            }
            sb.append(mapped)
        }
        return sb.toString()
    }
}
