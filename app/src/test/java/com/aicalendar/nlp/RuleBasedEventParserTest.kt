package com.aicalendar.nlp

import com.aicalendar.data.model.CaptureSource
import com.aicalendar.data.model.ExtractionEngine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the deterministic Khmer + English grammar.
 *
 * Every case is resolved against a single fixed reference instant so the assertions
 * never drift with the wall clock.
 */
class RuleBasedEventParserTest {

    private val parser = RuleBasedEventParser()

    /** Wednesday, 12 August 2026 at 10:00. */
    private val now = LocalDateTime.of(2026, 8, 12, 10, 0)

    private fun parse(input: String) = parser.parse(input, now)

    private fun start(input: String): LocalDateTime {
        val draft = parse(input)
        assertNotNull("expected a draft for [$input]", draft)
        return draft!!.start
    }

    @Test
    fun referenceInstantIsAWednesday() {
        assertEquals(DayOfWeek.WEDNESDAY, now.dayOfWeek)
    }

    // ---- Khmer relative days -------------------------------------------------

    @Test
    fun khmerTomorrowShiftsOneDay() {
        assertEquals(LocalDate.of(2026, 8, 13), start("ថ្ងៃស្អែក").toLocalDate())
    }

    @Test
    fun khmerTodayStaysOnToday() {
        assertEquals(LocalDate.of(2026, 8, 12), start("ថ្ងៃនេះ").toLocalDate())
    }

    @Test
    fun khmerDayAfterTomorrowShiftsTwoDays() {
        assertEquals(LocalDate.of(2026, 8, 14), start("ខានស្អែក").toLocalDate())
    }

    @Test
    fun khmerRelativeDayCombinesWithTitleAndTime() {
        val draft = parse("ប្រជុំ ថ្ងៃស្អែក ម៉ោង ៣ រសៀល")!!
        assertEquals("ប្រជុំ", draft.title)
        assertEquals(LocalDateTime.of(2026, 8, 13, 15, 0), draft.start)
        assertEquals(LocalDateTime.of(2026, 8, 13, 16, 0), draft.end)
        assertFalse(draft.allDay)
        assertFalse(draft.dateWasGuessed)
    }

    // ---- Khmer times with day parts -----------------------------------------

    @Test
    fun khmerAfternoonMarkerPushesHourToPm() {
        assertEquals(LocalTime.of(15, 0), start("ថ្ងៃស្អែក ម៉ោង ៣ រសៀល").toLocalTime())
    }

    @Test
    fun khmerMorningMarkerKeepsHourInTheMorning() {
        assertEquals(LocalDateTime.of(2026, 8, 12, 8, 0), start("ថ្ងៃនេះ ម៉ោង ៨ ព្រឹក"))
    }

    @Test
    fun khmerNightMarkerPushesHourToEvening() {
        assertEquals(LocalDateTime.of(2026, 8, 14, 19, 0), start("ខានស្អែក ម៉ោង ៧ យប់"))
    }

    @Test
    fun khmerEveningMarkerPushesHourToEvening() {
        assertEquals(LocalTime.of(18, 0), start("ថ្ងៃស្អែក ម៉ោង ៦ ល្ងាច").toLocalTime())
    }

    // ---- Khmer numerals ------------------------------------------------------

    @Test
    fun khmerNumeralsAreConvertedToAsciiDigits() {
        // 10 is unambiguous morning-side, and a bare time rolls to the next
        // occurrence, which is tomorrow because 10:00 today is not after "now".
        assertEquals(LocalDateTime.of(2026, 8, 13, 10, 0), start("ម៉ោង ១០"))
    }

    @Test
    fun bareKhmerTimeInTheFutureStaysOnToday() {
        assertEquals(LocalDateTime.of(2026, 8, 12, 11, 0), start("ម៉ោង ១១"))
    }

    @Test
    fun khmerHalfHourMarkerAddsThirtyMinutes() {
        // No day part, so the 1..7 -> PM heuristic turns 3 into 15:00, plus កន្លះ.
        assertEquals(LocalDateTime.of(2026, 8, 12, 15, 30), start("ម៉ោង ៣ កន្លះ"))
    }

