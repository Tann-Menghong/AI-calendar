package com.aicalendar.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aicalendar.AiCalendarApp
import com.aicalendar.data.model.CalendarEvent
import com.aicalendar.data.repo.EventRepository
import com.aicalendar.data.repo.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class CalendarUiState(
    val visibleMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val monthEvents: List<CalendarEvent> = emptyList(),
    val weekStartsOnMonday: Boolean = false,
) {
    /** Events grouped by the day they start on, for the month grid dots. */
    val eventsByDay: Map<LocalDate, List<CalendarEvent>> =
        monthEvents.groupBy { it.startDateTime().toLocalDate() }

    val selectedDayEvents: List<CalendarEvent> =
        monthEvents.filter { event ->
            val start = event.startDateTime().toLocalDate()
            val end = event.endDateTime().toLocalDate()
            !selectedDate.isBefore(start) && !selectedDate.isAfter(end)
        }.sortedBy { it.startEpochMillis }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val events: EventRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val visibleMonth = MutableStateFlow(YearMonth.now())
    private val selectedDate = MutableStateFlow(LocalDate.now())

    private val monthEvents: StateFlow<List<CalendarEvent>> = visibleMonth
        .flatMapLatest { events.observeMonth(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<CalendarUiState> = combine(
        visibleMonth,
        selectedDate,
        monthEvents,
        settings.settings.map { it.weekStartsOnMonday },
    ) { month, date, list, mondayFirst ->
        CalendarUiState(month, date, list, mondayFirst)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    fun showMonth(month: YearMonth) {
        visibleMonth.value = month
    }

    fun showPreviousMonth() {
        visibleMonth.value = visibleMonth.value.minusMonths(1)
    }

    fun showNextMonth() {
        visibleMonth.value = visibleMonth.value.plusMonths(1)
    }

    fun selectDate(date: LocalDate) {
        selectedDate.value = date
        // Tapping a trailing grid cell should follow the user into that month.
        val month = YearMonth.from(date)
        if (month != visibleMonth.value) visibleMonth.value = month
    }

    fun goToToday() {
        val today = LocalDate.now()
        selectedDate.value = today
        visibleMonth.value = YearMonth.from(today)
    }

    fun delete(event: CalendarEvent) {
        viewModelScope.launch { events.delete(event) }
    }

    fun restore(event: CalendarEvent) {
        viewModelScope.launch { events.save(event) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as AiCalendarApp
                CalendarViewModel(app.container.eventRepository, app.container.settingsRepository)
            }
        }
    }
}
