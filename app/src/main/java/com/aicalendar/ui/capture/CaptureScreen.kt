package com.aicalendar.ui.capture

import android.Manifest
import android.content.Context
import android.net.Uri
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicalendar.R
import com.aicalendar.ai.ExtractionResult
import com.aicalendar.ai.LlmState
import com.aicalendar.data.model.CaptureSource
import com.aicalendar.ocr.OcrEngineKind
import com.aicalendar.ui.components.DraftCard
import com.aicalendar.ui.rememberFormatters
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    initialMode: CaptureSource,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CaptureViewModel = viewModel(factory = CaptureViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formatters = rememberFormatters()
    val context = LocalContext.current

    LaunchedEffect(initialMode) { viewModel.setMode(initialMode) }

    val cameraUri = remember { mutableStateOf(newCameraUri(context)) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success -> if (success) viewModel.onImagePicked(cameraUri.value) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::onImagePicked) }

    val requestMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.startListening() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_capture)) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ModeSelector(
                    mode = state.mode,
                    onModeChange = viewModel::setMode,
                )
            }

            item {
                ModelStatusBanner(
                    llmState = state.llmState,
                    onOpenSettings = onOpenSettings,
                )
            }

            when (state.mode) {
                CaptureSource.TEXT -> item {
                    TextCapture(
                        text = state.text,
                        onTextChange = viewModel::setText,
                    )
                }

                CaptureSource.VOICE -> item {
                    VoiceCapture(
                        isListening = state.isListening,
                        micLevel = state.micLevel,
                        partial = state.partialSpeech,
                        transcript = state.text,
                        onTextChange = viewModel::setText,
                        onStart = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                        onStop = viewModel::stopListening,
                    )
                }

                CaptureSource.PHOTO -> item {
                    PhotoCapture(
                        state = state,
                        onTakePhoto = {
                            cameraUri.value = newCameraUri(context)
                            takePicture.launch(cameraUri.value)
                        },
                        onPickPhoto = {
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onClear = viewModel::clearPhoto,
                        onTextChange = viewModel::setText,
                        onInstallKhmerOcr = viewModel::installKhmerOcr,
                    )
                }
            }

            item {
                Button(
                    onClick = viewModel::extract,
                    enabled = state.canExtract,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.action_extract_events))
                }
            }

            state.message?.let { message ->
                item {
                    MessageBanner(
                        text = messageText(message),
                        onDismiss = viewModel::dismissMessage,
                    )
                }
            }

            state.notice?.let { notice ->
                item {
                    MessageBanner(
                        text = noticeText(notice),
                        onDismiss = viewModel::dismissMessage,
                    )
                }
            }

            if (state.drafts.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.drafts_heading, state.drafts.size),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (state.drafts.size > 1) {
                            TextButton(onClick = { viewModel.saveAll(onClose) }) {
                                Text(stringResource(R.string.action_save_all))
                            }
                        }
                    }
                }
            }

            itemsIndexed(state.drafts) { index, draft ->
                DraftCard(
                    draft = draft,
                    formatters = formatters,
                    onChange = { viewModel.updateDraft(index, it) },
                    onSave = {
                        viewModel.saveDraft(index)
                        if (state.drafts.size == 1) onClose()
                    },
                    onDiscard = { viewModel.discardDraft(index) },
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ModeSelector(mode: CaptureSource, onModeChange: (CaptureSource) -> Unit) {
    val options = listOf(
        CaptureSource.TEXT to stringResource(R.string.capture_text),
        CaptureSource.VOICE to stringResource(R.string.capture_voice),
        CaptureSource.PHOTO to stringResource(R.string.capture_photo),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onModeChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TextCapture(text: String, onTextChange: (String) -> Unit) {
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(stringResource(R.string.field_describe_event)) },
            placeholder = { Text(stringResource(R.string.hint_text_example)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.hint_text_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VoiceCapture(
    isListening: Boolean,
    micLevel: Float,
    partial: String,
    transcript: String,
    onTextChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    // The button swells with the microphone level, so the user can see it is hearing them.
    val scale by animateFloatAsState(
        targetValue = if (isListening) 1f + micLevel * 0.25f else 1f,
        label = "micScale",
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    if (isListening) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                )
                .clickable { if (isListening) onStop() else onStart() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = stringResource(
                    if (isListening) R.string.action_stop_listening else R.string.action_start_listening,
                ),
                modifier = Modifier.size(44.dp),
                tint = if (isListening) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            )
        }

        Spacer(Modifier.size(10.dp))
        Text(
            text = stringResource(
                if (isListening) R.string.voice_listening else R.string.voice_tap_to_speak,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        AnimatedVisibility(visible = partial.isNotBlank()) {
            Text(
                text = partial,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(12.dp),
            )
        }

        Spacer(Modifier.size(12.dp))
        OutlinedTextField(
            value = transcript,
            onValueChange = onTextChange,
            label = { Text(stringResource(R.string.field_transcript)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
        )
    }
}

@Composable
private fun PhotoCapture(
    state: CaptureUiState,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onClear: () -> Unit,
    onTextChange: (String) -> Unit,
    onInstallKhmerOcr: () -> Unit,
) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onTakePhoto, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.action_take_photo), maxLines = 1)
            }
            OutlinedButton(onClick = onPickPhoto, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.action_pick_photo), maxLines = 1)
            }
        }

        state.photo?.let { bitmap ->
            Spacer(Modifier.size(12.dp))
            Box {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_clear_photo),
                    )
                }
            }
        }

        if (state.khmerOcrMissing) {
            Spacer(Modifier.size(10.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.ocr_khmer_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Spacer(Modifier.size(6.dp))
                    if (state.installingKhmerOcr) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        TextButton(onClick = onInstallKhmerOcr) {
                            Text(stringResource(R.string.action_install_khmer_ocr))
                        }
                    }
                }
            }
        }

        state.ocrEngine?.let { engine ->
            if (engine != OcrEngineKind.NONE) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(
                        R.string.ocr_engine_used,
                        stringResource(
                            when (engine) {
                                OcrEngineKind.KHMER -> R.string.ocr_engine_khmer
                                else -> R.string.ocr_engine_latin
                            },
                        ),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChange,
            label = { Text(stringResource(R.string.field_recognised_text)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
        )
    }
}

@Composable
private fun ModelStatusBanner(llmState: LlmState, onOpenSettings: () -> Unit) {
    val (text, showAction) = when (llmState) {
        is LlmState.Ready -> stringResource(R.string.model_ready, llmState.modelName) to false
        LlmState.Loading -> stringResource(R.string.model_loading) to false
        LlmState.NotInstalled -> stringResource(R.string.model_not_installed) to true
        is LlmState.Failed -> stringResource(R.string.model_failed) to true
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (showAction) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.action_set_up))
                }
            }
        }
    }
}

