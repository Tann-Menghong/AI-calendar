package com.aicalendar.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicalendar.R
import com.aicalendar.ai.LlmState
import com.aicalendar.ai.ModelManager
import com.aicalendar.voice.SpeechToText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val ocr by viewModel.ocr.collectAsStateWithLifecycle()
    val llmState by viewModel.llmState.collectAsStateWithLifecycle()

    val pickModelFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importModel) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_section_model))

            ModelCard(
                llmState = llmState,
                model = model,
                useLlm = settings.useLlm,
                onImport = { pickModelFile.launch(arrayOf("*/*")) },
                onDownload = viewModel::downloadModel,
                onDelete = viewModel::deleteModel,
                onCancel = viewModel::cancelTransfer,
                onToggleUseLlm = viewModel::setUseLlm,
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_ocr))

            OcrCard(
                state = ocr,
                onInstall = viewModel::installKhmerOcr,
                onRemove = viewModel::removeKhmerOcr,
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_voice))

            Text(
                text = stringResource(R.string.settings_speech_language),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeechToText.SUPPORTED_LOCALES.forEach { (tag, label) ->
                    FilterChip(
                        selected = settings.speechLocale == tag,
                        onClick = { viewModel.setSpeechLocale(tag) },
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_speech_offline_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_calendar))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.settings_week_starts_monday),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.weekStartsOnMonday,
                    onCheckedChange = viewModel::setWeekStartsOnMonday,
                )
            }

            Text(
                text = stringResource(R.string.settings_default_reminder),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 10, 15, 30, 60).forEach { minutes ->
                    FilterChip(
                        selected = settings.defaultReminderMinutes == minutes,
                        onClick = { viewModel.setDefaultReminder(minutes) },
                        label = { Text(stringResource(R.string.reminder_minutes, minutes)) },
                    )
                }
            }

            HorizontalDivider()
            Text(
                text = stringResource(R.string.settings_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ModelCard(
    llmState: LlmState,
    model: ModelUiState,
    useLlm: Boolean,
    onImport: () -> Unit,
    onDownload: (String, String?) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onToggleUseLlm: (Boolean) -> Unit,
) {
    var showDownloadForm by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = when (llmState) {
                    is LlmState.Ready -> stringResource(R.string.model_ready, llmState.modelName)
                    LlmState.Loading -> stringResource(R.string.model_loading)
                    LlmState.NotInstalled -> stringResource(R.string.model_not_installed)
                    is LlmState.Failed -> stringResource(R.string.model_failed)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (llmState is LlmState.Ready && llmState.supportsVision) {
                Text(
                    text = stringResource(R.string.model_supports_vision),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            model.installedName?.let { name ->
                Text(
                    text = "$name · ${formatMb(model.installedSizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.model_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (model.transferring) {
                Spacer(Modifier.size(10.dp))
                val fraction = model.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(
                    text = formatMb(model.bytesCopied) +
                        (model.totalBytes?.let { " / ${formatMb(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            } else {
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImport) { Text(stringResource(R.string.action_import_model)) }
                    OutlinedButton(onClick = { showDownloadForm = !showDownloadForm }) {
                        Text(stringResource(R.string.action_download_model))
                    }
                }
                if (model.installedName != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.action_remove_model))
                    }
                }
            }

            model.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (showDownloadForm && !model.transferring) {
                Spacer(Modifier.size(10.dp))
                Text(
                    text = stringResource(R.string.model_suggested_heading),
                    style = MaterialTheme.typography.titleSmall,
                )
                ModelManager.SUGGESTED_MODELS.forEach { suggestion ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = "${suggestion.name} · ~${suggestion.approxSizeMb} MB",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = suggestion.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { url = suggestion.url }) {
                            Text(stringResource(R.string.action_use_this_url))
                        }
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.field_model_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(6.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.field_access_token)) },
                    supportingText = { Text(stringResource(R.string.field_access_token_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = { onDownload(url.trim(), token.trim().ifBlank { null }) },
                    enabled = url.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_start_download))
                }
            }

            Spacer(Modifier.size(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_use_llm),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.settings_use_llm_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = useLlm, onCheckedChange = onToggleUseLlm)
            }
        }
    }
}

@Composable
private fun OcrCard(
    state: OcrUiState,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = stringResource(
                    if (state.installed) R.string.ocr_khmer_installed else R.string.ocr_khmer_not_installed,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.ocr_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            when {
                state.installing -> LinearProgressIndicator(Modifier.fillMaxWidth())
                state.installed -> TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.action_remove_khmer_ocr))
                }
                else -> Button(onClick = onInstall) {
                    Text(stringResource(R.string.action_install_khmer_ocr))
                }
            }
            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun formatMb(bytes: Long): String =
    "%.0f MB".format(bytes / 1_048_576.0)
