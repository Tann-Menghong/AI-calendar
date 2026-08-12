package com.aicalendar.ui.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicalendar.R
import com.aicalendar.data.model.CalendarEvent
import com.aicalendar.data.model.CaptureSource
import com.aicalendar.ui.Formatters
import com.aicalendar.ui.rememberFormatters
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onAddEvent: () -> Unit,
    onOpenEvent: (Long) -> Unit,
    onOpenCapture: (CaptureSource) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CalendarViewModel = viewModel(factory = CalendarViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val formatters = rememberFormatters()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.action_undo)
    val deletedMessage = stringResource(R.string.event_deleted)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(formatters.monthTitle(state.visibleMonth)) },
                actions = {
                    IconButton(onClick = viewModel::showPreviousMonth) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.action_previous_month),
                        )
                    }
                    IconButton(onClick = viewModel::goToToday) {
                        Icon(
                            Icons.Default.CalendarToday,
                            contentDescription = stringResource(R.string.action_today),
                        )
                    }
                    IconButton(onClick = viewModel::showNextMonth) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.action_next_month),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.title_settings),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddEvent,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add_event)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            MonthGrid(
                month = state.visibleMonth,
                selectedDate = state.selectedDate,
                eventsByDay = state.eventsByDay,
                weekStartsOnMonday = state.weekStartsOnMonday,
                formatters = formatters,
                onSelectDate = viewModel::selectDate,
            )

            HorizontalDivider()

            QuickCaptureRow(onOpenCapture = onOpenCapture)

            Text(
                text = formatters.fullDate(state.selectedDate),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            AgendaList(
                events = state.selectedDayEvents,
                formatters = formatters,
                onOpenEvent = onOpenEvent,
                onDelete = { event ->
                    viewModel.delete(event)
                    scope.launch {
                        val result = snackbarHost.showSnackbar(
                            message = deletedMessage,
                            actionLabel = undoLabel,
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.restore(event)
                    }
                },
            )
        }
    }
}

@Composable
private fun QuickCaptureRow(onOpenCapture: (CaptureSource) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickCaptureChip(
            label = stringResource(R.string.capture_voice),
            onClick = { onOpenCapture(CaptureSource.VOICE) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        QuickCaptureChip(
            label = stringResource(R.string.capture_photo),
            onClick = { onOpenCapture(CaptureSource.PHOTO) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        QuickCaptureChip(
            label = stringResource(R.string.capture_text),
            onClick = { onOpenCapture(CaptureSource.TEXT) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun QuickCaptureChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.size(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selectedDate: LocalDate,
    eventsByDay: Map<LocalDate, List<CalendarEvent>>,
    weekStartsOnMonday: Boolean,
    formatters: Formatters,
    onSelectDate: (LocalDate) -> Unit,
) {
    val firstDayOfWeek = if (weekStartsOnMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    val weekDays = remember(firstDayOfWeek) {
        (0..6).map { DayOfWeek.of(((firstDayOfWeek.value - 1 + it) % 7) + 1) }
    }
    val cells = remember(month, firstDayOfWeek) { monthCells(month, firstDayOfWeek) }
    val today = remember { LocalDate.now() }

    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            weekDays.forEach { day ->
                Text(
                    text = formatters.weekdayInitial(day),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    DayCell(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        isSelected = date == selectedDate,
                        eventCount = eventsByDay[date]?.size ?: 0,
                        label = formatters.dayOfMonth(date),
                        onClick = { onSelectDate(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    eventCount: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val container = when {
        isSelected -> colors.primary
        isToday -> colors.primaryContainer
        else -> Color.Transparent
    }
    val content = when {
        isSelected -> colors.onPrimary
        isToday -> colors.onPrimaryContainer
        inMonth -> colors.onSurface
        else -> colors.onSurfaceVariant.copy(alpha = 0.45f)
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .background(container)
            .then(
                if (isToday && !isSelected) {
                    Modifier.border(1.dp, colors.primary, CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                color = content,
            )
            // Up to three dots, so a busy day reads as busy without counting.
            AnimatedVisibility(visible = eventCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(eventCount.coerceAtMost(3)) {
                        Box(
                            Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) colors.onPrimary else colors.primary),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaList(
    events: List<CalendarEvent>,
    formatters: Formatters,
    onOpenEvent: (Long) -> Unit,
    onDelete: (CalendarEvent) -> Unit,
) {
    if (events.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = stringResource(R.string.agenda_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(events, key = { it.id }) { event ->
            EventRow(
                event = event,
                formatters = formatters,
                onClick = { onOpenEvent(event.id) },
                onDelete = { onDelete(event) },
            )
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun EventRow(
    event: CalendarEvent,
    formatters: Formatters,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val allDayLabel = stringResource(R.string.all_day)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
                Text(
                    text = formatters.eventTimeRange(event, allDayLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!event.location.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Place,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = event.location,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            SourceBadge(event.source)
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A small hint of how the event got here — typed, dictated or photographed. */
@Composable
private fun SourceBadge(source: CaptureSource) {
    val icon = when (source) {
        CaptureSource.VOICE -> Icons.Default.Mic
        CaptureSource.PHOTO -> Icons.Default.PhotoCamera
        CaptureSource.TEXT -> null
    } ?: return
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Six rows of seven days, padded with the neighbouring months' trailing days. */
private fun monthCells(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate> {
    val first = month.atDay(1)
    val offset = ((first.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
    val start = first.minusDays(offset.toLong())
    return (0 until 42).map { start.plusDays(it.toLong()) }
}
