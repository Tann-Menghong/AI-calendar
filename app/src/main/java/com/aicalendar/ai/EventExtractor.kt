package com.aicalendar.ai

import android.graphics.Bitmap
import com.aicalendar.data.model.CaptureSource
import com.aicalendar.data.model.EventDraft
import com.aicalendar.data.model.ExtractionEngine
import com.aicalendar.nlp.KhmerText
import com.aicalendar.nlp.RuleBasedEventParser
import com.aicalendar.util.MiniJson
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** What the capture screen gets back, including why it looks the way it does. */
data class ExtractionResult(
    val drafts: List<EventDraft>,
    val engine: ExtractionEngine,
    /** Non-fatal explanation to show under the results, already localised by the caller. */
    val notice: Notice? = null,
) {
    enum class Notice {
        /** No model installed, so only the built-in grammar ran. */
        MODEL_NOT_INSTALLED,

        /** The model was installed but errored; results come from the grammar. */
        MODEL_FAILED,

        /** Nothing in the input looked like a date, so the date shown is a guess. */
        DATE_GUESSED,
    }
}

/**
 * Turns free text (typed, spoken or read off a photo) into event drafts.
 *
 * The model and the grammar are combined rather than raced. Small models are good at
 * *what* an event is — the title, the place, splitting a poster into three separate
 * appointments — and unreliable at *when*, because relative-date arithmetic is exactly
 * what they get wrong. So when the grammar found a concrete date or time in the input,
 * that value wins; the model supplies the semantics around it.
 */