    // ---- Khmer weekday, explicit date, offsets -------------------------------

    @Test
    fun khmerWeekdayResolvesToTheComingFriday() {
        val draft = parse("ថ្ងៃសុក្រ")!!
        assertEquals(LocalDate.of(2026, 8, 14), draft.start.toLocalDate())
        assertEquals(DayOfWeek.FRIDAY, draft.start.dayOfWeek)
        assertTrue(draft.allDay)
    }

    @Test
    fun khmerWeekdayCombinesWithAnAfternoonTime() {
        assertEquals(LocalDateTime.of(2026, 8, 14, 14, 0), start("ប្រជុំ ថ្ងៃសុក្រ ម៉ោង ២ រសៀល"))
    }

    @Test
    fun khmerExplicitDayAndMonth() {
        val draft = parse("ថ្ងៃទី ១៥ ខែ សីហា")!!
        assertEquals(LocalDate.of(2026, 8, 15), draft.start.toLocalDate())
        assertTrue(draft.allDay)
    }

    @Test
    fun khmerExplicitDateAlreadyPastRollsForwardAYear() {
        // 1 August 2026 is behind the reference date and the month was given,
        // so the parser reads it as next year.
        assertEquals(LocalDate.of(2027, 8, 1), start("ថ្ងៃទី ១ ខែ សីហា").toLocalDate())
    }

    @Test
    fun khmerNextWeekAddsSevenDays() {
        assertEquals(LocalDate.of(2026, 8, 19), start("សប្តាហ៍ក្រោយ").toLocalDate())
    }

    @Test
    fun khmerNextMonthAddsOneMonth() {
        assertEquals(LocalDate.of(2026, 9, 12), start("ខែក្រោយ").toLocalDate())
    }

    // ---- Khmer duration, reminder, location ----------------------------------

    @Test
    fun khmerDurationSetsTheEventLength() {
        val draft = parse("ប្រជុំ រយៈពេល ២ ម៉ោង")!!
        assertEquals(120L, draft.durationMinutes)
        assertEquals("ប្រជុំ", draft.title)
    }

    @Test
    fun khmerReminderOverridesTheDefault() {
        val draft = parse("រំលឹក មុន ៣០ នាទី ថ្ងៃស្អែក")!!
        assertEquals(30, draft.reminderMinutes)
        assertEquals(LocalDate.of(2026, 8, 13), draft.start.toLocalDate())
    }

    @Test
    fun khmerLocationFollowsNoev() {
        val draft = parse("ជួប នៅ ភ្នំពេញ")!!
        assertEquals("ភ្នំពេញ", draft.location)
        assertEquals("ជួប", draft.title)
    }

    @Test
    fun khmerLocationStopsAtTheNextClaimedSpan() {
        val draft = parse("ញាំបាយ នៅ ផ្សារ ថ្មី ថ្ងៃស្អែក ម៉ោង ៦ ល្ងាច")!!
        assertEquals("ផ្សារ ថ្មី", draft.location)
        assertEquals("ញាំបាយ", draft.title)
        assertEquals(LocalDateTime.of(2026, 8, 13, 18, 0), draft.start)
    }

    // ---- English -------------------------------------------------------------

    @Test
    fun englishTomorrowWithMeridiemTime() {
        assertEquals(LocalDateTime.of(2026, 8, 13, 15, 0), start("tomorrow 3pm"))
    }

    @Test
    fun englishNextFridayResolvesToTheComingFriday() {
        val draft = parse("next friday 9am")!!
        assertEquals(LocalDateTime.of(2026, 8, 14, 9, 0), draft.start)
        assertEquals(DayOfWeek.FRIDAY, draft.start.dayOfWeek)
    }

    @Test
    fun leadingNextMarkerIsNotLeftInTheTitle() {
        // The marker sits before the weekday, so it must be claimed from the left.
        assertEquals("dentist", parse("dentist next friday 9am")!!.title)
        assertEquals("Event", parse("next friday 9am")!!.title)
    }

