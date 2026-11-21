package com.example.network

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor as AndroidMediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.AudioOutputFormat
import com.example.data.DownloadEntity
import com.example.data.DownloadStatus
import com.example.data.MediaType
import com.example.data.MediaVaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit

class MediaDownloadEngine(
    private val context: Context,
    private val repository: MediaVaultRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    fun getVaultDirectory(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(base, "PureSnap").apply { if (!exists()) mkdirs() }
    }

    data class BatchDownloadItem(
        val title: String,
        val sourceUrl: String,
        val variant: MediaStreamVariant,
        val thumbnailUrl: String?
    )

    fun startDownload(
        title: String,
        sourceUrl: String,
        variant: MediaStreamVariant,
        thumbnailUrl: String?,
        wifiOnly: Boolean = false,
        autoExtractFormat: AudioOutputFormat? = null,
        extractBitrateKbps: Int = 192
    ) {
        startBatchDownloads(
            items = listOf(BatchDownloadItem(title, sourceUrl, variant, thumbnailUrl)),
            autoExtractFormat = autoExtractFormat,
            extractBitrateKbps = extractBitrateKbps,
            wifiOnly = wifiOnly
        )
    }

    fun startBatchDownloads(
        items: List<BatchDownloadItem>,
        autoExtractFormat: AudioOutputFormat? = null,
        extractBitrateKbps: Int = 192,
        wifiOnly: Boolean = false
    ) {
        if (items.isEmpty()) return
        scope.launch {
            for (item in items) {
                val safeTitle = sanitizeFilename(item.title)
                val ext = when {
                    item.variant.isHls -> "ts"
                    else -> item.variant.formatLabel.lowercase(Locale.US).ifBlank { "mp4" }
                }
                val destFile = createUniqueFile(getVaultDirectory(), safeTitle, ext)

                val entity = DownloadEntity(
                    title = item.title,
                    sourceUrl = item.sourceUrl,
                    mediaUrl = item.variant.mediaUrl,
                    thumbnailUrl = item.thumbnailUrl,
                    localFilePath = destFile.absolutePath,
                    mimeType = if (item.variant.isHls) "video/mp2t" else item.variant.mimeType,
                    mediaType = item.variant.mediaType,
                    formatLabel = if (item.variant.isHls) "TS" else item.variant.formatLabel,
                    qualityLabel = item.variant.qualityLabel,
                    totalBytes = item.variant.estimatedBytes,
                    downloadedBytes = 0L,
                    status = DownloadStatus.QUEUED
                )

                val id = repository.insertDownload(entity)
                val extractForThis = if (item.variant.mediaType == MediaType.VIDEO && !item.variant.isHls) {
                    autoExtractFormat
                } else {
                    null
                }
                enqueueWorkForDownload(
                    downloadId = id,
                    wifiOnly = wifiOnly,
                    policy = ExistingWorkPolicy.REPLACE,
                    autoExtractFormat = extractForThis,
                    extractBitrateKbps = extractBitrateKbps
                )
            }
        }
    }

    fun retryDownload(entity: DownloadEntity, wifiOnly: Boolean = false) {
        val destFile = File(entity.localFilePath)
        destFile.parentFile?.mkdirs()
        scope.launch {
            repository.updateProgress(
                id = entity.id,
                downloadedBytes = entity.downloadedBytes,
                totalBytes = entity.totalBytes,
                speedBytesPerSec = 0L,
                status = DownloadStatus.QUEUED
            )
            enqueueWorkForDownload(entity.id, wifiOnly = wifiOnly, policy = ExistingWorkPolicy.REPLACE)
        }
    }

    fun cancelDownload(downloadId: Long) {
        scope.launch {
            val existing = repository.getById(downloadId)
            repository.updateStatus(downloadId, DownloadStatus.CANCELLED, "Cancelled by user")
            workManager.cancelUniqueWork(uniqueWorkName(downloadId))
            existing?.let { repository.cleanupTempArtifacts(it.localFilePath) }
        }
    }

    /**
     * Re-attaches WorkManager jobs for any incomplete downloads found in Room after app restart
     * or process death, ensuring partial `.part.*` / `.seg.*` chunks continue seamlessly.
     */
    fun resumePendingDownloadsIfNeeded(wifiOnly: Boolean = false) {
        scope.launch {
            val incomplete = repository.getIncompleteDownloads()
            for (entity in incomplete) {
                val workInfos = runCatching {
                    workManager.getWorkInfosForUniqueWork(uniqueWorkName(entity.id)).get()
                }.getOrDefault(emptyList())

                val isAlreadyActive = workInfos.any {
                    it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED
                }
                if (!isAlreadyActive) {
                    enqueueWorkForDownload(
                        downloadId = entity.id,
                        wifiOnly = wifiOnly,
                        policy = ExistingWorkPolicy.KEEP
                    )
                }
            }
        }
    }

    private suspend fun enqueueWorkForDownload(
        downloadId: Long,
        wifiOnly: Boolean,
        policy: ExistingWorkPolicy,
        autoExtractFormat: AudioOutputFormat? = null,
        extractBitrateKbps: Int = 192
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()

        val data = workDataOf(
            MediaDownloadWorker.KEY_DOWNLOAD_ID to downloadId,
            MediaDownloadWorker.KEY_AUTO_EXTRACT_FORMAT to autoExtractFormat?.name,
            MediaDownloadWorker.KEY_AUTO_EXTRACT_BITRATE to extractBitrateKbps
        )

        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG_MEDIA_DOWNLOAD)
            .build()

        workManager.enqueueUniqueWork(uniqueWorkName(downloadId), policy, request)
        repository.updateWorkId(downloadId, request.id.toString())
    }

    /**
     * Extracts the native audio track from a local MP4/MOV video into a standalone .m4a file
     * using Android's MediaExtractor + MediaMuxer without external native libraries.
     */
    suspend fun extractAudioToM4a(source: DownloadEntity): Result<DownloadEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val inputFile = File(source.localFilePath)
                require(inputFile.exists()) { "Source video file no longer exists." }

                val audioOutFile = createUniqueFile(
                    getVaultDirectory(),
                    sanitizeFilename("${source.title}_audio"),
                    "m4a"
                )

                val extractor = AndroidMediaExtractor()
                var muxer: MediaMuxer? = null
                try {
                    extractor.setDataSource(inputFile.absolutePath)
                    var audioTrackIndex = -1
                    var audioFormat: MediaFormat? = null

                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("audio/")) {
                            audioTrackIndex = i
                            audioFormat = format
                            break
                        }
                    }

                    require(audioTrackIndex >= 0 && audioFormat != null) {
                        "No extractable audio track found in this video container."
                    }

                    extractor.selectTrack(audioTrackIndex)
                    muxer = MediaMuxer(
                        audioOutFile.absolutePath,
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    )
                    val muxerTrackIndex = muxer.addTrack(audioFormat)
                    muxer.start()

                    val maxInputSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
                    } else {
                        256 * 1024
                    }

                    val buffer = ByteBuffer.allocate(maxInputSize)
                    val bufferInfo = android.media.MediaCodec.BufferInfo()

                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = extractor.sampleTime
                        bufferInfo.flags = extractor.sampleFlags

                        muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                        extractor.advance()
                    }
                    muxer.stop()
                } finally {
                    runCatching { muxer?.release() }
                    runCatching { extractor.release() }
                }

                val size = audioOutFile.length()
                val newEntity = DownloadEntity(
                    title = "${source.title} (Audio)",
                    sourceUrl = source.sourceUrl,
                    mediaUrl = source.mediaUrl,
                    thumbnailUrl = source.thumbnailUrl,
                    localFilePath = audioOutFile.absolutePath,
                    mimeType = "audio/mp4",
                    mediaType = MediaType.AUDIO,
                    formatLabel = "M4A",
                    qualityLabel = "Extracted Audio",
                    totalBytes = size,
                    downloadedBytes = size,
                    speedBytesPerSec = 0L,
                    status = DownloadStatus.COMPLETED
                )
                val newId = repository.insertDownload(newEntity)
                newEntity.copy(id = newId)
            }
        }

    /**
     * Exports a completed vault item into the public Downloads/PureSnap folder via MediaStore (API 29+)
     * with zero storage permissions required.
     */
    suspend fun exportToPublicDownloads(entity: DownloadEntity): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sourceFile = File(entity.localFilePath)
                require(sourceFile.exists()) { "File not found in vault." }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
                        put(MediaStore.Downloads.MIME_TYPE, entity.mimeType)
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            "${Environment.DIRECTORY_DOWNLOADS}/PureSnap"
                        )
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Failed to create MediaStore entry")

                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    } ?: throw IllegalStateException("Unable to open output stream")

                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    repository.markExported(entity.id)
                    "Saved to Downloads/PureSnap/${sourceFile.name}"
                } else {
                    repository.markExported(entity.id)
                    "Saved in app vault (${sourceFile.name}). Use Share to copy externally."
                }
            }
        }

    private fun sanitizeFilename(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|]+"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return cleaned.take(60).ifBlank { "puresnap_media" }
    }

    private fun createUniqueFile(dir: File, baseName: String, ext: String): File {
        var candidate = File(dir, "$baseName.$ext")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${baseName}_$counter.$ext")
            counter++
        }
        return candidate
    }

    companion object {
        const val TAG_MEDIA_DOWNLOAD = "puresnap_media_download"
        fun uniqueWorkName(downloadId: Long): String = "puresnap_download_$downloadId"
    }
}