class EventExtractor(
    private val llm: LlmEngine,
    private val rules: RuleBasedEventParser = RuleBasedEventParser(),
) {

    suspend fun extract(
        input: String,
        now: LocalDateTime = LocalDateTime.now(),
        source: CaptureSource = CaptureSource.TEXT,
        useLlm: Boolean = true,
    ): ExtractionResult {
        val text = input.trim()
        if (text.isBlank()) return ExtractionResult(emptyList(), ExtractionEngine.RULES)

        val ruleDraft = rules.parse(text, now, source)
        val modelReady = llm.state.value is LlmState.Ready

        if (!useLlm || !modelReady) {
            return rulesOnly(
                ruleDraft,
                if (modelReady) null else ExtractionResult.Notice.MODEL_NOT_INSTALLED,
            )
        }

        val response = llm.generate(Prompts.buildExtractionPrompt(text, now))
        val raw = response.getOrNull()
        if (raw.isNullOrBlank()) {
            return rulesOnly(ruleDraft, ExtractionResult.Notice.MODEL_FAILED)
        }

        val parsed = parseDrafts(raw, now, source, text)
        if (parsed.isEmpty()) {
            return rulesOnly(ruleDraft, ExtractionResult.Notice.MODEL_FAILED)
        }

        return ExtractionResult(
            drafts = reconcile(parsed, ruleDraft),
            engine = if (ruleDraft == null) ExtractionEngine.LLM else ExtractionEngine.HYBRID,
        )
    }

    /**
     * Photo path. OCR text is the primary signal; when the loaded bundle has a vision
     * encoder the image itself is passed too, which recovers layout the OCR flattened.
     */
    suspend fun extractFromImage(
        image: Bitmap,
        ocrText: String,
        now: LocalDateTime = LocalDateTime.now(),
        useLlm: Boolean = true,
    ): ExtractionResult {
        val visionReady = (llm.state.value as? LlmState.Ready)?.supportsVision == true
        if (useLlm && visionReady) {
            val response = llm.generateFromImage(Prompts.buildImagePrompt(now, ocrText), image)
            val raw = response.getOrNull()
            if (!raw.isNullOrBlank()) {
                val drafts = parseDrafts(raw, now, CaptureSource.PHOTO, ocrText)
                if (drafts.isNotEmpty()) {
                    return ExtractionResult(drafts, ExtractionEngine.LLM)
                }
            }
        }
        return extract(ocrText, now, CaptureSource.PHOTO, useLlm)
    }

    // ---- internals ----------------------------------------------------------

    private fun rulesOnly(
        draft: EventDraft?,
        notice: ExtractionResult.Notice?,
    ): ExtractionResult = ExtractionResult(
        drafts = listOfNotNull(draft),
        engine = ExtractionEngine.RULES,
        notice = when {
            draft?.dateWasGuessed == true -> ExtractionResult.Notice.DATE_GUESSED
            else -> notice
        },
    )

    private fun parseDrafts(
        raw: String,
        now: LocalDateTime,
        source: CaptureSource,
        rawInput: String,
    ): List<EventDraft> {
        val root = MiniJson.extractFirst(raw) ?: return emptyList()
        val items = MiniJson.asList(root)
            ?: MiniJson.asList(MiniJson.asMap(root)?.get("events"))
            ?: listOfNotNull(MiniJson.asMap(root)?.takeIf { it.containsKey("title") })
        return items.mapNotNull { toDraft(MiniJson.asMap(it), now, source, rawInput) }
    }

    private fun toDraft(
        obj: Map<String, Any?>?,
        now: LocalDateTime,
        source: CaptureSource,
        rawInput: String,
    ): EventDraft? {
        if (obj == null) return null
        val title = MiniJson.string(obj, "title", "summary", "name")?.take(MAX_TITLE) ?: return null
        if (title.isBlank()) return null

        val date = parseDate(MiniJson.string(obj, "date", "day", "start_date"), now)
        val startTime = parseTime(MiniJson.string(obj, "start", "start_time", "time", "from"))
        val endTime = parseTime(MiniJson.string(obj, "end", "end_time", "to", "until"))
        val allDay = MiniJson.boolean(obj, "all_day", "allDay", "all-day") ?: (startTime == null)

        // A model answer with no usable date is worse than nothing: drop it and let
        // the grammar's draft stand instead of inventing a day for the user.
        if (date == null && startTime == null) return null

        val resolvedDate = date ?: now.toLocalDate()
        val start = if (allDay || startTime == null) {
            resolvedDate.atStartOfDay()
        } else {
            resolvedDate.atTime(startTime)
        }
        val end = when {
            allDay || startTime == null -> resolvedDate.atTime(23, 59)
            endTime != null -> {
                val candidate = resolvedDate.atTime(endTime)
                if (candidate.isAfter(start)) candidate else candidate.plusDays(1)
            }
            else -> start.plusHours(1)
        }

        return EventDraft(
            title = title,
            start = start,
            end = end,
            allDay = allDay || startTime == null,
            location = MiniJson.string(obj, "location", "place", "where")?.take(MAX_LOCATION),
            notes = MiniJson.string(obj, "notes", "description", "details")?.take(MAX_NOTES),
            reminderMinutes = MiniJson.int(obj, "reminder_minutes", "reminder") ?: DEFAULT_REMINDER,
            engine = ExtractionEngine.LLM,
            source = source,
            rawInput = rawInput,
            dateWasGuessed = date == null,
        )
    }

    /**
     * Overwrites model-guessed dates and times with the grammar's, which are computed
     * arithmetically from the current date and cannot drift.
     */
    private fun reconcile(modelDrafts: List<EventDraft>, ruleDraft: EventDraft?): List<EventDraft> {
        // Anchoring only makes sense one-to-one. A poster that yields five events has
        // no single grammar counterpart, so the model's own dates stand.
        if (ruleDraft == null || modelDrafts.size != 1) return modelDrafts
        val model = modelDrafts.first()
        if (ruleDraft.dateWasGuessed) return modelDrafts

        val ruleHasTime = !ruleDraft.allDay
        val anchoredDate = ruleDraft.start.toLocalDate()
        val anchoredTime = if (ruleHasTime) ruleDraft.start.toLocalTime() else model.start.toLocalTime()
        val allDay = model.allDay && !ruleHasTime

        val start = if (allDay) {
            anchoredDate.atStartOfDay()
        } else {
            anchoredDate.atTime(anchoredTime)
        }
        val duration = if (ruleHasTime && ruleDraft.durationMinutes > 0) {
            ruleDraft.durationMinutes
        } else {
            model.durationMinutes.coerceAtLeast(1)
        }
        val end = if (allDay) anchoredDate.atTime(23, 59) else start.plusMinutes(duration)

        return listOf(
            model.copy(
                // The grammar strips date words out of the title mechanically; the model
                // writes a better one. Keep the model's unless it came back empty.
                title = model.title.ifBlank { ruleDraft.title },
                start = start,
                end = end,
                allDay = allDay,
                location = model.location ?: ruleDraft.location,
                reminderMinutes = model.reminderMinutes ?: ruleDraft.reminderMinutes,
                engine = ExtractionEngine.HYBRID,
                dateWasGuessed = false,
            ),
        )
    }

    private fun parseDate(value: String?, now: LocalDateTime): LocalDate? {
        val text = value?.let { KhmerText.toAsciiDigits(it).trim() } ?: return null
        if (text.isBlank()) return null
        ISO_DATE.find(text)?.let { m ->
            val year = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val day = m.groupValues[3].toInt()
            runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { return it }
        }
        // The model sometimes echoes the phrase instead of resolving it.
        return rules.resolveDateOnly(text, now)
    }

    private fun parseTime(value: String?): LocalTime? {
        val text = value?.let { KhmerText.toAsciiDigits(it).trim() } ?: return null
        if (text.isBlank() || text.equals("null", ignoreCase = true)) return null
        CLOCK_TIME.find(text)?.let { m ->
            var hour = m.groupValues[1].toIntOrNull() ?: return@let
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            val meridiem = m.groupValues[3].lowercase()
            if (meridiem.startsWith("p") && hour in 1..11) hour += 12
            if (meridiem.startsWith("a") && hour == 12) hour = 0
            if (hour in 0..23 && minute in 0..59) return LocalTime.of(hour, minute)
        }
        return rules.resolveTimeOnly(text)
    }

    private companion object {
        const val MAX_TITLE = 120
        const val MAX_LOCATION = 120
        const val MAX_NOTES = 500
        const val DEFAULT_REMINDER = 15

        val ISO_DATE = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
        val CLOCK_TIME = Regex("""(\d{1,2})(?::(\d{2}))?\s*([ap]\.?m\.?)?""", RegexOption.IGNORE_CASE)
    }
}