    @Test
    fun englishBareWeekdayResolvesToTheComingOne() {
        assertEquals(LocalDateTime.of(2026, 8, 14, 9, 0), start("friday 9am"))
    }

    @Test
    fun englishAtHourWithoutMeridiemUsesBusinessHours() {
        // "at 4" with no marker: 1..7 reads as the afternoon.
        assertEquals(LocalDateTime.of(2026, 8, 12, 16, 0), start("meeting at 4"))
    }

    @Test
    fun twentyFourHourReadingIsNotShifted() {
        assertEquals(LocalDateTime.of(2026, 8, 12, 15, 30), start("15:30"))
    }

    @Test
    fun englishMonthThenDay() {
        val draft = parse("aug 5")!!
        // 5 August 2026 is already behind the reference date, so it rolls to 2027.
        assertEquals(LocalDate.of(2027, 8, 5), draft.start.toLocalDate())
        assertTrue(draft.allDay)
    }

    @Test
    fun englishDayThenMonth() {
        val draft = parse("5 august")!!
        assertEquals(LocalDate.of(2027, 8, 5), draft.start.toLocalDate())
        assertTrue(draft.allDay)
    }

    @Test
    fun fullMonthNameIsConsumedWholeNotJustItsAbbreviation() {
        // "aug" must not win over "august" and strand "ust" in the title.
        assertEquals("conference", parse("conference 5 august")!!.title)
        assertEquals("conference", parse("conference 12 september")!!.title)
        assertEquals("conference", parse("conference on 3 march")!!.title)
    }

    @Test
    fun englishDurationSetsTheEventLength() {
        val draft = parse("lunch for 2 hours")!!
        assertEquals(120L, draft.durationMinutes)
        assertEquals("lunch", draft.title)
    }

    @Test
    fun englishInNDaysOffsetsTheDate() {
        val draft = parse("gym in 3 days")!!
        assertEquals(LocalDate.of(2026, 8, 15), draft.start.toLocalDate())
        assertEquals("gym", draft.title)
        assertTrue(draft.allDay)
    }

    @Test
    fun englishTimeRangeSetsBothEnds() {
        val draft = parse("call mom 3pm to 5pm")!!
        assertEquals(LocalDateTime.of(2026, 8, 12, 15, 0), draft.start)
        assertEquals(LocalDateTime.of(2026, 8, 12, 17, 0), draft.end)
        assertEquals(120L, draft.durationMinutes)
        assertFalse(draft.allDay)
    }

    @Test
    fun englishReminderPhraseOverridesTheDefault() {
        val draft = parse("dentist tomorrow remind me 30 minutes before")!!
        assertEquals(30, draft.reminderMinutes)
        assertEquals("dentist", draft.title)
        assertEquals(LocalDate.of(2026, 8, 13), draft.start.toLocalDate())
    }

    @Test
    fun reminderDefaultsToFifteenMinutes() {
        assertEquals(15, parse("tomorrow 3pm")!!.reminderMinutes)
    }

    // ---- title extraction ----------------------------------------------------

    @Test
    fun titleKeepsNoDateTimeOrLocationWords() {
        val draft = parse("Team standup tomorrow 9am at the office")!!
        assertEquals("Team standup", draft.title)
        assertEquals("the office", draft.location)
        for (word in listOf("tomorrow", "9am", "at", "office")) {
            assertFalse(
                "title [${draft.title}] should not contain [$word]",
                draft.title.lowercase().contains(word),
            )
        }
    }

    @Test
    fun khmerTitleKeepsNoDateTimeOrLocationWords() {
        val draft = parse("ប្រជុំក្រុម ថ្ងៃស្អែក ម៉ោង ១០ ព្រឹក នៅ ការិយាល័យ")!!
        assertEquals("ប្រជុំក្រុម", draft.title)
        assertEquals("ការិយាល័យ", draft.location)
        for (word in listOf("ថ្ងៃស្អែក", "ម៉ោង", "១០", "ព្រឹក", "នៅ", "ការិយាល័យ")) {
            assertFalse(
                "title [${draft.title}] should not contain [$word]",
                draft.title.contains(word),
            )
        }
        assertEquals(LocalDateTime.of(2026, 8, 13, 10, 0), draft.start)
    }

