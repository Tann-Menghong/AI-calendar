package com.aicalendar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicalendar.R
import com.aicalendar.data.model.EventDraft
import com.aicalendar.ui.Formatters

/**
 * One extracted event, shown for review before it is saved.
 *
 * Everything is editable in place: extraction is a suggestion, and the fastest fix for
 * a wrong time is a tap on the time, not a trip to a separate editor screen.
 */
@Composable
fun DraftCard(
    draft: EventDraft,
    formatters: Formatters,
    onChange: (EventDraft) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { onChange(draft.copy(title = it)) },
                label = { Text(stringResource(R.string.field_title)) },
                singleLine = false,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.size(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { showDatePicker = true },
                    label = { Text(formatters.dayMonth(draft.start.toLocalDate())) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )
                if (!draft.allDay) {
                    AssistChip(
                        onClick = { showStartPicker = true },
                        label = { Text(formatters.time(draft.start)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AccessTime,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                    )
                    AssistChip(
                        onClick = { showEndPicker = true },
                        label = { Text(formatters.time(draft.end)) },
                    )
                } else {
                    AssistChip(
                        onClick = {
                            onChange(
                                draft.copy(
                                    allDay = false,
                                    start = draft.start.toLocalDate().atTime(9, 0),
                                    end = draft.start.toLocalDate().atTime(10, 0),
                                ),
                            )
                        },
                        label = { Text(stringResource(R.string.all_day)) },
                    )
                }
            }

            if (!draft.location.isNullOrBlank()) {
                Spacer(Modifier.size(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = draft.location,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (draft.dateWasGuessed) {
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.draft_date_guessed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.size(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.action_discard))
                }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onSave, enabled = draft.title.isNotBlank()) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    if (showDatePicker) {
        DatePickerDialogM3(
            initial = draft.start.toLocalDate(),
            onDismiss = { showDatePicker = false },
            onPicked = { date ->
                // Keep the clock time and the duration; only the day moves.
                val duration = java.time.Duration.between(draft.start, draft.end)
                val start = date.atTime(draft.start.toLocalTime())
                onChange(draft.copy(start = start, end = start.plus(duration)))
            },
        )
    }
    if (showStartPicker) {
        TimePickerDialogM3(
            initial = draft.start.toLocalTime(),
            onDismiss = { showStartPicker = false },
            onPicked = { time ->
                val duration = java.time.Duration.between(draft.start, draft.end)
                val start = draft.start.toLocalDate().atTime(time)
                onChange(draft.copy(start = start, end = start.plus(duration), allDay = false))
            },
        )
    }
    if (showEndPicker) {
        TimePickerDialogM3(
            initial = draft.end.toLocalTime(),
            onDismiss = { showEndPicker = false },
            onPicked = { time ->
                var end = draft.start.toLocalDate().atTime(time)
                // An end before the start means the event runs past midnight.
                if (!end.isAfter(draft.start)) end = end.plusDays(1)
                onChange(draft.copy(end = end))
            },
        )
    }
}
