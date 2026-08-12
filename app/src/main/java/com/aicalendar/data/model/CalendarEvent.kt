package com.aicalendar.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A saved event.
 *
 * Times are persisted as epoch milliseconds so range queries and alarm scheduling
 * are exact; the UI converts to local time at the edges.
 */
@Entity(
    tableName = "events",
    indices = [Index("startEpochMillis"), Index("endEpochMillis")],
)
data class CalendarEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val allDay: Boolean = false,
    val location: String? = null,
    val notes: String? = null,
    /** Minutes before start to fire a notification; null means no reminder. */
    val reminderMinutes: Int? = 15,
    @ColumnInfo(defaultValue = "0") val colorIndex: Int = 0,
    /** How this event was captured, for the "created from voice/photo" hint. */
    val source: CaptureSource = CaptureSource.TEXT,
    /** Which extractor produced it, useful when tuning prompts. */
    val engine: ExtractionEngine = ExtractionEngine.RULES,
    /** Original text/transcript, kept so the user can see what was heard or read. */
    val rawInput: String? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    fun startDateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(startEpochMillis), zone)

    fun endDateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(endEpochMillis), zone)

    /** Epoch millis at which the reminder should fire, or null when there is none. */
    fun reminderAtEpochMillis(): Long? =
        reminderMinutes?.let { startEpochMillis - it * 60_000L }

    companion object {
        fun fromDraft(draft: EventDraft, zone: ZoneId = ZoneId.systemDefault()): CalendarEvent =
            CalendarEvent(
                title = draft.title,
                startEpochMillis = draft.start.atZone(zone).toInstant().toEpochMilli(),
                endEpochMillis = draft.end.atZone(zone).toInstant().toEpochMilli(),
                allDay = draft.allDay,
                location = draft.location,
                notes = draft.notes,
                reminderMinutes = draft.reminderMinutes,
                source = draft.source,
                engine = draft.engine,
                rawInput = draft.rawInput.ifBlank { null },
            )
    }
}
