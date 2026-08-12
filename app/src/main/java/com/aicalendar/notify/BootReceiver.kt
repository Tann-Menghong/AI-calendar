package com.aicalendar.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aicalendar.AiCalendarApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Alarms do not survive a reboot or an app update, so every pending reminder is
 * re-armed from the database when the device comes back up.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val container = (context.applicationContext as? AiCalendarApp)?.container ?: return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderScheduler.ensureChannel(context)
                container.eventRepository.rescheduleAllReminders()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
