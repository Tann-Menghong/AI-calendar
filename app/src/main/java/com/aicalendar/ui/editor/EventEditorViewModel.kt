package com.aicalendar.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aicalendar.AiCalendarApp
import com.aicalendar.data.model.CalendarEvent
import com.aicalendar.data.repo.EventRepository
import com.aicalendar.data.repo.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class EditorUiState(
    val id: Long = 0,
    val title: String = "",
    val start: LocalDateTime = LocalDateTime.now().plusHours(1).withMinute(0),
    val end: LocalDateTime = LocalDateTime.now().plusHours(2).withMinute(0),
    val allDay: Boolean = false,
    val location: String = "",
    val notes: String = "",
    val reminderMinutes: Int? = 15,
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
) {
    val canSave: Boolean get() = title.isNotBlank() && !end.isBefore(start)
}

/** Create/edit form for a single event. */
class EventEditorViewModel(
    private val events: EventRepository,
    private val settings: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val eventId: Long = savedStateHandle.get<Long>(ARG_EVENT_ID) ?: 0L
    private val startMillisArg: Long = savedStateHandle.get<Long>(ARG_START_MILLIS) ?: 0L

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val defaults = settings.settings.first()
            if (eventId > 0) {
                val event = events.getById(eventId)
                if (event != null) {
                    _state.value = EditorUiState(
                        id = event.id,
                        title = event.title,
                        start = event.startDateTime(),
                        end = event.endDateTime(),
                        allDay = event.allDay,
                        location = event.location.orEmpty(),
                        notes = event.notes.orEmpty(),
                        reminderMinutes = event.reminderMinutes,
                        isLoading = false,
                        isNew = false,
                    )
                    return@launch
                }
            }
            // New event: open on the day the user was looking at, at the next round hour.
            val base = if (startMillisArg > 0) {
                LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(startMillisArg),
                    ZoneId.systemDefault(),
                )
            } else {
                LocalDateTime.now()
            }
            val start = base.withMinute(0).withSecond(0).withNano(0).plusHours(1)
            _state.value = EditorUiState(
                start = start,
                end = start.plusMinutes(defaults.defaultDurationMinutes.toLong()),
                reminderMinutes = defaults.defaultReminderMinutes,
                isLoading = false,
                isNew = true,
            )
        }
    }

    fun setTitle(value: String) = _state.update { it.copy(title = value) }

    fun setLocation(value: String) = _state.update { it.copy(location = value) }

    fun setNotes(value: String) = _state.update { it.copy(notes = value) }

    fun setReminder(minutes: Int?) = _state.update { it.copy(reminderMinutes = minutes) }

    fun setAllDay(allDay: Boolean) = _state.update { current ->
        if (allDay) {
            current.copy(
                allDay = true,
                start = current.start.toLocalDate().atStartOfDay(),
                end = current.start.toLocalDate().atTime(23, 59),
            )
        } else {
            val start = current.start.toLocalDate().atTime(9, 0)
            current.copy(allDay = false, start = start, end = start.plusHours(1))
        }
    }

    fun setDate(date: LocalDate) = _state.update { current ->
        val duration = java.time.Duration.between(current.start, current.end)
        val start = date.atTime(current.start.toLocalTime())
        current.copy(start = start, end = start.plus(duration))
    }

    fun setStartTime(time: LocalTime) = _state.update { current ->
        val duration = java.time.Duration.between(current.start, current.end)
        val start = current.start.toLocalDate().atTime(time)
        current.copy(start = start, end = start.plus(duration), allDay = false)
    }

    fun setEndTime(time: LocalTime) = _state.update { current ->
        var end = current.start.toLocalDate().atTime(time)
        if (!end.isAfter(current.start)) end = end.plusDays(1)
        current.copy(end = end)
    }

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        if (!current.canSave) return
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val existing = if (current.id > 0) events.getById(current.id) else null
            val event = (existing ?: CalendarEvent(title = "", startEpochMillis = 0, endEpochMillis = 0))
                .copy(
                    id = current.id,
                    title = current.title.trim(),
                    startEpochMillis = current.start.atZone(zone).toInstant().toEpochMilli(),
                    endEpochMillis = current.end.atZone(zone).toInstant().toEpochMilli(),
                    allDay = current.allDay,
                    location = current.location.trim().ifBlank { null },
                    notes = current.notes.trim().ifBlank { null },
                    reminderMinutes = current.reminderMinutes,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
            events.save(event)
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = _state.value.id
        if (id <= 0) {
            onDeleted()
            return
        }
        viewModelScope.launch {
            events.deleteById(id)
            onDeleted()
        }
    }

    companion object {
        const val ARG_EVENT_ID = "eventId"
        const val ARG_START_MILLIS = "startMillis"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as AiCalendarApp
                EventEditorViewModel(
                    app.container.eventRepository,
                    app.container.settingsRepository,
                    createSavedStateHandle(),
                )
            }
        }
    }
}
