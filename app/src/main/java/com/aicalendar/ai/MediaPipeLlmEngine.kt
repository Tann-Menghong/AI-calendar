package com.aicalendar.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [LlmEngine] backed by MediaPipe's LLM Inference API, which runs quantised Gemma
 * `.task` bundles fully on-device.
 *
 * Inference is serialised behind a mutex: the native runtime holds one KV cache per
 * engine, so overlapping calls would corrupt each other.
 */
class MediaPipeLlmEngine(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LlmEngine {

    private val _state = MutableStateFlow<LlmState>(LlmState.NotInstalled)
    override val state: StateFlow<LlmState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var inference: LlmInference? = null
    private var loadedPath: String? = null
    private var visionEnabled: Boolean = false

    override suspend fun load(modelPath: String): Result<Unit> = mutex.withLock {
        if (loadedPath == modelPath && inference != null) return@withLock Result.success(Unit)
        val file = File(modelPath)
        if (!file.isFile || file.length() == 0L) {
            _state.value = LlmState.NotInstalled
            return@withLock Result.failure(IllegalStateException("Model file not found: $modelPath"))
        }

        _state.value = LlmState.Loading
        closeLocked()

        // Vision-capable bundles (Gemma 3n) accept images; text-only ones throw at
        // creation time if we ask for image slots, so fall back automatically.
        val attempts = listOf(true, false)
        var lastError: Throwable? = null
        for (wantVision in attempts) {
            val result = runCatching {
                withContext(io) {
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelPath)
                        .setMaxTokens(MAX_CONTEXT_TOKENS)
                        .setMaxTopK(TOP_K)
                        .apply { if (wantVision) setMaxNumImages(1) }
                        .build()
                    LlmInference.createFromOptions(context, options)
                }
            }
            result.onSuccess { created ->
                inference = created
                loadedPath = modelPath
                visionEnabled = wantVision
                _state.value = LlmState.Ready(file.nameWithoutExtension, wantVision)
                return@withLock Result.success(Unit)
            }
            lastError = result.exceptionOrNull()
            Log.w(TAG, "Model load failed (vision=$wantVision)", lastError)
        }

        val message = lastError?.message ?: "Unknown model load failure"
        _state.value = LlmState.Failed(message)
        Result.failure(lastError ?: IllegalStateException(message))
    }

    override fun unload() {
        inference?.runCatching { close() }
        inference = null
        loadedPath = null
        visionEnabled = false
        _state.value = LlmState.NotInstalled
    }

    override suspend fun generate(prompt: String, maxTokens: Int): Result<String> =
        runSession(prompt, image = null, maxTokens = maxTokens)

    override suspend fun generateFromImage(
        prompt: String,
        image: Bitmap,
        maxTokens: Int,
    ): Result<String> {
        if (!visionEnabled) {
            return Result.failure(UnsupportedOperationException("Loaded model has no vision encoder"))
        }
        return runSession(prompt, image, maxTokens)
    }

    private suspend fun runSession(
        prompt: String,
        image: Bitmap?,
        maxTokens: Int,
    ): Result<String> = mutex.withLock {
        val engine = inference
            ?: return@withLock Result.failure(IllegalStateException("Model not loaded"))

        runCatching {
            withContext(io) {
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(TOP_K)
                    .setTopP(TOP_P)
                    // Near-greedy: we want the same JSON for the same sentence, not variety.
                    .setTemperature(TEMPERATURE)
                    .apply {
                        if (image != null) {
                            setGraphOptions(
                                GraphOptions.builder().setEnableVisionModality(true).build(),
                            )
                        }
                    }
                    .build()

                LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
                    session.addQueryChunk(prompt)
                    if (image != null) {
                        session.addImage(BitmapImageBuilder(image).build())
                    }
                    session.generateResponse()
                }
            }
        }.onFailure { Log.w(TAG, "Generation failed", it) }
    }

    private fun closeLocked() {
        inference?.runCatching { close() }
        inference = null
        loadedPath = null
    }

    private companion object {
        const val TAG = "MediaPipeLlmEngine"

        /**
         * Small models on phones: a 1280-token window covers the prompt plus a long
         * photo transcript while keeping the KV cache within a few hundred megabytes.
         */
        const val MAX_CONTEXT_TOKENS = 1280
        const val TOP_K = 40
        const val TOP_P = 0.9f
        const val TEMPERATURE = 0.1f
    }
}