@Composable
private fun MessageBanner(text: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun messageText(message: CaptureMessage): String = when (message) {
    CaptureMessage.CannotReadImage -> stringResource(R.string.error_cannot_read_image)
    CaptureMessage.NothingFound -> stringResource(R.string.error_nothing_found)
    CaptureMessage.KhmerOcrInstalled -> stringResource(R.string.ocr_khmer_installed)
    is CaptureMessage.KhmerOcrFailed ->
        stringResource(R.string.ocr_khmer_failed, message.reason)
    is CaptureMessage.Speech -> stringResource(speechErrorRes(message.code))
}

private fun speechErrorRes(code: Int): Int = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> R.string.speech_error_audio
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.speech_error_permission
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
        R.string.speech_error_network
    SpeechRecognizer.ERROR_NO_MATCH -> R.string.speech_error_no_match
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.speech_error_busy
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.speech_error_timeout
    else -> R.string.speech_error_generic
}

@Composable
private fun noticeText(notice: ExtractionResult.Notice): String = when (notice) {
    ExtractionResult.Notice.MODEL_NOT_INSTALLED -> stringResource(R.string.notice_rules_only)
    ExtractionResult.Notice.MODEL_FAILED -> stringResource(R.string.notice_model_failed)
    ExtractionResult.Notice.DATE_GUESSED -> stringResource(R.string.notice_date_guessed)
}

// ---- camera file plumbing ---------------------------------------------------

/** A fresh file per shot, exposed through the app's FileProvider. */
private fun newCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
