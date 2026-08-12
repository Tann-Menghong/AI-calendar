package com.aicalendar.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** UI language. AUTO follows the system locale. */
enum class AppLanguage(val tag: String) {
    AUTO(""),
    KHMER("km"),
    ENGLISH("en"),
}

data class AppSettings(
    val language: AppLanguage = AppLanguage.AUTO,
    /** Locale handed to the speech recogniser. */
    val speechLocale: String = "km-KH",
    val defaultReminderMinutes: Int = 15,
    val defaultDurationMinutes: Int = 60,
    /** Absolute path of the installed .task LLM bundle, or null when none. */
    val modelPath: String? = null,
    val useLlm: Boolean = true,
    /** Week starts on Sunday in Cambodia; Monday is offered for other habits. */
    val weekStartsOnMonday: Boolean = false,
    val khmerOcrInstalled: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val LANGUAGE = stringPreferencesKey("language")
        val SPEECH_LOCALE = stringPreferencesKey("speech_locale")
        val REMINDER = intPreferencesKey("default_reminder_minutes")
        val DURATION = intPreferencesKey("default_duration_minutes")
        val MODEL_PATH = stringPreferencesKey("model_path")
        val USE_LLM = booleanPreferencesKey("use_llm")
        val WEEK_START_MONDAY = booleanPreferencesKey("week_start_monday")
        val KHMER_OCR = booleanPreferencesKey("khmer_ocr_installed")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            language = prefs[Keys.LANGUAGE]
                ?.let { tag -> AppLanguage.entries.firstOrNull { it.name == tag } }
                ?: AppLanguage.AUTO,
            speechLocale = prefs[Keys.SPEECH_LOCALE] ?: "km-KH",
            defaultReminderMinutes = prefs[Keys.REMINDER] ?: 15,
            defaultDurationMinutes = prefs[Keys.DURATION] ?: 60,
            modelPath = prefs[Keys.MODEL_PATH],
            useLlm = prefs[Keys.USE_LLM] ?: true,
            weekStartsOnMonday = prefs[Keys.WEEK_START_MONDAY] ?: false,
            khmerOcrInstalled = prefs[Keys.KHMER_OCR] ?: false,
        )
    }

    suspend fun setLanguage(language: AppLanguage) = edit { it[Keys.LANGUAGE] = language.name }

    suspend fun setSpeechLocale(locale: String) = edit { it[Keys.SPEECH_LOCALE] = locale }

    suspend fun setDefaultReminderMinutes(minutes: Int) = edit { it[Keys.REMINDER] = minutes }

    suspend fun setDefaultDurationMinutes(minutes: Int) = edit { it[Keys.DURATION] = minutes }

    suspend fun setModelPath(path: String?) = edit {
        if (path == null) it.remove(Keys.MODEL_PATH) else it[Keys.MODEL_PATH] = path
    }

    suspend fun setUseLlm(enabled: Boolean) = edit { it[Keys.USE_LLM] = enabled }

    suspend fun setWeekStartsOnMonday(monday: Boolean) = edit { it[Keys.WEEK_START_MONDAY] = monday }

    suspend fun setKhmerOcrInstalled(installed: Boolean) = edit { it[Keys.KHMER_OCR] = installed }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
