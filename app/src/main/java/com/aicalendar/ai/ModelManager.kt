package com.aicalendar.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Progress of an import or download, for the Settings progress bar. */
sealed interface ModelTransfer {
    data class Progress(val bytesCopied: Long, val totalBytes: Long?) : ModelTransfer {
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let { (bytesCopied.toFloat() / it).coerceIn(0f, 1f) }
    }

    data class Done(val file: File) : ModelTransfer
    data class Failed(val message: String) : ModelTransfer
}

/**
 * Installs and removes the on-device `.task` model bundle.
 *
 * The model is not shipped inside the APK: quantised Gemma bundles are several
 * hundred megabytes and are distributed under their own licence, so the user brings
 * their own file — either picked from storage or fetched from a URL they supply.
 */
class ModelManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    /** The installed bundle, or null when none has been set up yet. */
    fun installedModel(): File? =
        modelsDir.listFiles()
            ?.filter { it.isFile && it.length() > MIN_PLAUSIBLE_SIZE && it.name.endsWith(TASK_EXTENSION) }
            ?.maxByOrNull { it.lastModified() }

    fun deleteInstalledModel(): Boolean = installedModel()?.delete() ?: false

    fun freeSpaceBytes(): Long = modelsDir.usableSpace

    /** Copies a user-picked file into app storage, where the native runtime can mmap it. */
    fun importFrom(uri: Uri, displayName: String?): Flow<ModelTransfer> = flow {
        val name = sanitiseName(displayName ?: "model$TASK_EXTENSION")
        val resolver = context.contentResolver
        val declaredSize = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { len -> len > 0 } }
        }.getOrNull()

        val input = runCatching { resolver.openInputStream(uri) }.getOrNull()
        if (input == null) {
            emit(ModelTransfer.Failed("Cannot open the selected file"))
            return@flow
        }
        copyInto(input, File(modelsDir, name), declaredSize) { emit(it) }
    }.flowOn(Dispatchers.IO)

    /**
     * Streams a model from [url]. [authToken] is sent as a bearer token, which is what
     * gated Hugging Face repositories require.
     */
    fun downloadFrom(url: String, authToken: String?): Flow<ModelTransfer> = flow {
        val parsed = runCatching { URL(url) }.getOrNull()
        if (parsed == null || parsed.protocol !in ALLOWED_PROTOCOLS) {
            emit(ModelTransfer.Failed("Enter a valid https:// URL"))
            return@flow
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (parsed.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                if (!authToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer ${authToken.trim()}")
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                emit(
                    ModelTransfer.Failed(
                        when (code) {
                            401, 403 -> "Access denied ($code). Gated models need an access token."
                            404 -> "Not found (404). Check the download URL."
                            else -> "Download failed with HTTP $code"
                        },
                    ),
                )
                return@flow
            }
            val total = connection.contentLengthLong.takeIf { it > 0 }
            val name = sanitiseName(parsed.path.substringAfterLast('/').ifBlank { "model$TASK_EXTENSION" })
            copyInto(connection.inputStream, File(modelsDir, name), total) { emit(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Model download failed", e)
            emit(ModelTransfer.Failed(e.message ?: "Download failed"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private suspend inline fun copyInto(
        input: InputStream,
        target: File,
        totalBytes: Long?,
        emit: (ModelTransfer) -> Unit,
    ) {
        // Write to a temp file first so a cancelled transfer never leaves a
        // truncated bundle that the loader would try to mmap.
        val temp = File(target.parentFile, target.name + PART_EXTENSION)
        var copied = 0L
        try {
            input.use { source ->
                FileOutputStream(temp).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastEmit = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        copied += read
                        if (copied - lastEmit >= EMIT_EVERY_BYTES) {
                            lastEmit = copied
                            emit(ModelTransfer.Progress(copied, totalBytes))
                        }
                    }
                    out.fd.sync()
                }
            }
            if (copied < MIN_PLAUSIBLE_SIZE) {
                temp.delete()
                emit(ModelTransfer.Failed("File is too small to be a model bundle"))
                return
            }
            // Only one bundle is kept installed at a time.
            modelsDir.listFiles()?.forEach { if (it != temp) it.delete() }
            if (!temp.renameTo(target)) {
                temp.delete()
                emit(ModelTransfer.Failed("Could not finalise the model file"))
                return
            }
            emit(ModelTransfer.Progress(copied, totalBytes ?: copied))
            emit(ModelTransfer.Done(target))
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun sanitiseName(raw: String): String {
        val base = raw.substringAfterLast('/').replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        val named = base.ifBlank { "model" }
        return if (named.endsWith(TASK_EXTENSION)) named else "$named$TASK_EXTENSION"
    }

    companion object {
        private const val TAG = "ModelManager"
        const val TASK_EXTENSION = ".task"
        private const val PART_EXTENSION = ".part"
        private const val BUFFER_BYTES = 1 shl 16
        private const val EMIT_EVERY_BYTES = 2L * 1024 * 1024
        private const val MIN_PLAUSIBLE_SIZE = 16L * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private val ALLOWED_PROTOCOLS = setOf("https")

        /**
         * Bundles known to run in this app. Gemma 3n also reads images, which the
         * photo capture path uses when it is installed.
         */
        val SUGGESTED_MODELS = listOf(
            SuggestedModel(
                name = "Gemma 3 1B (int4)",
                approxSizeMb = 555,
                supportsVision = false,
                note = "Smallest option. Handles Khmer and English text well on 4 GB devices.",
                url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
            ),
            SuggestedModel(
                name = "Gemma 3n E2B (int4)",
                approxSizeMb = 3100,
                supportsVision = true,
                note = "Reads photos directly and is stronger on Khmer. Needs 6 GB+ RAM.",
                url = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task",
            ),
        )
    }
}

data class SuggestedModel(
    val name: String,
    val approxSizeMb: Int,
    val supportsVision: Boolean,
    val note: String,
    val url: String,
)
