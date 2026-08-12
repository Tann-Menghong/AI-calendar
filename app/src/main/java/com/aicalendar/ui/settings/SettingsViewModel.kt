package com.aicalendar.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aicalendar.AiCalendarApp
import com.aicalendar.ai.LlmEngine
import com.aicalendar.ai.LlmState
import com.aicalendar.ai.ModelManager
import com.aicalendar.ai.ModelTransfer
import com.aicalendar.data.repo.AppSettings
import com.aicalendar.data.repo.SettingsRepository
import com.aicalendar.ocr.TessDataInstaller
import com.aicalendar.ocr.TessDataStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ModelUiState(
    val transferring: Boolean = false,
    val bytesCopied: Long = 0,
    val totalBytes: Long? = null,
    val error: String? = null,
    val installedName: String? = null,
    val installedSizeBytes: Long = 0,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (bytesCopied.toFloat() / it).coerceIn(0f, 1f) }
}

data class OcrUiState(
    val installing: Boolean = false,
    val installed: Boolean = false,
    val sizeBytes: Long = 0,
    val error: String? = null,
)

class SettingsViewModel(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val modelManager: ModelManager,
    private val llm: LlmEngine,
    private val tessData: TessDataInstaller,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val llmState: StateFlow<LlmState> = llm.state

    private val _model = MutableStateFlow(ModelUiState())
    val model: StateFlow<ModelUiState> = _model.asStateFlow()

    private val _ocr = MutableStateFlow(OcrUiState())
    val ocr: StateFlow<OcrUiState> = _ocr.asStateFlow()

    private var transferJob: Job? = null

    init {
        refreshInstalledModel()
        refreshOcr()
    }

    fun importModel(uri: Uri) {
        startTransfer { modelManager.importFrom(uri, displayNameOf(uri)) }
    }

    fun downloadModel(url: String, token: String?) {
        startTransfer { modelManager.downloadFrom(url, token) }
    }

    fun cancelTransfer() {
        transferJob?.cancel()
        transferJob = null
        _model.value = _model.value.copy(transferring = false)
        refreshInstalledModel()
    }

    fun deleteModel() {
        viewModelScope.launch {
            llm.unload()
            modelManager.deleteInstalledModel()
            settingsRepo.setModelPath(null)
            refreshInstalledModel()
        }
    }

    fun setUseLlm(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setUseLlm(enabled)
            if (enabled) {
                modelManager.installedModel()?.let { llm.load(it.absolutePath) }
            } else {
                llm.unload()
            }
        }
    }

    fun setSpeechLocale(tag: String) {
        viewModelScope.launch { settingsRepo.setSpeechLocale(tag) }
    }

    fun setWeekStartsOnMonday(monday: Boolean) {
        viewModelScope.launch { settingsRepo.setWeekStartsOnMonday(monday) }
    }

    fun setDefaultReminder(minutes: Int) {
        viewModelScope.launch { settingsRepo.setDefaultReminderMinutes(minutes) }
    }

    fun installKhmerOcr() {
        if (_ocr.value.installing) return
        viewModelScope.launch {
            _ocr.value = _ocr.value.copy(installing = true, error = null)
            tessData.downloadKhmer().collect { status ->
                when (status) {
                    is TessDataStatus.Progress -> Unit
                    TessDataStatus.Installed -> {
                        settingsRepo.setKhmerOcrInstalled(true)
                        _ocr.value = OcrUiState(
                            installing = false,
                            installed = true,
                            sizeBytes = tessData.installedSizeBytes(),
                        )
                    }
                    is TessDataStatus.Failed -> _ocr.value =
                        _ocr.value.copy(installing = false, error = status.message)
                }
            }
        }
    }

    fun removeKhmerOcr() {
        viewModelScope.launch {
            tessData.removeKhmer()
            settingsRepo.setKhmerOcrInstalled(false)
            refreshOcr()
        }
    }

    private fun startTransfer(source: () -> kotlinx.coroutines.flow.Flow<ModelTransfer>) {
        transferJob?.cancel()
        _model.value = ModelUiState(transferring = true)
        transferJob = viewModelScope.launch {
            source().collect { event ->
                when (event) {
                    is ModelTransfer.Progress -> _model.value = _model.value.copy(
                        transferring = true,
                        bytesCopied = event.bytesCopied,
                        totalBytes = event.totalBytes,
                    )
                    is ModelTransfer.Done -> {
                        settingsRepo.setModelPath(event.file.absolutePath)
                        _model.value = _model.value.copy(transferring = false)
                        refreshInstalledModel()
                        // Load straight away so the user can test it without a restart.
                        val loaded = llm.load(event.file.absolutePath)
                        loaded.exceptionOrNull()?.let { error ->
                            _model.value = _model.value.copy(error = error.message)
                        }
                    }
                    is ModelTransfer.Failed -> _model.value = _model.value.copy(
                        transferring = false,
                        error = event.message,
                    )
                }
            }
        }
    }

    private fun refreshInstalledModel() {
        val file = modelManager.installedModel()
        _model.value = _model.value.copy(
            installedName = file?.name,
            installedSizeBytes = file?.length() ?: 0L,
        )
    }

    private fun refreshOcr() {
        _ocr.value = _ocr.value.copy(
            installed = tessData.isKhmerInstalled(),
            sizeBytes = tessData.installedSizeBytes(),
        )
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as AiCalendarApp
                val c = app.container
                SettingsViewModel(
                    context = app,
                    settingsRepo = c.settingsRepository,
                    modelManager = c.modelManager,
                    llm = c.llmEngine,
                    tessData = c.tessDataInstaller,
                )
            }
        }
    }
}
