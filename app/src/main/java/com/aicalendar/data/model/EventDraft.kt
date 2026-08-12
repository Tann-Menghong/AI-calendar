package com.aicalendar.data.model

import java.time.LocalDateTime

/** Where an extracted event came from, so the review sheet can explain itself. */
enum class CaptureSource {
    TEXT,
    PHOTO,
    VOICE,
}

/** Which extraction stage produced a draft. */
enum class ExtractionEngine {
    /** The on-device LLM understood the request. */
    LLM,

    /** The deterministic Khmer/English date grammar produced it (no model installed, or model failed). */
    RULES,

    /** LLM output that was repaired or anchored with the rule parser. */
    HYBRID,
}

/**
 * A candidate event, not yet persisted. The user always reviews these before they
 * become real [CalendarEvent]s.
 */
data class EventDraft(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean = false,
    val location: String? = null,
    val notes: String? = null,
    val reminderMinutes: Int? = 15,
    val engine: ExtractionEngine = ExtractionEngine.RULES,
    val source: CaptureSource = CaptureSource.TEXT,
    /** Raw input the draft was derived from, kept for the "show original" affordance. */
    val rawInput: String = "",
    /**
     * True when no date or time was found in the input and [start] is a guess.
     * The editor highlights these so the user does not silently save a wrong date.
     */
    val dateWasGuessed: Boolean = false,
) {
    val durationMinutes: Long
        get() = java.time.Duration.between(start, end).toMinutes()
}
