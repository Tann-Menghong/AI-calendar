package com.aicalendar.nlp

import java.time.DayOfWeek
import java.time.Month

/**
 * Khmer and English vocabulary for dates, times and event framing.
 *
 * All keys are stored pre-normalised (see [KhmerText.normalize]) so lookups against
 * normalised input are exact.
 */
object DateTimeLexicon {

    private fun norm(s: String) = KhmerText.normalize(s)

    private fun <V> table(vararg pairs: Pair<String, V>): Map<String, V> =
        pairs.associate { (k, v) -> norm(k) to v }

    /** Day-of-week names, longest-first at match time so ព្រហស្បតិ៍ wins over ព្រហ. */
    val weekdays: Map<String, DayOfWeek> = table(
        "ច័ន្ទ" to DayOfWeek.MONDAY,
        "ចន្ទ" to DayOfWeek.MONDAY,
        "អង្គារ" to DayOfWeek.TUESDAY,
        "ពុធ" to DayOfWeek.WEDNESDAY,
        "ព្រហស្បតិ៍" to DayOfWeek.THURSDAY,
        "ព្រហស្បត្តិ៍" to DayOfWeek.THURSDAY,
        "សុក្រ" to DayOfWeek.FRIDAY,
        "សៅរ៍" to DayOfWeek.SATURDAY,
        "អាទិត្យ" to DayOfWeek.SUNDAY,
        "monday" to DayOfWeek.MONDAY,
        "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "tue" to DayOfWeek.TUESDAY,
        "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "thu" to DayOfWeek.THURSDAY,
        "thur" to DayOfWeek.THURSDAY,
        "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
        "sun" to DayOfWeek.SUNDAY,
    )

    val months: Map<String, Month> = table(
        "មករា" to Month.JANUARY,
        "កុម្ភៈ" to Month.FEBRUARY,
        "កុម្ភះ" to Month.FEBRUARY,
        "មីនា" to Month.MARCH,
        "មេសា" to Month.APRIL,
        "ឧសភា" to Month.MAY,
        "មិថុនា" to Month.JUNE,
        "កក្កដា" to Month.JULY,
        "សីហា" to Month.AUGUST,
        "កញ្ញា" to Month.SEPTEMBER,
        "តុលា" to Month.OCTOBER,
        "វិច្ឆិកា" to Month.NOVEMBER,
        "ធ្នូ" to Month.DECEMBER,
        "january" to Month.JANUARY,
        "jan" to Month.JANUARY,
        "february" to Month.FEBRUARY,
        "feb" to Month.FEBRUARY,
        "march" to Month.MARCH,
        "mar" to Month.MARCH,
        "april" to Month.APRIL,
        "apr" to Month.APRIL,
        "may" to Month.MAY,
        "june" to Month.JUNE,
        "jun" to Month.JUNE,
        "july" to Month.JULY,
        "jul" to Month.JULY,
        "august" to Month.AUGUST,
        "aug" to Month.AUGUST,
        "september" to Month.SEPTEMBER,
        "sept" to Month.SEPTEMBER,
        "sep" to Month.SEPTEMBER,
        "october" to Month.OCTOBER,
        "oct" to Month.OCTOBER,
        "november" to Month.NOVEMBER,
        "nov" to Month.NOVEMBER,
        "december" to Month.DECEMBER,
        "dec" to Month.DECEMBER,
    )

    /** Words that shift the day relative to "now", as a day offset. */
    val relativeDays: Map<String, Long> = table(
        "ថ្ងៃនេះ" to 0L,
        "យប់នេះ" to 0L,
        "ថ្ងៃស្អែក" to 1L,
        "ស្អែក" to 1L,
        "ខានស្អែក" to 2L,
        "ថ្ងៃខានស្អែក" to 2L,
        "ម្សិលមិញ" to -1L,
        "ម្សិលមិញនេះ" to -1L,
        "today" to 0L,
        "tonight" to 0L,
        "tomorrow" to 1L,
        "tmr" to 1L,
        "day after tomorrow" to 2L,
        "yesterday" to -1L,
    )

