package com.aicalendar.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KhmerTextTest {

    // ---- script detection ----------------------------------------------------

    @Test
    fun isKhmerCoversLettersAndDigits() {
        assertTrue(KhmerText.isKhmer('ក'))
        assertTrue(KhmerText.isKhmer('ថ'))
        assertTrue(KhmerText.isKhmer('០'))
        assertTrue(KhmerText.isKhmer('៩'))
        assertFalse(KhmerText.isKhmer('a'))
        assertFalse(KhmerText.isKhmer('Z'))
        assertFalse(KhmerText.isKhmer('1'))
        assertFalse(KhmerText.isKhmer(' '))
    }

    @Test
    fun containsKhmerDetectsAnySingleKhmerCharacter() {
        assertTrue(KhmerText.containsKhmer("ប្រជុំ"))
        assertTrue(KhmerText.containsKhmer("meeting ក today"))
        assertFalse(KhmerText.containsKhmer("meeting today"))
        assertFalse(KhmerText.containsKhmer(""))
    }

    // ---- digit conversion, both directions -----------------------------------

    @Test
    fun khmerDigitsConvertToAscii() {
        assertEquals("0123456789", KhmerText.toAsciiDigits("០១២៣៤៥៦៧៨៩"))
    }

    @Test
    fun toAsciiDigitsLeavesNonDigitsAlone() {
        assertEquals("ថ្ងៃទី 15", KhmerText.toAsciiDigits("ថ្ងៃទី ១៥"))
        assertEquals("abc 123 !", KhmerText.toAsciiDigits("abc 123 !"))
    }

    @Test
    fun toAsciiDigitsReturnsTheSameInstanceWhenNothingToDo() {
        val input = "no khmer digits here 42"
        assertTrue(input === KhmerText.toAsciiDigits(input))
    }

    @Test
    fun asciiDigitsConvertToKhmer() {
        assertEquals("០១២៣៤៥៦៧៨៩", KhmerText.toKhmerDigits("0123456789"))
    }

    @Test
    fun toKhmerDigitsLeavesNonDigitsAlone() {
        assertEquals("ម៉ោង ៣:៣០", KhmerText.toKhmerDigits("ម៉ោង 3:30"))
    }

    @Test
    fun digitConversionRoundTrips() {
        val ascii = "2026-08-12 15:30"
        assertEquals(ascii, KhmerText.toAsciiDigits(KhmerText.toKhmerDigits(ascii)))
    }

    // ---- khmerRatio ----------------------------------------------------------

    @Test
    fun khmerRatioIsZeroWithoutLetters() {
        assertEquals(0f, KhmerText.khmerRatio(""), 0f)
        assertEquals(0f, KhmerText.khmerRatio("123 !!  "), 0f)
    }

    @Test
    fun khmerRatioIsOneForPureKhmer() {
        assertEquals(1f, KhmerText.khmerRatio("ថ្ងៃស្អែក ម៉ោង ៣ រសៀល"), 0f)
    }

    @Test
    fun khmerRatioIsZeroForPureLatin() {
        assertEquals(0f, KhmerText.khmerRatio("Meeting tomorrow at 3pm"), 0f)
    }

    @Test
    fun khmerRatioIgnoresDigitsAndPunctuation() {
        // Only letters count towards the denominator, so the digits do not dilute it.
        assertEquals(0.5f, KhmerText.khmerRatio("កa"), 0f)
        assertEquals(0.5f, KhmerText.khmerRatio("ក a 1234 !!!"), 0f)
    }

    @Test
    fun khmerRatioCountsKhmerDigitsAsNonLetters() {
        // Khmer digits are in the Khmer block but are not letters.
        assertEquals(0f, KhmerText.khmerRatio("០១២៣"), 0f)
    }

    // ---- normalize -----------------------------------------------------------

    @Test
    fun normalizePreservesLength() {
        val samples = listOf(
            "",
            "ថ្ងៃស្អែក ម៉ោង ៣ រសៀល",
            "Meeting TOMORROW at 3PM",
            "ប្រជុំ ១២៣ abc",
            "a​b\tc\nd",
            "ចាប់ផ្តើម‌នៅ﻿ ៩៩",
            "MiXeD ខ្មែរ 42 !!",
            "  padded  ",
            "ÀÉÎÕÜ",
            "ថ្ងៃទី ១៥ ខែ សីហា ឆ្នាំ ២០២៦ ម៉ោង ១០ ព្រឹក",
        )
        for (s in samples) {
            assertEquals("length changed for [$s]", s.length, KhmerText.normalize(s).length)
        }
    }

    @Test
    fun normalizeLowercasesLatin() {
        assertEquals("meeting tomorrow at 3pm", KhmerText.normalize("Meeting TOMORROW at 3PM"))
    }

    @Test
    fun normalizeConvertsKhmerDigits() {
        assertEquals("ម៉ោង 3 រសៀល", KhmerText.normalize("ម៉ោង ៣ រសៀល"))
    }

    @Test
    fun normalizeMapsWhitespaceToSingleSpaceCharacters() {
        assertEquals("a b c d", KhmerText.normalize("a\tb\nc\rd"))
    }

    @Test
    fun normalizeMapsZeroWidthCharactersToSpaces() {
        assertEquals("a b c", KhmerText.normalize("a​b‌c"))
        assertEquals("a b", KhmerText.normalize("a﻿b"))
        assertEquals("a b", KhmerText.normalize("a‍b"))
    }

    @Test
    fun normalizeLeavesKhmerLettersUntouched() {
        val khmer = "ថ្ងៃស្អែក"
        assertEquals(khmer, KhmerText.normalize(khmer))
    }

    @Test
    fun normalizeIsIdempotent() {
        val input = "Meeting ថ្ងៃស្អែក ម៉ោង ៣\tរសៀល"
        val once = KhmerText.normalize(input)
        assertEquals(once, KhmerText.normalize(once))
    }
}
