package com.aicalendar.ui.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aicalendar.AiCalendarApp
import com.aicalendar.ai.EventExtractor
import com.aicalendar.ai.ExtractionResult
import com.aicalendar.ai.LlmEngine
import com.aicalendar.ai.LlmState
import com.aicalendar.data.model.CaptureSource
import com.aicalendar.data.model.EventDraft
import com.aicalendar.data.repo.EventRepository
import com.aicalendar.data.repo.SettingsRepository
import com.aicalendar.ocr.OcrEngine
import com.aicalendar.ocr.OcrEngineKind
import com.aicalendar.ocr.TessDataInstaller
import com.aicalendar.ocr.TessDataStatus
import com.aicalendar.voice.SpeechState
import com.aicalendar.voice.SpeechToText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.time.LocalDateTime

/** Anything the capture screen needs to tell the user, resolved to a string in the UI. */
sealed interface CaptureMessage {
    data object CannotReadImage : CaptureMessage
    data object NothingFound : CaptureMessage
    data object KhmerOcrInstalled : CaptureMessage
    data class KhmerOcrFailed(val reason: String) : CaptureMessage
    data class Speech(val code: Int) : CaptureMessage
}

data class CaptureUiState(
    val mode: CaptureSource = CaptureSource.TEXT,
    val text: String = "",
    val isListening: Boolean = false,
    val partialSpeech: String = "",
    val micLevel: Float = 0f,
    val photo: Bitmap? = null,
    val ocrEngine: OcrEngineKind? = null,
    val khmerOcrMissing: Boolean = false,
    val isProcessing: Boolean = false,
    val drafts: List<EventDraft> = emptyList(),
    val notice: ExtractionResult.Notice? = null,
    val message: CaptureMessage? = null,
    val installingKhmerOcr: Boolean = false,
    val llmState: LlmState = LlmState.NotInstalled,
    val savedCount: Int = 0,
) {
    val canExtract: Boolean get() = text.isNotBlank() && !isProcessing
}

/**
 * Drives the three capture paths. All of them converge on the same place: text goes
 * into [EventExtractor], drafts come out, and nothing is written to the calendar until
 * the user confirms.
 */