    /** Khmer/English markers meaning "the coming one" when attached to a weekday or unit. */
    val nextMarkers: List<String> = listOf("ក្រោយ", "បន្ទាប់", "next", "coming", "this coming").map(::norm)

    val lastMarkers: List<String> = listOf("មុន", "កន្លង", "last", "previous").map(::norm)

    /** Time-of-day words and the 12-hour window they select. */
    enum class DayPart { MORNING, NOON, AFTERNOON, EVENING, NIGHT, MIDNIGHT }

    val dayParts: Map<String, DayPart> = table(
        "ព្រឹក" to DayPart.MORNING,
        "ថ្ងៃត្រង់" to DayPart.NOON,
        "រសៀល" to DayPart.AFTERNOON,
        "ល្ងាច" to DayPart.EVENING,
        "យប់" to DayPart.NIGHT,
        "អធ្រាត្រ" to DayPart.MIDNIGHT,
        "morning" to DayPart.MORNING,
        "noon" to DayPart.NOON,
        "midday" to DayPart.NOON,
        "afternoon" to DayPart.AFTERNOON,
        "evening" to DayPart.EVENING,
        "night" to DayPart.NIGHT,
        "midnight" to DayPart.MIDNIGHT,
        "am" to DayPart.MORNING,
        "a.m." to DayPart.MORNING,
        "pm" to DayPart.AFTERNOON,
        "p.m." to DayPart.AFTERNOON,
    )

    /** Khmer number words for 1..12, which people use for hours as often as digits. */
    val khmerNumberWords: Map<String, Int> = table(
        "មួយ" to 1,
        "ពីរ" to 2,
        "បី" to 3,
        "បួន" to 4,
        "ប្រាំ" to 5,
        "ប្រាំមួយ" to 6,
        "ប្រាំពីរ" to 7,
        "ប្រាំបី" to 8,
        "ប្រាំបួន" to 9,
        "ដប់" to 10,
        "ដប់មួយ" to 11,
        "ដប់ពីរ" to 12,
    )

    // ---- structural markers -------------------------------------------------

    val hourMarker: String = norm("ម៉ោង")
    val minuteMarker: String = norm("នាទី")
    val halfMarker: String = norm("កន្លះ")
    val dayNumberMarker: String = norm("ថ្ងៃទី")
    val monthMarker: String = norm("ខែ")
    val yearMarker: String = norm("ឆ្នាំ")
    /** Both coeng spellings are in common use; treat them as the same word. */
    val weekMarkers: List<String> = listOf("សប្តាហ៍", "សប្ដាហ៍").map(::norm)
    val dayMarker: String = norm("ថ្ងៃ")
    val durationMarker: String = norm("រយៈពេល")
    val untilMarkers: List<String> = listOf("ដល់", "រហូតដល់", "ទល់", "to", "until", "till", "-", "–").map(::norm)
    val locationMarkers: List<String> = listOf("នៅឯ", "នៅ", "at", "in").map(::norm)
    val reminderMarkers: List<String> = listOf("រំលឹក", "ជូនដំណឹង", "remind", "reminder", "alert").map(::norm)
    val beforeMarkers: List<String> = listOf("មុន", "before", "ahead").map(::norm)

    /**
     * Filler removed from the title once date/time/location spans are stripped.
     * Keeping this list short avoids eating meaningful words.
     */
    val titleFillers: Set<String> = setOf(
        "នៅ", "ចុះ", "ហើយ", "ណា", "ផង", "សូម", "ជួយ", "បន្ថែម", "កត់", "ទុក",
        "add", "an", "a", "the", "please", "pls", "schedule", "create", "set", "up",
        "event", "meeting", "reminder", "remind", "me", "to", "for", "on", "at",
    ).map(::norm).toSet()

    /** Words that hint the event is a meeting, used only to title untitled drafts. */
    val defaultTitleKhmer: String = "ព្រឹត្តិការណ៍"
    val defaultTitleEnglish: String = "Event"
}
