package com.aicalendar.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.aicalendar.R
import com.aicalendar.data.model.CalendarEvent

/**
 * Arms and cancels the one-shot alarm behind each event reminder.
 *
 * Exact alarms are used when the user has granted them, because a reminder that
 * arrives inside a maintenance window twenty minutes late is not a reminder. Without
 * the permission the alarm still fires, just inexactly.
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun schedule(event: CalendarEvent) {
        cancel(event.id)
        val triggerAt = event.reminderAtEpochMillis() ?: return
        if (triggerAt <= System.currentTimeMillis()) return
        val manager = alarmManager ?: return

        val pendingIntent = reminderPendingIntent(event) ?: return
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        try {
            if (canBeExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: SecurityException) {
            // The exact-alarm permission can be revoked between the check and the call.
            Log.w(TAG, "Falling back to an inexact alarm", e)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(eventId: Long) {
        val manager = alarmManager ?: return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMIND
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            eventId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pendingIntent != null) {
            manager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    /** True when reminders may be late because the exact-alarm permission is off. */
    fun needsExactAlarmPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager?.canScheduleExactAlarms() == false

    private fun reminderPendingIntent(event: CalendarEvent): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMIND
            putExtra(ReminderReceiver.EXTRA_EVENT_ID, event.id)
            putExtra(ReminderReceiver.EXTRA_TITLE, event.title)
            putExtra(ReminderReceiver.EXTRA_START, event.startEpochMillis)
            putExtra(ReminderReceiver.EXTRA_LOCATION, event.location)
            putExtra(ReminderReceiver.EXTRA_ALL_DAY, event.allDay)
        }
        return PendingIntent.getBroadcast(
            context,
            event.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "ReminderScheduler"
        const val CHANNEL_ID = "event_reminders"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_reminders),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_reminders_description)
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