class CaptureViewModel(
    private val context: Context,
    private val extractor: EventExtractor,
    private val ocr: OcrEngine,
    private val speech: SpeechToText,
    private val events: EventRepository,
    private val settings: SettingsRepository,
    private val llm: LlmEngine,
    private val tessData: TessDataInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private var listenJob: Job? = null

    init {
        viewModelScope.launch {
            llm.state.collect { llmState -> _state.update { it.copy(llmState = llmState) } }
        }
    }

    fun setMode(mode: CaptureSource) {
        if (mode != CaptureSource.VOICE) stopListening()
        _state.update { it.copy(mode = mode, message = null) }
    }

    fun setText(text: String) {
        _state.update { it.copy(text = text, message = null) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null, notice = null) }
    }

    // ---- voice --------------------------------------------------------------

    fun startListening() {
        if (_state.value.isListening) return
        listenJob?.cancel()
        listenJob = viewModelScope.launch {
            val locale = settings.settings.first().speechLocale
            _state.update { it.copy(isListening = true, partialSpeech = "", message = null) }
            speech.listen(locale).collect { event ->
                when (event) {
                    is SpeechState.Listening -> _state.update { it.copy(isListening = true) }
                    is SpeechState.Level -> _state.update { it.copy(micLevel = event.rms) }
                    is SpeechState.Partial -> _state.update { it.copy(partialSpeech = event.text) }
                    is SpeechState.Final -> {
                        val heard = event.text.trim()
                        _state.update {
                            it.copy(
                                isListening = false,
                                partialSpeech = "",
                                micLevel = 0f,
                                // Append rather than replace, so several sentences can be
                                // dictated one after another.
                                text = if (it.text.isBlank()) heard else "${it.text} $heard",
                            )
                        }
                        if (heard.isNotBlank()) extract()
                    }
                    is SpeechState.Error -> _state.update {
                        it.copy(
                            isListening = false,
                            micLevel = 0f,
                            message = CaptureMessage.Speech(event.code),
                        )
                    }
                    SpeechState.Idle -> Unit
                }
            }
            _state.update { it.copy(isListening = false, micLevel = 0f) }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        _state.update { it.copy(isListening = false, micLevel = 0f, partialSpeech = "") }
    }

    // ---- photo --------------------------------------------------------------

    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isProcessing = true, message = null, mode = CaptureSource.PHOTO) }
            val bitmap = withContext(Dispatchers.IO) { decodeSampled(uri) }
            if (bitmap == null) {
                _state.update { it.copy(isProcessing = false, message = CaptureMessage.CannotReadImage) }
                return@launch
            }
            val result = ocr.recognize(bitmap)
            _state.update {
                it.copy(
                    photo = bitmap,
                    text = result.text,
                    ocrEngine = result.engine,
                    khmerOcrMissing = result.khmerDataMissing,
                    isProcessing = false,
                )
            }
            if (result.text.isNotBlank() || (llm.state.value as? LlmState.Ready)?.supportsVision == true) {
                extractFromPhoto(bitmap, result.text)
            }
        }
    }

    fun clearPhoto() {
        _state.update {
            it.copy(photo = null, ocrEngine = null, khmerOcrMissing = false, text = "", drafts = emptyList())
        }
    }

    // ---- extraction ---------------------------------------------------------

    fun extract() {
        val input = _state.value.text.trim()
        if (input.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isProcessing = true, message = null) }
            val useLlm = settings.settings.first().useLlm
            val result = extractor.extract(
                input = input,
                now = LocalDateTime.now(),
                source = _state.value.mode,
                useLlm = useLlm,
            )
            _state.update {
                it.copy(
                    isProcessing = false,
                    drafts = result.drafts,
                    notice = result.notice,
                    message = if (result.drafts.isEmpty()) CaptureMessage.NothingFound else null,
                )
            }
        }
    }

    private fun extractFromPhoto(bitmap: Bitmap, ocrText: String) {
        viewModelScope.launch {
            _state.update { it.copy(isProcessing = true) }
            val useLlm = settings.settings.first().useLlm
            val result = extractor.extractFromImage(bitmap, ocrText, LocalDateTime.now(), useLlm)
            _state.update {
                it.copy(
                    isProcessing = false,
                    drafts = result.drafts,
                    notice = result.notice,
                    message = if (result.drafts.isEmpty()) CaptureMessage.NothingFound else null,
                )
            }
        }
    }

    // ---- drafts -------------------------------------------------------------

    fun updateDraft(index: Int, draft: EventDraft) {
        _state.update { current ->
            current.copy(
                drafts = current.drafts.toMutableList().also {
                    if (index in it.indices) it[index] = draft
                },
            )
        }
    }

    fun discardDraft(index: Int) {
        _state.update { current ->
            current.copy(
                drafts = current.drafts.filterIndexed { i, _ -> i != index },
            )
        }
    }

    fun saveDraft(index: Int, onSaved: (Long) -> Unit = {}) {
        val draft = _state.value.drafts.getOrNull(index) ?: return
        viewModelScope.launch {
            val id = events.save(draft)
            discardDraft(index)
            _state.update { it.copy(savedCount = it.savedCount + 1) }
            onSaved(id)
        }
    }

    fun saveAll(onSaved: () -> Unit = {}) {
        val drafts = _state.value.drafts
        if (drafts.isEmpty()) return
        viewModelScope.launch {
            events.saveAll(drafts)
            _state.update { it.copy(drafts = emptyList(), savedCount = it.savedCount + drafts.size) }
            onSaved()
        }
    }

    /** Offered inline when a photo turns out to be Khmer and the pack is missing. */
    fun installKhmerOcr() {
        if (_state.value.installingKhmerOcr) return
        viewModelScope.launch {
            _state.update { it.copy(installingKhmerOcr = true, message = null) }
            tessData.downloadKhmer().collect { status ->
                when (status) {
                    is TessDataStatus.Progress -> Unit
                    is TessDataStatus.Installed -> {
                        settings.setKhmerOcrInstalled(true)
                        _state.update {
                            it.copy(
                                installingKhmerOcr = false,
                                khmerOcrMissing = false,
                                message = CaptureMessage.KhmerOcrInstalled,
                            )
                        }
                        // Re-read the photo now that Khmer glyphs can be recognised.
                        _state.value.photo?.let { photo -> rerunOcr(photo) }
                    }
                    is TessDataStatus.Failed -> _state.update {
                        it.copy(
                            installingKhmerOcr = false,
                            message = CaptureMessage.KhmerOcrFailed(status.message),
                        )
                    }
                }
            }
        }
    }

    private suspend fun rerunOcr(photo: Bitmap) {
        _state.update { it.copy(isProcessing = true) }
        val result = ocr.recognize(photo)
        _state.update {
            it.copy(
                text = result.text,
                ocrEngine = result.engine,
                khmerOcrMissing = result.khmerDataMissing,
                isProcessing = false,
            )
        }
        if (result.text.isNotBlank()) extractFromPhoto(photo, result.text)
    }

    override fun onCleared() {
        stopListening()
        super.onCleared()
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Decodes at most ~4 MP. Phone cameras produce 12 MP+ images that would blow the
     * heap once OCR and the vision encoder each hold a copy.
     */
    private fun decodeSampled(uri: Uri): Bitmap? {
        fun open(): InputStream? = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null

        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_PIXELS) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return open()?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    companion object {
        private const val MAX_PIXELS = 4_000_000L

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as AiCalendarApp
                val c = app.container
                CaptureViewModel(
                    context = app,
                    extractor = c.eventExtractor,
                    ocr = c.ocrEngine,
                    speech = c.speechToText,
                    events = c.eventRepository,
                    settings = c.settingsRepository,
                    llm = c.llmEngine,
                    tessData = c.tessDataInstaller,
                )
            }
        }
    }
}
