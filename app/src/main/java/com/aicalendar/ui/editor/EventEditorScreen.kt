package com.aicalendar.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicalendar.R
import com.aicalendar.ui.components.DatePickerDialogM3
import com.aicalendar.ui.components.TimePickerDialogM3
import com.aicalendar.ui.rememberFormatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorScreen(
    onClose: () -> Unit,
    viewModel: EventEditorViewModel = viewModel(factory = EventEditorViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formatters = rememberFormatters()

    var showDatePicker by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) R.string.title_new_event else R.string.title_edit_event,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { viewModel.delete(onClose) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
                    }
                    TextButton(
                        onClick = { viewModel.save(onClose) },
                        enabled = state.canSave,
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                label = { Text(stringResource(R.string.field_title)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.all_day), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.allDay, onCheckedChange = viewModel::setAllDay)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { showDatePicker = true },
                    label = { Text(formatters.dayMonth(state.start.toLocalDate())) },
                )
                if (!state.allDay) {
                    AssistChip(
                        onClick = { showStartPicker = true },
                        label = { Text(formatters.time(state.start)) },
                    )
                    AssistChip(
                        onClick = { showEndPicker = true },
                        label = { Text(formatters.time(state.end)) },
                    )
                }
            }

            OutlinedTextField(
                value = state.location,
                onValueChange = viewModel::setLocation,
                label = { Text(stringResource(R.string.field_location)) },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::setNotes,
                label = { Text(stringResource(R.string.field_notes)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
            )

            Text(
                text = stringResource(R.string.field_reminder),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                REMINDER_CHOICES.forEach { minutes ->
                    FilterChip(
                        selected = state.reminderMinutes == minutes,
                        onClick = { viewModel.setReminder(minutes) },
                        label = { Text(reminderLabel(minutes)) },
                    )
                }
            }

            Spacer(Modifier.size(24.dp))
        }
    }

    if (showDatePicker) {
        DatePickerDialogM3(
            initial = state.start.toLocalDate(),
            onDismiss = { showDatePicker = false },
            onPicked = viewModel::setDate,
        )
    }
    if (showStartPicker) {
        TimePickerDialogM3(
            initial = state.start.toLocalTime(),
            onDismiss = { showStartPicker = false },
            onPicked = viewModel::setStartTime,
        )
    }
    if (showEndPicker) {
        TimePickerDialogM3(
            initial = state.end.toLocalTime(),
            onDismiss = { showEndPicker = false },
            onPicked = viewModel::setEndTime,
        )
    }
}

private val REMINDER_CHOICES = listOf(null, 0, 10, 30, 60)

@Composable
private fun reminderLabel(minutes: Int?): String = when (minutes) {
    null -> stringResource(R.string.reminder_none)
    0 -> stringResource(R.string.reminder_at_start)
    60 -> stringResource(R.string.reminder_one_hour)
    else -> stringResource(R.string.reminder_minutes, minutes)
}
