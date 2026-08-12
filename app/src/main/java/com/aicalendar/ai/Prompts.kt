package com.aicalendar.ai

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Prompt construction for the on-device model.
 *
 * Two things matter most for a 1–4B model: the current date has to be stated
 * explicitly (small models cannot know it and will invent one), and the output
 * contract has to be shown, not just described. Both examples below are therefore
 * complete input/output pairs, one Khmer and one English.
 */
object Prompts {

    /** Gemma turn markers. Harmless for bundles that also apply their own template. */
    private const val TURN_USER = "<start_of_turn>user\n"
    private const val TURN_END = "<end_of_turn>\n"
    private const val TURN_MODEL = "<start_of_turn>model\n"

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.US)

    private val KHMER_WEEKDAYS = listOf(
        "ថ្ងៃច័ន្ទ", "ថ្ងៃអង្គារ", "ថ្ងៃពុធ", "ថ្ងៃព្រហស្បតិ៍",
        "ថ្ងៃសុក្រ", "ថ្ងៃសៅរ៍", "ថ្ងៃអាទិត្យ",
    )

    fun buildExtractionPrompt(input: String, now: LocalDateTime): String {
        val today = now.format(DATE)
        val weekdayEn = now.format(WEEKDAY)
        val weekdayKm = KHMER_WEEKDAYS[now.dayOfWeek.value - 1]
        val clock = now.format(TIME)

        return buildString {
            append(TURN_USER)
            append(SYSTEM_RULES)
            append('\n')
            append("CURRENT DATE: $today ($weekdayEn / $weekdayKm)\n")
            append("CURRENT TIME: $clock\n")
            append("TOMORROW: ${now.plusDays(1).format(DATE)}\n")
            append("NEXT WEEK STARTS: ${now.plusDays(7).format(DATE)}\n")
            append('\n')
            append(examples(now))
            append('\n')
            append("INPUT:\n")
            append(input.trim())
            append('\n')
            append("JSON:\n")
            append(TURN_END)
            append(TURN_MODEL)
        }
    }

    /**
     * Prompt for a photo handed straight to a vision-capable model, used when the
     * bundle has an image encoder. OCR text is still passed alongside when available,
     * because Khmer glyph recognition is more reliable through Tesseract.
     */
    fun buildImagePrompt(now: LocalDateTime, ocrText: String?): String {
        val today = now.format(DATE)
        return buildString {
            append(TURN_USER)
            append(SYSTEM_RULES)
            append('\n')
            append("CURRENT DATE: $today\n")
            append("CURRENT TIME: ${now.format(TIME)}\n\n")
            append("The image shows an invitation, poster, schedule or handwritten note.\n")
            append("Read every appointment in it and return them all.\n")
            if (!ocrText.isNullOrBlank()) {
                append("\nText already recognised from the image (may contain OCR errors):\n")
                append(ocrText.trim().take(1500))
                append('\n')
            }
            append("\nJSON:\n")
            append(TURN_END)
            append(TURN_MODEL)
        }
    }

    private val SYSTEM_RULES = """
        You turn Khmer (ភាសាខ្មែរ) or English text into calendar events.
        Answer with JSON only. No explanation, no markdown fence.

        Schema:
        {"events":[{"title":string,"date":"YYYY-MM-DD","start":"HH:MM","end":"HH:MM","all_day":boolean,"location":string|null}]}

        Rules:
        - Write the title in the SAME language as the input. Never translate it.
        - Keep the title short: what happens, not when or where.
        - Resolve every relative date (ថ្ងៃស្អែក, សប្តាហ៍ក្រោយ, tomorrow, next Friday) against CURRENT DATE.
        - Use a 24-hour clock. រសៀល/ល្ងាច/យប់ and pm mean 12:00-23:59.
        - Khmer digits ០១២៣៤៥៦៧៨៩ mean 0123456789.
        - If no end time is stated, make the event one hour long.
        - If no time is stated at all, set "all_day": true and omit start/end.
        - Return every distinct appointment you find, in one "events" array.
    """.trimIndent()

    /**
     * The example answers carry dates computed from [now], so the model sees a
     * worked instance of the relative-date arithmetic it is being asked to do.
     */
    private fun examples(now: LocalDateTime): String {
        val tomorrow = now.plusDays(1).format(DATE)
        val nextFriday = now.toLocalDate()
            .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.FRIDAY))
            .format(DATE)
        return """
            INPUT:
            ជួបជាមួយលោកគ្រូ ថ្ងៃស្អែក ម៉ោង ៣ រសៀល នៅសាលា
            JSON:
            {"events":[{"title":"ជួបជាមួយលោកគ្រូ","date":"$tomorrow","start":"15:00","end":"16:00","all_day":false,"location":"សាលា"}]}

            INPUT:
            dentist next friday 9am
            JSON:
            {"events":[{"title":"Dentist","date":"$nextFriday","start":"09:00","end":"10:00","all_day":false,"location":null}]}
        """.trimIndent()
    }
}
