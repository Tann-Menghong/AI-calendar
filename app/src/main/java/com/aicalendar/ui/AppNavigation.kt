package com.aicalendar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aicalendar.data.model.CaptureSource
import com.aicalendar.ui.calendar.CalendarScreen
import com.aicalendar.ui.capture.CaptureScreen
import com.aicalendar.ui.editor.EventEditorScreen
import com.aicalendar.ui.editor.EventEditorViewModel
import com.aicalendar.ui.settings.SettingsScreen

private object Routes {
    const val CALENDAR = "calendar"
    const val SETTINGS = "settings"
    const val CAPTURE = "capture/{mode}"
    const val EDITOR = "editor?${EventEditorViewModel.ARG_EVENT_ID}={eventId}"

    fun capture(mode: CaptureSource) = "capture/${mode.name}"

    fun editor(eventId: Long = 0L) = "editor?${EventEditorViewModel.ARG_EVENT_ID}=$eventId"
}

@Composable
fun AppNavigation(openEventId: Long? = null) {
    val navController = rememberNavController()

    // Launched from a reminder notification: go straight to that event.
    LaunchedEffect(openEventId) {
        if (openEventId != null && openEventId > 0) {
            navController.navigate(Routes.editor(openEventId))
        }
    }

    NavHost(navController = navController, startDestination = Routes.CALENDAR) {
        composable(Routes.CALENDAR) {
            CalendarScreen(
                onAddEvent = { navController.navigate(Routes.editor()) },
                onOpenEvent = { id -> navController.navigate(Routes.editor(id)) },
                onOpenCapture = { mode -> navController.navigate(Routes.capture(mode)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.CAPTURE,
            arguments = listOf(navArgument("mode") { type = NavType.StringType }),
        ) { entry ->
            val mode = entry.arguments?.getString("mode")
                ?.let { name -> runCatching { CaptureSource.valueOf(name) }.getOrNull() }
                ?: CaptureSource.TEXT
            CaptureScreen(
                initialMode = mode,
                onClose = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument(EventEditorViewModel.ARG_EVENT_ID) {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
        ) {
            EventEditorScreen(onClose = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onClose = { navController.popBackStack() })
        }
    }
}
