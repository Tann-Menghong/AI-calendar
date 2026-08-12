package com.aicalendar.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Live state of a dictation session. */
sealed interface SpeechState {
    data object Idle : SpeechState
    data object Listening : SpeechState

    /** Microphone level 0f..1f, for the waveform. */
    data class Level(val rms: Float) : SpeechState

    /** Words recognised so far; not final. */
    data class Partial(val text: String) : SpeechState
    data class Final(val text: String) : SpeechState

    /** Raw [SpeechRecognizer] error code; the UI maps it to a localised string. */
    data class Error(val code: Int) : SpeechState
}

/**
 * Wraps Android's [SpeechRecognizer] as a flow.
 *
 * Khmer (`km-KH`) is supported by Google's recogniser; whether it works without a
 * network connection depends on whether the user has downloaded the offline pack in
 * system settings, so [SpeechState.Error] carries the code for a useful message.
 */
class SpeechToText(private val context: Context) {

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts listening in [languageTag] and emits until the recogniser finishes.
     * Cancelling the collection stops and releases the recogniser.
     */
    fun listen(languageTag: String): Flow<SpeechState> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(SpeechState.Error(ERROR_UNAVAILABLE))
            close()
            return@callbackFlow
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechState.Listening)
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                // The API reports roughly -2..10 dB; normalise for the level meter.
                trySend(SpeechState.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                trySend(SpeechState.Error(error))
                close()
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                trySend(SpeechState.Final(text))
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) trySend(SpeechState.Partial(text))
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        recognizer.setRecognitionListener(listener)
        recognizer.startListening(buildIntent(languageTag))

        awaitClose {
            runCatching { recognizer.stopListening() }
            runCatching { recognizer.destroy() }
        }
    }

    private fun buildIntent(languageTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            // Khmer speakers frequently mix in English words, so keep the fallback open.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    companion object {
        const val ERROR_UNAVAILABLE = -1

        /** Offered in Settings; the recogniser falls back to the system default if unsupported. */
        val SUPPORTED_LOCALES = listOf(
            "km-KH" to "ភាសាខ្មែរ (Khmer)",
            "en-US" to "English (US)",
            "en-GB" to "English (UK)",
        )
    }
}
