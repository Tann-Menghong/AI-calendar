package com.aicalendar.nlp

import com.aicalendar.data.model.CaptureSource
import com.aicalendar.data.model.EventDraft
import com.aicalendar.data.model.ExtractionEngine
import com.aicalendar.nlp.DateTimeLexicon.DayPart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Deterministic Khmer + English event grammar.
 *
 * This is the floor the app stands on: it runs with no model downloaded, costs
 * nothing, and never hallucinates a date. The on-device LLM is layered on top for
 * phrasing this grammar cannot reach (see `ai/EventExtractor`), but every LLM answer
 * is still anchored against these results.
 *
 * The parser works over a length-preserving normalisation of the input and records
 * which character ranges were spent on date/time/location, so whatever is left over
 * becomes the event title.
 */
class RuleBasedEventParser(
    private val defaultDurationMinutes: Long = 60L,
    /** Hour used when a date is known but the phrase is not an all-day one. */
    private val defaultReminderMinutes: Int = 15,
) {

    // ---- public API ---------------------------------------------------------

    fun parse(
        rawInput: String,
        now: LocalDateTime,
        source: CaptureSource = CaptureSource.TEXT,
    ): EventDraft? {
        val original = rawInput.trim()
        if (original.isBlank()) return null
        val scan = Scan(KhmerText.normalize(original))

        val reminderMinutes = matchReminder(scan)
        val durationMinutes = matchDuration(scan)
        val times = matchTimes(scan)
        val date = matchDate(scan, now.toLocalDate())
        val location = matchLocation(scan, original)
        val title = buildTitle(original, scan)

        val allDay = date != null && times == null
        val startDateTime: LocalDateTime
        var dateWasGuessed = false
        when {
            date != null && times != null -> startDateTime = date.atTime(times.start)
            date != null -> startDateTime = date.atStartOfDay()
            times != null -> {
                // A bare time means the next occurrence of that clock reading.
                val todayAt = now.toLocalDate().atTime(times.start)
                startDateTime = if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
            }
            else -> {
                startDateTime = roundUpToNextHalfHour(now)
                dateWasGuessed = true
            }
        }

        val endDateTime = when {
            allDay -> startDateTime.toLocalDate().atTime(23, 59)
            times?.end != null -> {
                val candidate = startDateTime.toLocalDate().atTime(times.end)
                // "9pm to 1am" wraps past midnight.
                if (candidate.isAfter(startDateTime)) candidate else candidate.plusDays(1)
            }
            durationMinutes != null -> startDateTime.plusMinutes(durationMinutes)
            else -> startDateTime.plusMinutes(defaultDurationMinutes)
        }

        val resolvedTitle = title.ifBlank {
            if (KhmerText.containsKhmer(original)) {
                DateTimeLexicon.defaultTitleKhmer
            } else {
                DateTimeLexicon.defaultTitleEnglish
            }
        }

        return EventDraft(
            title = resolvedTitle,
            start = startDateTime,
            end = endDateTime,
            allDay = allDay,
            location = location,
            notes = null,
            reminderMinutes = reminderMinutes ?: defaultReminderMinutes,
            engine = ExtractionEngine.RULES,
            source = source,
            rawInput = original,
            dateWasGuessed = dateWasGuessed,
        )
    }

    /**
     * Re-resolves a relative date phrase against [now] without building a whole draft.
     * Used to correct LLM output that repeated "tomorrow" back as a literal date.
     */
    fun resolveDateOnly(rawInput: String, now: LocalDateTime): LocalDate? =
        matchDate(Scan(KhmerText.normalize(rawInput)), now.toLocalDate())

    fun resolveTimeOnly(rawInput: String): LocalTime? =
        matchTimes(Scan(KhmerText.normalize(rawInput)))?.start

    // ---- scanning primitives ------------------------------------------------

    /** Normalised text plus a record of which characters have been claimed. */
    private class Scan(val text: String) {
        val consumed = BooleanArray(text.length)

        fun claim(from: Int, untilExclusive: Int) {
            for (i in from.coerceAtLeast(0) until untilExclusive.coerceAtMost(text.length)) {
                consumed[i] = true
            }
        }

        fun isFree(from: Int, untilExclusive: Int): Boolean {
            for (i in from.coerceAtLeast(0) until untilExclusive.coerceAtMost(text.length)) {
                if (consumed[i]) return false
            }
            return true
        }

        /** First unconsumed occurrence of [needle], or -1. */
        fun indexOfFree(needle: String, from: Int = 0): Int {
            if (needle.isEmpty()) return -1
            var i = text.indexOf(needle, from)
            while (i >= 0) {
                if (isFree(i, i + needle.length)) return i
                i = text.indexOf(needle, i + 1)
            }
            return -1
        }

        fun freeMatches(regex: Regex): List<MatchResult> =
            regex.findAll(text).filter { isFree(it.range.first, it.range.last + 1) }.toList()
    }

    private data class TimeMatch(
        val start: LocalTime,
        val end: LocalTime?,
    )

    private data class TimeCandidate(
        val range: IntRange,
        val hour: Int,
        val minute: Int,
        val explicitDayPart: DayPart?,
        /** True for 24-hour readings like 15:30, which must not be shifted. */
        val unambiguous: Boolean,
    )

    // ---- time ---------------------------------------------------------------

    private fun matchTimes(scan: Scan): TimeMatch? {
        val candidates = collectTimeCandidates(scan).sortedBy { it.range.first }
        if (candidates.isEmpty()) return matchBareDayPart(scan)

        val first = candidates.first()
        val second = candidates.getOrNull(1)

        // A range only counts when the two readings are joined by "to"/"ដល់"/"-".
        if (second != null && isRangeSeparator(scan.text, first.range.last + 1, second.range.first)) {
            var startPart = first.explicitDayPart
            val endPart = second.explicitDayPart
            // "2 to 4pm" — the trailing marker governs both halves.
            if (startPart == null && endPart != null) startPart = endPart
            val startTime = toLocalTime(first.hour, first.minute, startPart, first.unambiguous)
            val endTime = toLocalTime(second.hour, second.minute, endPart ?: startPart, second.unambiguous)
            scan.claim(first.range.first, second.range.last + 1)
            return TimeMatch(startTime, endTime)
        }

        val startTime = toLocalTime(first.hour, first.minute, first.explicitDayPart, first.unambiguous)
        scan.claim(first.range.first, first.range.last + 1)
        // A day-part word sitting next to the reading is part of the phrase, not the title.
        claimAdjacentDayPart(scan, first.range)
        return TimeMatch(startTime, null)
    }

    private fun collectTimeCandidates(scan: Scan): List<TimeCandidate> {
        val out = mutableListOf<TimeCandidate>()
        val taken = mutableListOf<IntRange>()

        fun add(candidate: TimeCandidate) {
            if (taken.any { it.first <= candidate.range.last && candidate.range.first <= it.last }) return
            taken += candidate.range
            out += candidate
        }

        // English "3pm", "3:30 p.m."
        for (m in scan.freeMatches(EN_TIME_MERIDIEM)) {
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) continue
            val part = if (m.groupValues[3].startsWith("a")) DayPart.MORNING else DayPart.AFTERNOON
            add(TimeCandidate(m.range, hour, minute, part, unambiguous = true))
        }
        // Khmer "ម៉ោង ៣", "ម៉ោង ៣:៣០", "ម៉ោង ៣ កន្លះ"
        for (m in scan.freeMatches(KH_TIME)) {
            val hour = m.groupValues[1].toInt()
            if (hour !in 0..23) continue
            var minute = m.groupValues[2].toIntOrNull() ?: 0
            if (minute !in 0..59) minute = 0
            var end = m.range.last
            val half = matchAt(scan.text, end + 1, DateTimeLexicon.halfMarker, skipSpaces = true)
            if (half != null && minute == 0) {
                minute = 30
                end = half.last
            }
            add(
                TimeCandidate(
                    range = m.range.first..end,
                    hour = hour,
                    minute = minute,
                    explicitDayPart = nearbyDayPart(scan.text, m.range),
                    unambiguous = false,
                ),
            )
        }
        // Khmer "ម៉ោង បី" (spelled-out hour)
        for (m in scan.freeMatches(KH_TIME_WORD)) {
            val word = m.groupValues[1]
            val hour = DateTimeLexicon.khmerNumberWords[word] ?: continue
            var minute = 0
            var end = m.range.last
            val half = matchAt(scan.text, end + 1, DateTimeLexicon.halfMarker, skipSpaces = true)
            if (half != null) {
                minute = 30
                end = half.last
            }
            add(
                TimeCandidate(
                    range = m.range.first..end,
                    hour = hour,
                    minute = minute,
                    explicitDayPart = nearbyDayPart(scan.text, m.range),
                    unambiguous = false,
                ),
            )
        }
        // 24-hour "15:30"
        for (m in scan.freeMatches(EN_TIME_24)) {
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            if (hour !in 0..23 || minute !in 0..59) continue
            add(
                TimeCandidate(
                    m.range,
                    hour,
                    minute,
                    nearbyDayPart(scan.text, m.range),
                    unambiguous = hour > 12 || hour == 0,
                ),
            )
        }
        // English "at 4"
        for (m in scan.freeMatches(EN_TIME_AT)) {
            val hour = m.groupValues[1].toInt()
            if (hour !in 0..23) continue
            add(TimeCandidate(m.range, hour, 0, nearbyDayPart(scan.text, m.range), unambiguous = false))
        }
        return out
    }

    /** "tomorrow morning" with no clock reading still pins a usable hour. */
    private fun matchBareDayPart(scan: Scan): TimeMatch? {
        val hit = longestLexiconHit(scan, DateTimeLexicon.dayParts) ?: return null
        // "am"/"pm" on their own are noise, not a time.
        if (hit.key == "am" || hit.key == "pm") return null
        val hour = when (hit.value) {
            DayPart.MORNING -> 9
            DayPart.NOON -> 12
            DayPart.AFTERNOON -> 14
            DayPart.EVENING -> 18
            DayPart.NIGHT -> 20
            DayPart.MIDNIGHT -> 0
        }
        scan.claim(hit.range.first, hit.range.last + 1)
        return TimeMatch(LocalTime.of(hour, 0), null)
    }

    private fun toLocalTime(hour: Int, minute: Int, part: DayPart?, unambiguous: Boolean): LocalTime {
        var h = hour
        when (part) {
            DayPart.MORNING -> if (h == 12) h = 0
            DayPart.NOON -> if (h == 12 || h == 0) h = 12
            DayPart.AFTERNOON, DayPart.EVENING -> if (h in 1..11) h += 12
            DayPart.NIGHT -> if (h in 1..11) h += 12 else if (h == 12) h = 0
            DayPart.MIDNIGHT -> h = 0
            null -> {
                // No marker at all: business hours are the safe reading. 1–7 means
                // afternoon/evening, 8–12 means morning. 13+ is already unambiguous.
                if (!unambiguous && h in 1..7) h += 12
            }
        }
        return LocalTime.of(h % 24, minute)
    }

    private fun nearbyDayPart(text: String, range: IntRange): DayPart? {
        val after = text.substring(
            (range.last + 1).coerceAtMost(text.length),
            (range.last + 1 + DAY_PART_WINDOW).coerceAtMost(text.length),
        )
        findDayPart(after)?.let { return it }
        val before = text.substring(
            (range.first - DAY_PART_WINDOW).coerceAtLeast(0),
            range.first.coerceAtLeast(0),
        )
        return findDayPart(before)
    }

    private fun findDayPart(window: String): DayPart? =
        DateTimeLexicon.dayParts.entries
            .filter { it.key.length > 2 || window.contains(it.key) }
            .sortedByDescending { it.key.length }
            .firstOrNull { window.contains(it.key) }
            ?.value

    private fun claimAdjacentDayPart(scan: Scan, range: IntRange) {
        for ((word, _) in DateTimeLexicon.dayParts.entries.sortedByDescending { it.key.length }) {
            if (word.length < 3) continue
            val after = matchAt(scan.text, range.last + 1, word, skipSpaces = true)
            if (after != null) {
                scan.claim(after.first, after.last + 1)
                return
            }
            val start = range.first - word.length
            if (start >= 0 && scan.text.startsWith(word, start) && scan.isFree(start, range.first)) {
                scan.claim(start, range.first)
                return
            }
            val spaced = range.first - word.length - 1
            if (spaced >= 0 && scan.text.startsWith("$word ", spaced) && scan.isFree(spaced, range.first)) {
                scan.claim(spaced, range.first)
                return
            }
        }
    }

    private fun isRangeSeparator(text: String, from: Int, untilExclusive: Int): Boolean {
        if (from > untilExclusive) return false
        val between = text.substring(from.coerceIn(0, text.length), untilExclusive.coerceIn(0, text.length))
        val trimmed = between.trim()
        if (trimmed.isEmpty()) return false
        val stripped = trimmed.removePrefix(DateTimeLexicon.hourMarker).trim()
        return DateTimeLexicon.untilMarkers.any { stripped == it || trimmed == it }
    }

    // ---- date ---------------------------------------------------------------

    private fun matchDate(scan: Scan, today: LocalDate): LocalDate? {
        explicitKhmerDate(scan, today)?.let { return it }
        explicitEnglishDate(scan, today)?.let { return it }
        numericDate(scan, today)?.let { return it }
        relativeKeywordDate(scan, today)?.let { return it }
        weekdayDate(scan, today)?.let { return it }
        offsetUnitDate(scan, today)?.let { return it }
        return null
    }

    /** "ថ្ងៃទី ១៥ ខែ សីហា ឆ្នាំ ២០២៦" and its shorter forms. */
    private fun explicitKhmerDate(scan: Scan, today: LocalDate): LocalDate? {
        val dayIdx = scan.indexOfFree(DateTimeLexicon.dayNumberMarker)
        if (dayIdx < 0) return null
        val dayMatch = KH_DAY_NUMBER.find(scan.text, dayIdx) ?: return null
        if (dayMatch.range.first != dayIdx) return null
        val day = dayMatch.groupValues[1].toIntOrNull() ?: return null
        if (day !in 1..31) return null

        var end = dayMatch.range.last + 1
        var month = today.monthValue
        var monthGiven = false
        var year = today.year
        var yearGiven = false

        val monthPart = KH_MONTH_PART.find(scan.text.substring(end).let { it })
        if (monthPart != null && monthPart.range.first == 0) {
            val name = monthPart.groupValues[1].trim()
            val number = monthPart.groupValues[2].toIntOrNull()
            val resolved = DateTimeLexicon.months[name]?.value ?: number
            if (resolved != null && resolved in 1..12) {
                month = resolved
                monthGiven = true
                end += monthPart.range.last + 1
            }
        }
        val yearPart = KH_YEAR_PART.find(scan.text.substring(end))
        if (yearPart != null && yearPart.range.first == 0) {
            yearPart.groupValues[1].toIntOrNull()?.let {
                year = it
                yearGiven = true
                end += yearPart.range.last + 1
            }
        }

        val candidate = safeDate(year, month, day) ?: return null
        scan.claim(dayIdx, end)
        return rollForward(candidate, today, monthGiven, yearGiven)
    }

    /** "5 August", "August 5", "Aug 5 2026". */
    private fun explicitEnglishDate(scan: Scan, today: LocalDate): LocalDate? {
        for (m in scan.freeMatches(EN_MONTH_DAY)) {
            val month = DateTimeLexicon.months[m.groupValues[1]] ?: continue
            val day = m.groupValues[2].toIntOrNull() ?: continue
            val year = m.groupValues[3].toIntOrNull()
            val candidate = safeDate(year ?: today.year, month.value, day) ?: continue
            scan.claim(m.range.first, m.range.last + 1)
            return rollForward(candidate, today, monthGiven = true, yearGiven = year != null)
        }
        for (m in scan.freeMatches(EN_DAY_MONTH)) {
            val day = m.groupValues[1].toIntOrNull() ?: continue
            val month = DateTimeLexicon.months[m.groupValues[2]] ?: continue
            val year = m.groupValues[3].toIntOrNull()
            val candidate = safeDate(year ?: today.year, month.value, day) ?: continue
            scan.claim(m.range.first, m.range.last + 1)
            return rollForward(candidate, today, monthGiven = true, yearGiven = year != null)
        }
        // "ខែ សីហា" with no day number: the first of that month.
        val monthIdx = scan.indexOfFree(DateTimeLexicon.monthMarker)
        if (monthIdx >= 0) {
            val rest = scan.text.substring(monthIdx + DateTimeLexicon.monthMarker.length)
            val name = DateTimeLexicon.months.entries
                .filter { it.key.any { c -> KhmerText.isKhmer(c) } }
                .sortedByDescending { it.key.length }
                .firstOrNull { rest.trimStart().startsWith(it.key) }
            if (name != null) {
                val leading = rest.length - rest.trimStart().length
                val end = monthIdx + DateTimeLexicon.monthMarker.length + leading + name.key.length
                val candidate = safeDate(today.year, name.value.value, 1) ?: return null
                scan.claim(monthIdx, end)
                return rollForward(candidate, today, monthGiven = true, yearGiven = false)
            }
        }
        return null
    }

    /** "15/08", "15-08-2026". Day-first, which is the convention in Cambodia. */
    private fun numericDate(scan: Scan, today: LocalDate): LocalDate? {
        for (m in scan.freeMatches(NUMERIC_DATE)) {
            val day = m.groupValues[1].toIntOrNull() ?: continue
            val month = m.groupValues[2].toIntOrNull() ?: continue
            if (day !in 1..31 || month !in 1..12) continue
            val rawYear = m.groupValues[3].toIntOrNull()
            val year = when {
                rawYear == null -> today.year
                rawYear < 100 -> 2000 + rawYear
                else -> rawYear
            }
            val candidate = safeDate(year, month, day) ?: continue
            scan.claim(m.range.first, m.range.last + 1)
            return rollForward(candidate, today, monthGiven = true, yearGiven = rawYear != null)
        }
        return null
    }

    private fun relativeKeywordDate(scan: Scan, today: LocalDate): LocalDate? {
        val hit = longestLexiconHit(scan, DateTimeLexicon.relativeDays) ?: return null
        scan.claim(hit.range.first, hit.range.last + 1)
        return today.plusDays(hit.value)
    }

    private fun weekdayDate(scan: Scan, today: LocalDate): LocalDate? {
        val hit = longestLexiconHit(scan, DateTimeLexicon.weekdays) ?: return null
        // Bare "អាទិត្យ" means "week" unless ថ្ងៃ marks it as Sunday.
        if (hit.value == DayOfWeek.SUNDAY && hit.key == KhmerText.normalize("អាទិត្យ")) {
            val prefixStart = hit.range.first - DateTimeLexicon.dayMarker.length
            val markedAsDay = prefixStart >= 0 &&
                scan.text.startsWith(DateTimeLexicon.dayMarker, prefixStart)
            if (!markedAsDay) return null
        }
        var start = hit.range.first
        var end = hit.range.last + 1
        // Absorb the ថ្ងៃ prefix so it does not leak into the title.
        val prefixStart = start - DateTimeLexicon.dayMarker.length
        if (prefixStart >= 0 && scan.text.startsWith(DateTimeLexicon.dayMarker, prefixStart)) {
            start = prefixStart
        }

        var next = false
        var last = false
        for (marker in DateTimeLexicon.nextMarkers) {
            val trailing = matchAt(scan.text, end, marker, skipSpaces = true)
            if (trailing != null) {
                next = true
                end = trailing.last + 1
                break
            }
            if (startsBefore(scan.text, start, marker)) {
                next = true
                // Claim the leading marker as well, or "next" survives into the title.
                start -= marker.length + 1
                break
            }
        }
        if (!next) {
            for (marker in DateTimeLexicon.lastMarkers) {
                val trailing = matchAt(scan.text, end, marker, skipSpaces = true)
                if (trailing != null) {
                    last = true
                    end = trailing.last + 1
                    break
                }
                if (startsBefore(scan.text, start, marker)) {
                    last = true
                    start -= marker.length + 1
                    break
                }
            }
        }

        scan.claim(start, end)
        val target = hit.value
        return when {
            last -> today.with(TemporalAdjusters.previous(target))
            next -> today.with(TemporalAdjusters.next(target)).let {
                // "next Monday" said on a Sunday should skip the immediate one.
                if (today.dayOfWeek == target) today.plusWeeks(1) else it
            }
            today.dayOfWeek == target -> today
            else -> today.with(TemporalAdjusters.next(target))
        }
    }

    /** "សប្តាហ៍ក្រោយ", "ខែក្រោយ", "in 3 days", "ក្នុង ២ សប្តាហ៍". */
    private fun offsetUnitDate(scan: Scan, today: LocalDate): LocalDate? {
        for (m in scan.freeMatches(EN_IN_N_UNITS)) {
            val n = m.groupValues[1].toLongOrNull() ?: continue
            val unit = m.groupValues[2]
            val result = when {
                unit.startsWith("day") -> today.plusDays(n)
                unit.startsWith("week") -> today.plusWeeks(n)
                unit.startsWith("month") -> today.plusMonths(n)
                else -> continue
            }
            scan.claim(m.range.first, m.range.last + 1)
            return result
        }
        for (m in scan.freeMatches(KH_IN_N_UNITS)) {
            val n = m.groupValues[1].toLongOrNull() ?: continue
            val unit = m.groupValues[2]
            val result = when {
                unit == DateTimeLexicon.dayMarker -> today.plusDays(n)
                unit in DateTimeLexicon.weekMarkers -> today.plusWeeks(n)
                unit == DateTimeLexicon.monthMarker -> today.plusMonths(n)
                else -> continue
            }
            scan.claim(m.range.first, m.range.last + 1)
            return result
        }
        val units = DateTimeLexicon.weekMarkers.map { it to 7L } + listOf(DateTimeLexicon.monthMarker to 0L)
        for ((unit, days) in units) {
            val idx = scan.indexOfFree(unit)
            if (idx < 0) continue
            val end = idx + unit.length
            for (marker in DateTimeLexicon.nextMarkers) {
                val m = matchAt(scan.text, end, marker, skipSpaces = true) ?: continue
                scan.claim(idx, m.last + 1)
                return if (days > 0) today.plusDays(days) else today.plusMonths(1)
            }
            for (marker in DateTimeLexicon.lastMarkers) {
                val m = matchAt(scan.text, end, marker, skipSpaces = true) ?: continue
                scan.claim(idx, m.last + 1)
                return if (days > 0) today.minusDays(days) else today.minusMonths(1)
            }
        }
        return null
    }

    /**
     * A date with no year (or no month) is assumed to mean the next such date,
     * so "ថ្ងៃទី ២" on the 20th means next month, not three weeks ago.
     */
    private fun rollForward(
        candidate: LocalDate,
        today: LocalDate,
        monthGiven: Boolean,
        yearGiven: Boolean,
    ): LocalDate = when {
        !candidate.isBefore(today) -> candidate
        yearGiven -> candidate
        monthGiven -> candidate.plusYears(1)
        else -> candidate.plusMonths(1)
    }

    // ---- duration, reminder, location --------------------------------------

    private fun matchDuration(scan: Scan): Long? {
        for (m in scan.freeMatches(EN_DURATION)) {
            val n = m.groupValues[1].toLongOrNull() ?: continue
            val unit = m.groupValues[2]
            val minutes = if (unit.startsWith("h")) n * 60 else n
            scan.claim(m.range.first, m.range.last + 1)
            return minutes
        }
        for (m in scan.freeMatches(KH_DURATION)) {
            val n = m.groupValues[1].toLongOrNull() ?: continue
            val unit = m.groupValues[2]
            val minutes = if (unit == DateTimeLexicon.hourMarker) n * 60 else n
            scan.claim(m.range.first, m.range.last + 1)
            return minutes
        }
        return null
    }

    private fun matchReminder(scan: Scan): Int? {
        for (m in scan.freeMatches(EN_REMINDER)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            val unit = m.groupValues[2]
            val minutes = if (unit.startsWith("h")) n * 60 else n
            scan.claim(m.range.first, m.range.last + 1)
            return minutes
        }
        for (m in scan.freeMatches(KH_REMINDER)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            val unit = m.groupValues[2]
            val minutes = if (unit == DateTimeLexicon.hourMarker) n * 60 else n
            scan.claim(m.range.first, m.range.last + 1)
            return minutes
        }
        return null
    }

    /**
     * Location is whatever follows នៅ / at / in, up to the next claimed span or
     * sentence break. Time phrases like "at 3pm" are already consumed by then,
     * so the marker cannot swallow them.
     */
    private fun matchLocation(scan: Scan, original: String): String? {
        for (marker in DateTimeLexicon.locationMarkers) {
            var searchFrom = 0
            while (true) {
                val idx = scan.indexOfFree(marker, searchFrom)
                if (idx < 0) break
                searchFrom = idx + marker.length
                // Latin markers must stand as whole words.
                if (marker.all { it.code < 0x80 }) {
                    val before = scan.text.getOrNull(idx - 1)
                    val after = scan.text.getOrNull(idx + marker.length)
                    if (before != null && before.isLetter()) continue
                    if (after != null && after.isLetter()) continue
                }
                var cursor = idx + marker.length
                while (cursor < scan.text.length && scan.text[cursor] == ' ') cursor++
                val start = cursor
                while (cursor < scan.text.length &&
                    !scan.consumed[cursor] &&
                    scan.text[cursor] !in LOCATION_STOPPERS
                ) {
                    cursor++
                }
                val value = original.substring(start.coerceAtMost(original.length), cursor.coerceAtMost(original.length)).trim()
                if (value.isEmpty() || value.first().isDigit()) continue
                if (KhmerText.normalize(value) in DateTimeLexicon.titleFillers) continue
                scan.claim(idx, start + value.length)
                return value
            }
        }
        return null
    }

    // ---- title --------------------------------------------------------------

    private fun buildTitle(original: String, scan: Scan): String {
        val sb = StringBuilder()
        for (i in original.indices) {
            if (i < scan.consumed.size && scan.consumed[i]) {
                sb.append(' ')
            } else {
                sb.append(original[i])
            }
        }
        val words = sb.toString()
            .split(Regex("[\\s,;:.!?\"'()\\[\\]/|]+"))
            .filter { it.isNotBlank() }
            .toMutableList()

        // Trim filler words from both ends only; a filler in the middle is content.
        while (words.isNotEmpty() && KhmerText.normalize(words.first()) in DateTimeLexicon.titleFillers) {
            words.removeAt(0)
        }
        while (words.isNotEmpty() && KhmerText.normalize(words.last()) in DateTimeLexicon.titleFillers) {
            words.removeAt(words.size - 1)
        }
        return words.joinToString(" ").trim().trim('-', '–', '—').trim()
    }

    // ---- small helpers ------------------------------------------------------

    private class LexiconHit<V>(val key: String, val value: V, val range: IntRange)

    /** Longest-first lookup, so ថ្ងៃស្អែក beats ថ្ងៃ and "thursday" beats "thu". */
    private fun <V> longestLexiconHit(scan: Scan, table: Map<String, V>): LexiconHit<V>? {
        var best: LexiconHit<V>? = null
        for ((key, value) in table) {
            val idx = scan.indexOfFree(key)
            if (idx < 0) continue
            // Latin keys must match whole words; Khmer is written without spaces.
            if (key.all { it.code < 0x80 }) {
                val before = scan.text.getOrNull(idx - 1)
                val after = scan.text.getOrNull(idx + key.length)
                if (before != null && (before.isLetterOrDigit())) continue
                if (after != null && (after.isLetterOrDigit())) continue
            }
            if (best == null || key.length > best.key.length) {
                best = LexiconHit(key, value, idx until (idx + key.length))
            }
        }
        return best
    }

    /** Matches [needle] at [index], optionally skipping leading spaces. */
    private fun matchAt(text: String, index: Int, needle: String, skipSpaces: Boolean): IntRange? {
        var i = index
        if (skipSpaces) while (i < text.length && text[i] == ' ') i++
        if (i >= text.length || !text.startsWith(needle, i)) return null
        return i until (i + needle.length)
    }

    /** True when [needle] plus a space sits immediately before [index] as a whole word. */
    private fun startsBefore(text: String, index: Int, needle: String): Boolean {
        val start = index - needle.length - 1
        if (start < 0) return false
        if (!text.startsWith("$needle ", start)) return false
        val preceding = text.getOrNull(start - 1)
        return preceding == null || !preceding.isLetterOrDigit()
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (e: java.time.DateTimeException) {
        null
    }

    private fun roundUpToNextHalfHour(now: LocalDateTime): LocalDateTime {
        val truncated = now.withSecond(0).withNano(0)
        return when {
            truncated.minute == 0 || truncated.minute == 30 -> truncated.plusMinutes(30)
            truncated.minute < 30 -> truncated.withMinute(30)
            else -> truncated.plusHours(1).withMinute(0)
        }
    }

    private companion object {
        const val DAY_PART_WINDOW = 14
        val LOCATION_STOPPERS = charArrayOf(',', '.', ';', '!', '?', '\n')

        val EN_TIME_MERIDIEM = Regex("""(?<![\d:])(\d{1,2})(?::(\d{2}))?\s*([ap])\.?m\.?(?![a-z])""")
        val EN_TIME_24 = Regex("""(?<![\d:])(\d{1,2}):(\d{2})(?![\d:])""")
        val EN_TIME_AT = Regex("""\bat\s+(\d{1,2})(?![\d:])""")
        val KH_TIME = Regex("""ម៉ោង\s*(\d{1,2})(?:\s*[:.]\s*(\d{1,2}))?(?:\s*នាទី)?""")
        val KH_TIME_WORD = Regex("""ម៉ោង\s*(ដប់ពីរ|ដប់មួយ|ប្រាំបួន|ប្រាំពីរ|ប្រាំបី|ប្រាំមួយ|ប្រាំ|ដប់|មួយ|ពីរ|បី|បួន)""")

        val KH_DAY_NUMBER = Regex("""ថ្ងៃទី\s*(\d{1,2})""")
        val KH_MONTH_PART = Regex("""\s*(?:ខែ\s*)?(មករា|កុម្ភៈ|កុម្ភះ|មីនា|មេសា|ឧសភា|មិថុនា|កក្កដា|សីហា|កញ្ញា|តុលា|វិច្ឆិកា|ធ្នូ)|\s*ខែ\s*(\d{1,2})""")
        val KH_YEAR_PART = Regex("""\s*ឆ្នាំ\s*(\d{4})""")

        // Alternatives are ordered longest-first on purpose: regex alternation is
        // ordered, so "aug" listed before "august" would match "5 august" as "5 aug"
        // and leave "ust" behind in the event title.
        val EN_MONTH_DAY = Regex(
            """\b(january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec)\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?""",
        )
        val EN_DAY_MONTH = Regex(
            """\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?(january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec)\.?(?:,?\s+(\d{4}))?""",
        )
        val NUMERIC_DATE = Regex("""(?<![\d/-])(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?(?![\d/-])""")

        val EN_DURATION = Regex("""\bfor\s+(\d{1,3})\s*(hours?|hrs?|h|minutes?|mins?|m)\b""")
        val KH_DURATION = Regex("""រយៈពេល\s*(\d{1,3})\s*(ម៉ោង|នាទី)""")
        val EN_REMINDER = Regex(
            """\b(?:remind(?:\s+me)?|reminder|alert)\s*(?:me\s*)?(\d{1,3})\s*(hours?|hrs?|h|minutes?|mins?|m)\s*(?:before|ahead|early)?""",
        )
        val KH_REMINDER = Regex("""(?:រំលឹក|ជូនដំណឹង)\s*(?:មុន\s*)?(\d{1,3})\s*(ម៉ោង|នាទី)\s*(?:មុន)?""")

        val EN_IN_N_UNITS = Regex("""\bin\s+(\d{1,3})\s+(days?|weeks?|months?)\b""")
        val KH_IN_N_UNITS = Regex("""(?:ក្នុងរយៈពេល|ក្នុង)?\s*(\d{1,3})\s*(ថ្ងៃ|សប្តាហ៍|សប្ដាហ៍|ខែ)\s*(?:ទៀត|ក្រោយ)""")
    }
}
