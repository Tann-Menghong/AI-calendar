package com.aicalendar

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.aicalendar.ui.AppNavigation
import com.aicalendar.ui.theme.AiCalendarTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Reminders are the whole point of a calendar, so ask once at first launch
        // rather than at the moment an event is saved.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val openEventId = intent?.getLongExtra(EXTRA_OPEN_EVENT_ID, 0L)?.takeIf { it > 0 }

        setContent {
            AiCalendarTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavigation(openEventId = openEventId)
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_EVENT_ID = "open_event_id"
    }
}
