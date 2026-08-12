package com.aicalendar.notify

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aicalendar.MainActivity
import com.aicalendar.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Posts the notification when an event reminder alarm fires. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND) return
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        if (eventId < 0) return

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val startMillis = intent.getLongExtra(EXTRA_START, 0L)
        val location = intent.getStringExtra(EXTRA_LOCATION)
        val allDay = intent.getBooleanExtra(EXTRA_ALL_DAY, false)

        ReminderScheduler.ensureChannel(context)

        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        // On Android 13+ a revoked permission means the post is silently dropped;
        // bailing out early keeps that from looking like a scheduling bug in logs.
        if (!granted) return

        val whenText = formatWhen(context, startMillis, allDay)
        val body = listOfNotNull(whenText, location?.takeIf { it.isNotBlank() })
            .joinToString(context.getString(R.string.notification_detail_separator))

        val contentIntent = PendingIntent.getActivity(
            context,
            eventId.toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_EVENT_ID, eventId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { context.getString(R.string.notification_fallback_title) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(eventId.toInt(), notification)
        }
    }

    private fun formatWhen(context: Context, startMillis: Long, allDay: Boolean): String {
        if (startMillis <= 0L) return ""
        val dateTime = Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault())
        val formatter = if (allDay) {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        } else {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        }.withLocale(context.resources.configuration.locales.get(0) ?: Locale.getDefault())
        return dateTime.format(formatter)
    }

    companion object {
        const val ACTION_REMIND = "com.aicalendar.action.REMIND"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START = "start"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_ALL_DAY = "all_day"
    }
}
