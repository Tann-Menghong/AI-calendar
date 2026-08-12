package com.aicalendar.ai

import android.graphics.Bitmap

/** State of the on-device model, surfaced in Settings and on the capture screen. */
sealed interface LlmState {
    data object NotInstalled : LlmState
    data object Loading : LlmState
    data class Ready(val modelName: String, val supportsVision: Boolean) : LlmState
    data class Failed(val message: String) : LlmState
}

/**
 * The local generative model. Everything runs on the device — no request ever leaves
 * the phone, which is why the app works offline and needs no API key.
 */
interface LlmEngine {

    val state: kotlinx.coroutines.flow.StateFlow<LlmState>

    /** Loads a `.task` bundle from disk. Safe to call repeatedly with the same path. */
    suspend fun load(modelPath: String): Result<Unit>

    /** Frees native memory and returns to [LlmState.NotInstalled]. */
    fun unload()

    /** Runs a single-turn completion. Fails fast when the model is not loaded. */
    suspend fun generate(prompt: String, maxTokens: Int = DEFAULT_MAX_TOKENS): Result<String>

    /**
     * Runs a completion with an image, for models with a vision encoder (Gemma 3n).
     * Returns a failure when the loaded model is text-only.
     */
    suspend fun generateFromImage(
        prompt: String,
        image: Bitmap,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): Result<String>

    companion object {
        const val DEFAULT_MAX_TOKENS = 512
    }
}
