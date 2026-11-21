package com.example.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.AudioConversionDao
import com.example.data.AudioConversionEntity
import com.example.data.AudioOutputFormat
import com.example.data.ConversionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

data class StagedVideoInput(
    val displayName: String,
    val sourceUri: String,
    val isVaultItem: Boolean = false
)

class BatchConversionEngine(
    private val context: Context,
    private val conversionDao: AudioConversionDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    val conversionsFlow: Flow<List<AudioConversionEntity>> = conversionDao.observeAll()

    fun getAudioOutputDirectory(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(base, "PureSnap/Audio").apply { if (!exists()) mkdirs() }
    }

    fun resolveDisplayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "local_video" } ?: "local_video"
    }

    fun enqueueBatchConversions(
        items: List<StagedVideoInput>,
        targetFormat: AudioOutputFormat,
        bitrateKbps: Int
    ) {
        if (items.isEmpty()) return
        scope.launch {
            val outDir = getAudioOutputDirectory()
            val requests = mutableListOf<Pair<Long, androidx.work.OneTimeWorkRequest>>()

            for (item in items) {
                if (item.sourceUri.startsWith("content://")) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            Uri.parse(item.sourceUri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }

                val safeBase = sanitizeBaseName(item.displayName.substringBeforeLast('.'))
                val outFile = createUniqueAudioFile(outDir, safeBase, targetFormat.extension)

                val entity = AudioConversionEntity(
                    sourceName = item.displayName,
                    sourceUri = item.sourceUri,
                    outputFilePath = outFile.absolutePath,
                    targetFormat = targetFormat,
                    bitrateKbps = bitrateKbps,
                    status = ConversionStatus.QUEUED
                )
                val id = conversionDao.insert(entity)
                val req = buildWorkRequest(id)
                requests.add(id to req)
            }

            // Enqueue individually by unique ID so WorkManager executes cleanly and persists across process death
            for ((id, req) in requests) {
                workManager.enqueueUniqueWork(uniqueWorkName(id), ExistingWorkPolicy.REPLACE, req)
                conversionDao.updateWorkId(id, req.id.toString())
            }
        }
    }

    fun cancelConversion(conversionId: Long) {
        scope.launch {
            conversionDao.updateStatus(conversionId, ConversionStatus.CANCELLED, "Cancelled by user")
            workManager.cancelUniqueWork(uniqueWorkName(conversionId))
        }
    }

    fun retryConversion(entity: AudioConversionEntity) {
        scope.launch {
            conversionDao.updateProgress(entity.id, 0, 0L, ConversionStatus.QUEUED)
            val req = buildWorkRequest(entity.id)
            workManager.enqueueUniqueWork(uniqueWorkName(entity.id), ExistingWorkPolicy.REPLACE, req)
            conversionDao.updateWorkId(entity.id, req.id.toString())
        }
    }

    fun deleteConversion(entity: AudioConversionEntity) {
        scope.launch {
            if (entity.status == ConversionStatus.QUEUED || entity.status == ConversionStatus.EXTRACTING) {
                workManager.cancelUniqueWork(uniqueWorkName(entity.id))
            }
            conversionDao.deleteById(entity.id)
        }
    }

    fun clearInactiveConversions() {
        scope.launch {
            conversionDao.clearInactive()
        }
    }

    fun resumePendingConversionsIfNeeded() {
        scope.launch {
            val incomplete = conversionDao.getIncomplete()
            for (entity in incomplete) {
                val infos = runCatching {
                    workManager.getWorkInfosForUniqueWork(uniqueWorkName(entity.id)).get()
                }.getOrDefault(emptyList())
                val active = infos.any {
                    it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED
                }
                if (!active) {
                    val req = buildWorkRequest(entity.id)
                    workManager.enqueueUniqueWork(uniqueWorkName(entity.id), ExistingWorkPolicy.KEEP, req)
                    conversionDao.updateWorkId(entity.id, req.id.toString())
                }
            }
        }
    }

    private fun buildWorkRequest(conversionId: Long): androidx.work.OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .build()

        return OneTimeWorkRequestBuilder<AudioExtractionWorker>()
            .setInputData(workDataOf(AudioExtractionWorker.KEY_CONVERSION_ID to conversionId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG_AUDIO_EXTRACTION)
            .build()
    }

    private fun sanitizeBaseName(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|]+"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return cleaned.take(55).ifBlank { "extracted_audio" }
    }

    private fun createUniqueAudioFile(dir: File, baseName: String, ext: String): File {
        var candidate = File(dir, "$baseName.$ext")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${baseName}_$counter.$ext")
            counter++
        }
        return candidate
    }

    companion object {
        const val TAG_AUDIO_EXTRACTION = "puresnap_audio_extraction"
        fun uniqueWorkName(conversionId: Long): String = "puresnap_extract_$conversionId"
    }
}
