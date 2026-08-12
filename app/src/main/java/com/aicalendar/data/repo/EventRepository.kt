package com.aicalendar.data.repo

import com.aicalendar.data.local.EventDao
import com.aicalendar.data.model.CalendarEvent
import com.aicalendar.data.model.EventDraft
import com.aicalendar.notify.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Single entry point for event storage. Every write also (re)arms the reminder alarm,
 * so notifications can never drift out of sync with the database.
 */
class EventRepository(
    private val dao: EventDao,
    private val reminders: ReminderScheduler,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    fun observeDay(date: LocalDate): Flow<List<CalendarEvent>> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.observeInRange(start, end)
    }

    fun observeMonth(month: YearMonth): Flow<List<CalendarEvent>> {
        // Pad by a week each side so the trailing/leading grid cells are populated.
        val start = month.atDay(1).minusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.atEndOfMonth().plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.observeInRange(start, end)
    }

    fun observeUpcoming(limit: Int = 50): Flow<List<CalendarEvent>> =
        dao.observeUpcoming(System.currentTimeMillis(), limit)

    fun observeById(id: Long): Flow<CalendarEvent?> = dao.observeById(id)

    fun search(query: String): Flow<List<CalendarEvent>> = dao.search(query)

    suspend fun getById(id: Long): CalendarEvent? = dao.getById(id)

    suspend fun save(draft: EventDraft): Long = save(CalendarEvent.fromDraft(draft, zone))

    suspend fun save(event: CalendarEvent): Long {
        val id = if (event.id == 0L) {
            dao.insert(event)
        } else {
            dao.update(event.copy(updatedAtEpochMillis = System.currentTimeMillis()))
            event.id
        }
        reminders.schedule(event.copy(id = id))
        return id
    }

    suspend fun saveAll(drafts: List<EventDraft>): List<Long> =
        drafts.map { save(it) }

    suspend fun delete(event: CalendarEvent) {
        reminders.cancel(event.id)
        dao.delete(event)
    }

    suspend fun deleteById(id: Long) {
        reminders.cancel(id)
        dao.deleteById(id)
    }

    /** Re-arms every future reminder; called after boot and after an app update. */
    suspend fun rescheduleAllReminders() {
        dao.getEventsWithPendingReminders(System.currentTimeMillis())
            .forEach { reminders.schedule(it) }
    }
}