    @Test
    fun titleKeepsTheMiddleOfAnEnglishPhrase() {
        assertEquals("Lunch with Sara", parse("Lunch with Sara tomorrow at 1pm")!!.title)
    }

    @Test
    fun untitledEnglishInputFallsBackToTheEnglishDefault() {
        assertEquals(DateTimeLexicon.defaultTitleEnglish, parse("tomorrow 3pm")!!.title)
    }

    @Test
    fun untitledKhmerInputFallsBackToTheKhmerDefault() {
        assertEquals(DateTimeLexicon.defaultTitleKhmer, parse("ថ្ងៃស្អែក")!!.title)
    }

    // ---- allDay / guessing / blank ------------------------------------------

    @Test
    fun allDayIsTrueWhenADateIsFoundButNoTime() {
        val draft = parse("gym tomorrow")!!
        assertTrue(draft.allDay)
        assertEquals(LocalDateTime.of(2026, 8, 13, 0, 0), draft.start)
        assertEquals(LocalDateTime.of(2026, 8, 13, 23, 59), draft.end)
    }

    @Test
    fun allDayIsFalseWhenATimeIsFound() {
        assertFalse(parse("gym tomorrow 9am")!!.allDay)
    }

    @Test
    fun dateWasGuessedWhenNeitherDateNorTimeIsPresent() {
        val draft = parse("coffee")!!
        assertTrue(draft.dateWasGuessed)
        assertEquals("coffee", draft.title)
        // 10:00 rounds up to the next half hour.
        assertEquals(LocalDateTime.of(2026, 8, 12, 10, 30), draft.start)
        assertFalse(draft.allDay)
    }

    @Test
    fun dateWasGuessedIsFalseWhenADateIsPresent() {
        assertFalse(parse("coffee tomorrow")!!.dateWasGuessed)
    }

    @Test
    fun dateWasGuessedIsFalseWhenOnlyATimeIsPresent() {
        assertFalse(parse("coffee at 4")!!.dateWasGuessed)
    }

    @Test
    fun blankInputReturnsNull() {
        assertNull(parser.parse("", now))
        assertNull(parser.parse("   ", now))
        assertNull(parser.parse("\n\t ", now))
    }

    // ---- draft metadata ------------------------------------------------------

    @Test
    fun draftCarriesEngineSourceAndRawInput() {
        val draft = parser.parse("  ប្រជុំ ថ្ងៃស្អែក  ", now, CaptureSource.VOICE)!!
        assertEquals(ExtractionEngine.RULES, draft.engine)
        assertEquals(CaptureSource.VOICE, draft.source)
        assertEquals("ប្រជុំ ថ្ងៃស្អែក", draft.rawInput)
    }

    @Test
    fun sourceDefaultsToText() {
        assertEquals(CaptureSource.TEXT, parse("coffee tomorrow")!!.source)
    }

    // ---- standalone resolvers ------------------------------------------------

    @Test
    fun resolveDateOnlyRereadsARelativePhrase() {
        assertEquals(LocalDate.of(2026, 8, 13), parser.resolveDateOnly("ថ្ងៃស្អែក", now))
        assertEquals(LocalDate.of(2026, 8, 13), parser.resolveDateOnly("tomorrow", now))
        assertNull(parser.resolveDateOnly("coffee", now))
    }

    @Test
    fun resolveTimeOnlyReadsAClockReading() {
        assertEquals(LocalTime.of(15, 0), parser.resolveTimeOnly("3pm"))
        assertEquals(LocalTime.of(15, 30), parser.resolveTimeOnly("15:30"))
        assertNull(parser.resolveTimeOnly("coffee"))
    }
}
