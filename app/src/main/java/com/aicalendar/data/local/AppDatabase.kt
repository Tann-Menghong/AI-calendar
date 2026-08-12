package com.aicalendar.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.aicalendar.data.model.CalendarEvent
import com.aicalendar.data.model.CaptureSource
import com.aicalendar.data.model.ExtractionEngine

class EnumConverters {
    @TypeConverter
    fun toCaptureSource(value: String?): CaptureSource =
        value?.let { runCatching { CaptureSource.valueOf(it) }.getOrNull() } ?: CaptureSource.TEXT

    @TypeConverter
    fun fromCaptureSource(value: CaptureSource): String = value.name

    @TypeConverter
    fun toExtractionEngine(value: String?): ExtractionEngine =
        value?.let { runCatching { ExtractionEngine.valueOf(it) }.getOrNull() } ?: ExtractionEngine.RULES

    @TypeConverter
    fun fromExtractionEngine(value: ExtractionEngine): String = value.name
}

@Database(entities = [CalendarEvent::class], version = 1, exportSchema = true)
@TypeConverters(EnumConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "ai-calendar.db",
            ).build().also { instance = it }
        }
    }
}
