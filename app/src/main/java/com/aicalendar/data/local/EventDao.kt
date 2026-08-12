package com.aicalendar.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aicalendar.data.model.CalendarEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    /** Events overlapping [fromEpochMillis, toEpochMillis), ordered for agenda display. */
    @Query(
        """
        SELECT * FROM events
        WHERE startEpochMillis < :toEpochMillis AND endEpochMillis >= :fromEpochMillis
        ORDER BY startEpochMillis ASC
        """,
    )
    fun observeInRange(fromEpochMillis: Long, toEpochMillis: Long): Flow<List<CalendarEvent>>

    @Query("SELECT * FROM events WHERE id = :id")
    fun observeById(id: Long): Flow<CalendarEvent?>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): CalendarEvent?

    @Query(
        """
        SELECT * FROM events
        WHERE startEpochMillis >= :fromEpochMillis
        ORDER BY startEpochMillis ASC
        LIMIT :limit
        """,
    )
    fun observeUpcoming(fromEpochMillis: Long, limit: Int = 50): Flow<List<CalendarEvent>>

    @Query(
        """
        SELECT * FROM events
        WHERE title LIKE '%' || :query || '%'
           OR location LIKE '%' || :query || '%'
           OR notes LIKE '%' || :query || '%'
        ORDER BY startEpochMillis DESC
        LIMIT 100
        """,
    )
    fun search(query: String): Flow<List<CalendarEvent>>

    /** Pending reminders, used to re-arm alarms after a reboot. */
    @Query(
        """
        SELECT * FROM events
        WHERE reminderMinutes IS NOT NULL AND startEpochMillis > :nowEpochMillis
        ORDER BY startEpochMillis ASC
        """,
    )
    suspend fun getEventsWithPendingReminders(nowEpochMillis: Long): List<CalendarEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CalendarEvent): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<CalendarEvent>): List<Long>

    @Update
    suspend fun update(event: CalendarEvent)

    @Delete
    suspend fun delete(event: CalendarEvent)

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteById(id: Long)
}
