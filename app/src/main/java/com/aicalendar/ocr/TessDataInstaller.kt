package com.aicalendar.ocr

import android.content.Context
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Progress of the Khmer language pack download. */
sealed interface TessDataStatus {
    data class Progress(val fraction: Float?) : TessDataStatus
    data object Installed : TessDataStatus
    data class Failed(val message: String) : TessDataStatus
}

/**
 * Manages `khm.traineddata`, the Khmer model Tesseract needs.
 *
 * It is roughly 4 MB and is fetched on demand rather than bundled, so users who only
 * ever photograph English text never pay for it.
 */
class TessDataInstaller(private val context: Context) {

    /** Tesseract expects a parent directory that *contains* `tessdata/`. */
    private val baseDir: File
        get() = File(context.filesDir, "tesseract").apply { mkdirs() }

    private val tessDataDir: File
        get() = File(baseDir, "tessdata").apply { mkdirs() }

    private fun khmerFile(): File = File(tessDataDir, "$KHMER_LANG.traineddata")

    private fun englishFile(): File = File(tessDataDir, "$ENGLISH_LANG.traineddata")

    fun isKhmerInstalled(): Boolean = khmerFile().let { it.isFile && it.length() > MIN_SIZE }

    fun installedSizeBytes(): Long = khmerFile().takeIf { it.isFile }?.length() ?: 0L

    fun removeKhmer(): Boolean = khmerFile().delete()

    /**
     * Opens a Tesseract session configured for Khmer (plus English when installed,
     * since Khmer documents routinely mix in Latin digits and names).
     */
    fun <T> withKhmerApi(block: (TessBaseAPI) -> T): T {
        check(isKhmerInstalled()) { "Khmer language data is not installed" }
        val languages = if (englishFile().isFile) "$KHMER_LANG+$ENGLISH_LANG" else KHMER_LANG
        val api = TessBaseAPI()
        try {
            check(api.init(baseDir.absolutePath, languages)) { "Tesseract init failed" }
            return block(api)
        } finally {
            runCatching { api.recycle() }
        }
    }

    fun downloadKhmer(): Flow<TessDataStatus> = flow {
        if (isKhmerInstalled()) {
            emit(TessDataStatus.Installed)
            return@flow
        }
        emit(TessDataStatus.Progress(0f))
        val result = runCatching { download(KHMER_URL, khmerFile()) { emit(TessDataStatus.Progress(it)) } }
        result.onFailure {
            Log.w(TAG, "Khmer traineddata download failed", it)
            khmerFile().delete()
            emit(TessDataStatus.Failed(it.message ?: "Download failed"))
            return@flow
        }
        // English is a small extra that markedly improves mixed-script pages.
        runCatching { download(ENGLISH_URL, englishFile()) {} }
        emit(TessDataStatus.Installed)
    }.flowOn(Dispatchers.IO)

    private suspend inline fun download(
        url: String,
        target: File,
        emit: (Float?) -> Unit,
    ) {
        val temp = File(target.parentFile, target.name + ".part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        try {
            val code = connection.responseCode
            check(code in 200..299) { "HTTP $code" }
            val total = connection.contentLengthLong.takeIf { it > 0 }
            var copied = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { out ->
                    val buffer = ByteArray(1 shl 15)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        copied += read
                        emit(total?.let { (copied.toFloat() / it).coerceIn(0f, 1f) })
                    }
                }
            }
            check(copied > MIN_SIZE) { "Downloaded file is too small" }
            check(temp.renameTo(target)) { "Could not save language data" }
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }

    private companion object {
        const val TAG = "TessDataInstaller"
        const val KHMER_LANG = "khm"
        const val ENGLISH_LANG = "eng"
        const val MIN_SIZE = 100_000L

        // tessdata_fast keeps the pack small enough for a phone download while
        // staying accurate on printed Khmer.
        const val KHMER_URL =
            "https://github.com/tesseract-ocr/tessdata_fast/raw/main/khm.traineddata"
        const val ENGLISH_URL =
            "https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata"
    }
}
