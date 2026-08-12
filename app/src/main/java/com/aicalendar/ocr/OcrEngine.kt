package com.aicalendar.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.aicalendar.nlp.KhmerText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Which recogniser produced the text, shown so the user knows what to fix. */
enum class OcrEngineKind { LATIN, KHMER, NONE }

data class OcrResult(
    val text: String,
    val engine: OcrEngineKind,
    /** True when the image clearly held Khmer but no Khmer data pack is installed. */
    val khmerDataMissing: Boolean = false,
)

/**
 * Reads text out of a photo.
 *
 * ML Kit's bundled recogniser covers Latin script but has no Khmer model, so Khmer
 * pages go through Tesseract with `khm.traineddata`. Both run when the Khmer pack is
 * installed and the richer result wins — a photo of a Cambodian invitation is usually
 * mixed script, and neither engine alone reads all of it.
 */
class OcrEngine(
    private val context: Context,
    private val tessData: TessDataInstaller = TessDataInstaller(context),
) {

    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        val prepared = downscaleForOcr(bitmap)
        val latin = runCatching { recognizeLatin(prepared) }.getOrElse {
            Log.w(TAG, "Latin OCR failed", it)
            ""
        }

        if (!tessData.isKhmerInstalled()) {
            // Latin OCR renders Khmer glyphs as punctuation soup; if that is all we
            // got, tell the caller the data pack is what is missing.
            val looksEmpty = latin.count { it.isLetterOrDigit() } < MIN_USEFUL_CHARS
            return@withContext OcrResult(
                text = latin.trim(),
                engine = if (latin.isBlank()) OcrEngineKind.NONE else OcrEngineKind.LATIN,
                khmerDataMissing = looksEmpty,
            )
        }

        val khmer = runCatching { recognizeKhmer(prepared) }.getOrElse {
            Log.w(TAG, "Khmer OCR failed", it)
            ""
        }

        val khmerChars = khmer.count { KhmerText.isKhmer(it) }
        val latinChars = latin.count { it.isLetterOrDigit() && it.code < 0x80 }
        when {
            khmerChars >= MIN_USEFUL_CHARS && khmerChars >= latinChars ->
                OcrResult(khmer.trim(), OcrEngineKind.KHMER)
            latin.isNotBlank() -> OcrResult(latin.trim(), OcrEngineKind.LATIN)
            khmer.isNotBlank() -> OcrResult(khmer.trim(), OcrEngineKind.KHMER)
            else -> OcrResult("", OcrEngineKind.NONE)
        }
    }

    private suspend fun recognizeLatin(bitmap: Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            latinRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result -> continuation.resume(result.text) }
                .addOnFailureListener { error ->
                    Log.w(TAG, "ML Kit failure", error)
                    continuation.resume("")
                }
                .addOnCanceledListener { continuation.resume("") }
        }

    private fun recognizeKhmer(bitmap: Bitmap): String = tessData.withKhmerApi { api ->
        api.setImage(bitmap)
        api.getUTF8Text().orEmpty()
    }

    /**
     * Tesseract slows down sharply above a few megapixels and gains nothing, while
     * ML Kit prefers a reasonably large image. ~2 MP is the sweet spot for both.
     */
    private fun downscaleForOcr(bitmap: Bitmap): Bitmap {
        val pixels = bitmap.width.toLong() * bitmap.height
        if (pixels <= MAX_OCR_PIXELS) return bitmap
        val scale = kotlin.math.sqrt(MAX_OCR_PIXELS.toDouble() / pixels)
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private companion object {
        const val TAG = "OcrEngine"
        const val MAX_OCR_PIXELS = 2_000_000L
        const val MIN_USEFUL_CHARS = 4
    }
}
