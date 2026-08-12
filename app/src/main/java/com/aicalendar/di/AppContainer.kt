package com.aicalendar.di

import android.content.Context
import com.aicalendar.ai.EventExtractor
import com.aicalendar.ai.LlmEngine
import com.aicalendar.ai.MediaPipeLlmEngine
import com.aicalendar.ai.ModelManager
import com.aicalendar.data.local.AppDatabase
import com.aicalendar.data.repo.EventRepository
import com.aicalendar.data.repo.SettingsRepository
import com.aicalendar.notify.ReminderScheduler
import com.aicalendar.nlp.RuleBasedEventParser
import com.aicalendar.ocr.OcrEngine
import com.aicalendar.ocr.TessDataInstaller
import com.aicalendar.voice.SpeechToText

/**
 * Hand-rolled dependency graph.
 *
 * The app has one process-wide object worth sharing — the loaded model, which owns
 * hundreds of megabytes of native memory — so a container is enough and a DI
 * framework would only add build time.
 */
class AppContainer(private val context: Context) {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(context) }

    val modelManager: ModelManager by lazy { ModelManager(context) }

    val llmEngine: LlmEngine by lazy { MediaPipeLlmEngine(context) }

    val ruleParser: RuleBasedEventParser by lazy { RuleBasedEventParser() }

    val eventExtractor: EventExtractor by lazy { EventExtractor(llmEngine, ruleParser) }

    val tessDataInstaller: TessDataInstaller by lazy { TessDataInstaller(context) }

    val ocrEngine: OcrEngine by lazy { OcrEngine(context, tessDataInstaller) }

    val speechToText: SpeechToText by lazy { SpeechToText(context) }

    val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(context) }

    val eventRepository: EventRepository by lazy {
        EventRepository(AppDatabase.get(context).eventDao(), reminderScheduler)
    }
}
