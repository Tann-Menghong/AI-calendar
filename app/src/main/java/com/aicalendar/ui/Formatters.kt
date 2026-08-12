package com.aicalendar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.aicalendar.data.model.CalendarEvent
import com.aicalendar.nlp.KhmerText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Date and time formatting that follows the active locale, including Khmer numerals.
 *
 * `java.time` localises month and weekday names for `km` but still writes Arabic
 * digits, so numbers are converted separately — a Khmer calendar that says "15" where
 * it should say "១៥" looks half-translated.
 */
class Formatters(private val locale: Locale) {

    private val isKhmer = locale.language == "km"

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (isKhmer) "H:mm" else "h:mm a", locale)
    private val dayMonthFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (isKhmer) "d MMMM" else "EEE, d MMM", locale)
    private val fullDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (isKhmer) "EEEE d MMMM y" else "EEEE, d MMMM y", locale)
    private val monthFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMMM y", locale)

    fun digits(text: String): String = if (isKhmer) KhmerText.toKhmerDigits(text) else text

    fun time(time: LocalTime): String = digits(time.format(timeFormatter))

    fun time(dateTime: LocalDateTime): String = time(dateTime.toLocalTime())

    fun dayMonth(date: LocalDate): String = digits(date.format(dayMonthFormatter))

    fun fullDate(date: LocalDate): String = digits(date.format(fullDateFormatter))

    fun monthTitle(month: YearMonth): String = digits(month.atDay(1).format(monthFormatter))

    fun dayOfMonth(date: LocalDate): String = digits(date.dayOfMonth.toString())

    fun weekdayInitial(dayOfWeek: java.time.DayOfWeek): String =
        dayOfWeek.getDisplayName(TextStyle.NARROW, locale)

    /** "3:00 PM – 4:00 PM", or the all-day label the caller supplies. */
    fun eventTimeRange(event: CalendarEvent, allDayLabel: String): String = when {
        event.allDay -> allDayLabel
        else -> "${time(event.startDateTime())} – ${time(event.endDateTime())}"
    }

    fun duration(minutes: Long, hourLabel: String, minuteLabel: String): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return buildString {
            if (hours > 0) append("${digits(hours.toString())} $hourLabel")
            if (mins > 0) {
                if (isNotEmpty()) append(" ")
                append("${digits(mins.toString())} $minuteLabel")
            }
            if (isEmpty()) append("${digits("0")} $minuteLabel")
        }
    }
}

@Composable
fun rememberFormatters(): Formatters {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales.get(0) ?: Locale.getDefault()
    return remember(locale) { Formatters(locale) }
}
