package com.example.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.R
import com.example.data.AppDatabase
import com.example.data.AudioOutputFormat
import com.example.data.DownloadEntity
import com.example.data.DownloadStatus
import com.example.data.MediaType
import com.example.data.MediaVaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MediaDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val database = AppDatabase.getInstance(appContext)
    private val repository = MediaVaultRepository(database.downloadDao())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun doWork(): Result {
        val downloadId = inputData.getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId <= 0L) return Result.failure()

        val initialEntity = repository.getById(downloadId) ?: return Result.failure()
        if (initialEntity.status == DownloadStatus.COMPLETED ||
            initialEntity.status == DownloadStatus.CANCELLED
        ) {
            return Result.success()
        }

        val destFile = File(initialEntity.localFilePath)
        destFile.parentFile?.mkdirs()

        // Promote to foreground service (dataSync) immediately so downloads survive app exit/process kill
        setForeground(
            createForegroundInfo(
                downloadId = downloadId,
                title = initialEntity.title,
                progressText = "Preparing resumable download…",
                progressPercent = -1
            )
        )

        return try {
            repository.updateProgress(
                id = downloadId,
                downloadedBytes = initialEntity.downloadedBytes,
                totalBytes = initialEntity.totalBytes,
                speedBytesPerSec = 0L,
                status = DownloadStatus.DOWNLOADING
            )

            val isHls = initialEntity.mediaUrl.lowercase(Locale.US).contains(".m3u8")
            if (isHls) {
                downloadHlsResumable(initialEntity, destFile)
            } else {
                downloadMultiThreadedResumable(initialEntity, destFile)
            }

            // Verify entity was not cancelled by user while finishing
            val latest = repository.getById(downloadId)
            if (latest == null || latest.status == DownloadStatus.CANCELLED) {
                repository.cleanupTempArtifacts(initialEntity.localFilePath)
                return Result.success()
            }

            val finalSize = destFile.length()
            repository.updateProgress(
                id = downloadId,
                downloadedBytes = finalSize,
                totalBytes = finalSize,
                speedBytesPerSec = 0L,
                status = DownloadStatus.COMPLETED
            )

            // If user requested automatic MP3/AAC extraction on completion, enqueue via WorkManager
            val autoFormatName = inputData.getString(KEY_AUTO_EXTRACT_FORMAT)
            val autoFormat = autoFormatName?.let { runCatching { AudioOutputFormat.valueOf(it) }.getOrNull() }
            if (autoFormat != null && initialEntity.mediaType == MediaType.VIDEO) {
                val bitrate = inputData.getInt(KEY_AUTO_EXTRACT_BITRATE, 192)
                BatchConversionEngine(applicationContext, database.conversionDao())
                    .enqueueBatchConversions(
                        items = listOf(
                            StagedVideoInput(
                                displayName = initialEntity.title,
                                sourceUri = destFile.absolutePath,
                                isVaultItem = true
                            )
                        ),
                        targetFormat = autoFormat,
                        bitrateKbps = bitrate
                    )
            }
            Result.success()
        } catch (cancel: CancellationException) {
            // Preserve .part files unless the user explicitly cancelled in Room
            val latest = repository.getById(downloadId)
            if (latest?.status == DownloadStatus.CANCELLED) {
                repository.cleanupTempArtifacts(initialEntity.localFilePath)
            }
            throw cancel
        } catch (httpErr: NonRetryableHttpException) {
            repository.cleanupTempArtifacts(initialEntity.localFilePath)
            repository.updateStatus(downloadId, DownloadStatus.FAILED, httpErr.message)
            Result.failure()
        } catch (ioErr: IOException) {
            // Transient network interruption: keep .part files on disk and let WorkManager retry
            repository.updateStatus(
                downloadId,
                DownloadStatus.QUEUED,
                "Waiting for network to resume (${ioErr.message ?: "connection lost"})"
            )
            Result.retry()
        } catch (err: Exception) {
            repository.updateStatus(
                downloadId,
                DownloadStatus.FAILED,
                err.message ?: "Download failed"
            )
            Result.failure()
        }
    }

    private suspend fun downloadMultiThreadedResumable(
        entity: DownloadEntity,
        destFile: File
    ) = coroutineScope {
        val probe = probeRemoteResource(entity.mediaUrl)

        // If ETag changed since previous partial session, discard stale partial files
        if (entity.etag != null && probe.etag != null && entity.etag != probe.etag) {
            repository.cleanupTempArtifacts(entity.localFilePath)
        }

        val totalBytes = if (probe.totalBytes > 0L) probe.totalBytes else entity.totalBytes
        val ranges = ChunkDownloadPlanner.planChunks(
            totalBytes = totalBytes,
            supportsRange = probe.supportsRange,
            maxThreads = ChunkDownloadPlanner.DEFAULT_MAX_THREADS
        )

        val threadCount = ranges.size.coerceAtLeast(1)
        repository.updateTransferMetadata(
            id = entity.id,
            etag = probe.etag,
            supportsRange = probe.supportsRange,
            threadCount = threadCount,
            totalBytes = totalBytes
        )

        // Fallback for unknown Content-Length servers: single non-chunked stream
        if (ranges.isEmpty()) {
            downloadSingleStreamUnknownLength(entity, destFile)
            return@coroutineScope
        }

        val partFiles = ranges.map { range -> File("${destFile.absolutePath}.part.${range.index}") }

        // Sum already-persisted partial bytes on disk from any previous killed run
        val initialDiskBytes = partFiles.zip(ranges).sumOf { (file, range) ->
            if (file.exists()) file.length().coerceAtMost(range.expectedLength) else 0L
        }
        val totalDownloadedCounter = AtomicLong(initialDiskBytes)
        val intervalBytesCounter = AtomicLong(0L)

        // Periodically publish progress to Room and Foreground Notification
        val progressReporter = launch(Dispatchers.IO) {
            var lastTime = System.currentTimeMillis()
            while (isActive) {
                delay(350L)
                val now = System.currentTimeMillis()
                val elapsed = (now - lastTime).coerceAtLeast(1L)
                val recentBytes = intervalBytesCounter.getAndSet(0L)
                val speed = (recentBytes * 1000L) / elapsed
                val currentTotal = totalDownloadedCounter.get().coerceAtMost(totalBytes)
                val percent = if (totalBytes > 0L) {
                    ((currentTotal * 100L) / totalBytes).toInt().coerceIn(0, 100)
                } else {
                    -1
                }

                repository.updateProgress(
                    id = entity.id,
                    downloadedBytes = currentTotal,
                    totalBytes = totalBytes,
                    speedBytesPerSec = speed,
                    status = DownloadStatus.DOWNLOADING
                )

                val progressLabel = buildString {
                    if (percent >= 0) append("$percent% • ")
                    append("${threadCount}x threads")
                    if (speed > 0L) append(" • ${formatSpeed(speed)}")
                }
                setForeground(
                    createForegroundInfo(
                        downloadId = entity.id,
                        title = entity.title,
                        progressText = progressLabel,
                        progressPercent = percent
                    )
                )
                lastTime = now
            }
        }

        try {
            val semaphore = Semaphore(threadCount)
            ranges.mapIndexed { idx, range ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        downloadChunkRange(
                            mediaUrl = entity.mediaUrl,
                            range = range,
                            partFile = partFiles[idx],
                            supportsRange = probe.supportsRange,
                            totalDownloadedCounter = totalDownloadedCounter,
                            intervalBytesCounter = intervalBytesCounter
                        )
                    }
                }
            }.awaitAll()
        } finally {
            progressReporter.cancel()
        }

        // Merge completed .part files sequentially into destFile
        mergePartFiles(partFiles, destFile)
    }

    private fun downloadChunkRange(
        mediaUrl: String,
        range: ByteRange,
        partFile: File,
        supportsRange: Boolean,
        totalDownloadedCounter: AtomicLong,
        intervalBytesCounter: AtomicLong
    ) {
        val existingBytes = if (partFile.exists()) partFile.length() else 0L
        val resumeStart = if (supportsRange) {
            ChunkDownloadPlanner.computeResumeOffset(range, existingBytes)
        } else {
            // Server does not support Range requests: restart this single part from 0
            if (partFile.exists()) {
                totalDownloadedCounter.addAndGet(-existingBytes)
                partFile.delete()
            }
            0L
        } ?: return // Chunk already complete on disk!

        val requestBuilder = Request.Builder()
            .url(mediaUrl)
            .header("User-Agent", USER_AGENT)
            .header("DNT", "1")

        if (supportsRange) {
            requestBuilder.header("Range", "bytes=$resumeStart-${range.endByte}")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code in 400..499 && response.code != 416) {
                throw NonRetryableHttpException("HTTP ${response.code}: Unable to fetch media")
            }
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("HTTP ${response.code}")
            }

            // If server ignored Range header and returned 200 OK instead of 206 Partial Content
            val appendMode = supportsRange && response.code == 206 && resumeStart > range.startByte
            if (!appendMode && existingBytes > 0L && supportsRange) {
                totalDownloadedCounter.addAndGet(-existingBytes)
            }

            val body = response.body ?: throw IOException("Empty chunk body")
            val input = body.byteStream()
            FileOutputStream(partFile, appendMode).use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    if (isStopped) throw CancellationException("Worker stopped")
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    totalDownloadedCounter.addAndGet(read.toLong())
                    intervalBytesCounter.addAndGet(read.toLong())
                }
                output.flush()
            }
        }
    }

    private suspend fun downloadSingleStreamUnknownLength(
        entity: DownloadEntity,
        destFile: File
    ) {
        val partFile = File("${destFile.absolutePath}.part.0")
        if (partFile.exists()) partFile.delete()

        val request = Request.Builder()
            .url(entity.mediaUrl)
            .header("User-Agent", USER_AGENT)
            .header("DNT", "1")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code in 400..499) {
                throw NonRetryableHttpException("HTTP ${response.code}: Unable to fetch stream")
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response body")
            val input = body.byteStream()

            FileOutputStream(partFile).use { output ->
                val buffer = ByteArray(16 * 1024)
                var downloaded = 0L
                var lastReport = System.currentTimeMillis()
                var intervalBytes = 0L

                while (true) {
                    if (isStopped) throw CancellationException("Worker stopped")
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    intervalBytes += read

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastReport
                    if (elapsed >= 350L) {
                        val speed = (intervalBytes * 1000L) / elapsed.coerceAtLeast(1L)
                        repository.updateProgress(
                            id = entity.id,
                            downloadedBytes = downloaded,
                            totalBytes = -1L,
                            speedBytesPerSec = speed,
                            status = DownloadStatus.DOWNLOADING
                        )
                        setForeground(
                            createForegroundInfo(
                                downloadId = entity.id,
                                title = entity.title,
                                progressText = "${formatBytes(downloaded)} • ${formatSpeed(speed)}",
                                progressPercent = -1
                            )
                        )
                        lastReport = now
                        intervalBytes = 0L
                    }
                }
                output.flush()
            }
        }
        mergePartFiles(listOf(partFile), destFile)
    }

    private suspend fun downloadHlsResumable(
        entity: DownloadEntity,
        destFile: File
    ) = coroutineScope {
        val playlistBody = fetchText(entity.mediaUrl)
            ?: throw IOException("Unable to load HLS playlist")

        val resolvedPlaylistUrl = if (playlistBody.contains("#EXT-X-STREAM-INF")) {
            val base = URL(entity.mediaUrl)
            val firstVariant = playlistBody.lines()
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: throw NonRetryableHttpException("No HLS variant stream found")
            URL(base, firstVariant.trim()).toString()
        } else {
            entity.mediaUrl
        }

        val mediaPlaylist = if (resolvedPlaylistUrl == entity.mediaUrl) {
            playlistBody
        } else {
            fetchText(resolvedPlaylistUrl) ?: throw IOException("Unable to load HLS segments")
        }

        val base = URL(resolvedPlaylistUrl)
        val segments = mediaPlaylist.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { URL(base, it).toString() }

        if (segments.isEmpty()) {
            throw NonRetryableHttpException("HLS playlist contains no downloadable segments")
        }

        repository.updateTransferMetadata(
            id = entity.id,
            etag = null,
            supportsRange = true,
            threadCount = 3,
            totalBytes = entity.totalBytes
        )

        val segFiles = segments.indices.map { idx -> File("${destFile.absolutePath}.seg.$idx") }
        val completedBytes = AtomicLong(segFiles.filter { it.exists() }.sumOf { it.length() })
        val completedSegCount = AtomicLong(segFiles.count { it.exists() && it.length() > 0L }.toLong())

        val semaphore = Semaphore(3)
        segments.mapIndexed { idx, segUrl ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (isStopped) throw CancellationException("Worker stopped")
                    val segFile = segFiles[idx]
                    if (segFile.exists() && segFile.length() > 0L) {
                        return@withPermit
                    }

                    val segReq = Request.Builder()
                        .url(segUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("DNT", "1")
                        .build()

                    client.newCall(segReq).execute().use { resp ->
                        if (resp.code in 400..499) {
                            throw NonRetryableHttpException("Segment ${idx + 1} failed (${resp.code})")
                        }
                        if (!resp.isSuccessful) throw IOException("Segment ${idx + 1} HTTP ${resp.code}")
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        FileOutputStream(segFile).use { it.write(bytes) }
                        val totalDownloaded = completedBytes.addAndGet(bytes.size.toLong())
                        val doneCount = completedSegCount.incrementAndGet()
                        val estTotal = (totalDownloaded / doneCount.coerceAtLeast(1L)) * segments.size
                        val percent = ((doneCount * 100L) / segments.size).toInt().coerceIn(0, 100)

                        repository.updateProgress(
                            id = entity.id,
                            downloadedBytes = totalDownloaded,
                            totalBytes = estTotal,
                            speedBytesPerSec = bytes.size.toLong(),
                            status = DownloadStatus.DOWNLOADING
                        )
                        setForeground(
                            createForegroundInfo(
                                downloadId = entity.id,
                                title = entity.title,
                                progressText = "HLS $percent% ($doneCount/${segments.size} segments)",
                                progressPercent = percent
                            )
                        )
                    }
                }
            }
        }.awaitAll()

        mergePartFiles(segFiles, destFile)
    }

    private fun mergePartFiles(partFiles: List<File>, destFile: File) {
        if (destFile.exists()) destFile.delete()
        FileOutputStream(destFile).use { output ->
            val buffer = ByteArray(32 * 1024)
            for (part in partFiles) {
                if (!part.exists()) continue
                FileInputStream(part).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            output.flush()
        }
        partFiles.forEach { runCatching { if (it.exists()) it.delete() } }
    }

    private data class RemoteProbe(
        val totalBytes: Long,
        val supportsRange: Boolean,
        val etag: String?
    )

    private fun probeRemoteResource(url: String): RemoteProbe {
        // Use a ranged GET 0-0 to reliably test both Accept-Ranges/206 and total length
        val rangeReq = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("DNT", "1")
            .header("Range", "bytes=0-0")
            .build()

        runCatching {
            client.newCall(rangeReq).execute().use { resp ->
                if (resp.code == 206) {
                    val totalFromRange = resp.header("Content-Range")
                        ?.substringAfterLast('/')
                        ?.toLongOrNull()
                        ?: -1L
                    val etag = resp.header("ETag") ?: resp.header("Last-Modified")
                    return RemoteProbe(
                        totalBytes = totalFromRange,
                        supportsRange = true,
                        etag = etag
                    )
                }
                if (resp.isSuccessful) {
                    val cl = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    val acceptRanges = resp.header("Accept-Ranges")?.equals("bytes", true) == true
                    val etag = resp.header("ETag") ?: resp.header("Last-Modified")
                    return RemoteProbe(
                        totalBytes = cl,
                        supportsRange = acceptRanges,
                        etag = etag
                    )
                }
            }
        }

        // Fallback HEAD
        val headReq = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", USER_AGENT)
            .header("DNT", "1")
            .build()

        return runCatching {
            client.newCall(headReq).execute().use { resp ->
                val cl = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                val acceptRanges = resp.header("Accept-Ranges")?.equals("bytes", true) == true
                val etag = resp.header("ETag") ?: resp.header("Last-Modified")
                RemoteProbe(cl, acceptRanges, etag)
            }
        }.getOrDefault(RemoteProbe(-1L, false, null))
    }

    private fun fetchText(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("DNT", "1")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (resp.code in 400..499) throw NonRetryableHttpException("HTTP ${resp.code}")
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string()
        }
    }

    private fun createForegroundInfo(
        downloadId: Long,
        title: String,
        progressText: String,
        progressPercent: Int
    ): ForegroundInfo {
        ensureNotificationChannel()
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notificationId = (NOTIFICATION_ID_BASE + (downloadId % 10000L)).toInt()

        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(progressText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply {
                if (progressPercent in 0..100) {
                    setProgress(100, progressPercent, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "PureSnap Downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows background multi-threaded media download progress"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return "${formatBytes(bytesPerSec)}/s"
    }

    private class NonRetryableHttpException(message: String) : Exception(message)

    companion object {
        const val KEY_DOWNLOAD_ID = "key_download_id"
        const val KEY_AUTO_EXTRACT_FORMAT = "key_auto_extract_format"
        const val KEY_AUTO_EXTRACT_BITRATE = "key_auto_extract_bitrate"
        private const val CHANNEL_ID = "puresnap_downloads_channel"
        private const val NOTIFICATION_ID_BASE = 4100
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
