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
import com.example.data.ConversionStatus
import com.example.data.DownloadEntity
import com.example.data.DownloadStatus
import com.example.data.MediaType
import kotlinx.coroutines.CancellationException
import java.io.File

class AudioExtractionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val database = AppDatabase.getInstance(appContext)
    private val conversionDao = database.conversionDao()
    private val downloadDao = database.downloadDao()

    override suspend fun doWork(): Result {
        val conversionId = inputData.getLong(KEY_CONVERSION_ID, -1L)
        if (conversionId <= 0L) return Result.failure()

        val entity = conversionDao.getById(conversionId) ?: return Result.failure()
        if (entity.status == ConversionStatus.COMPLETED || entity.status == ConversionStatus.CANCELLED) {
            return Result.success()
        }

        val outFile = File(entity.outputFilePath)
        outFile.parentFile?.mkdirs()

        setForeground(
            createForegroundInfo(
                conversionId = conversionId,
                title = "Extracting ${entity.targetFormat.label}: ${entity.sourceName}",
                text = "Preparing audio extractor…",
                percent = 0
            )
        )

        conversionDao.updateProgress(
            id = conversionId,
            progressPercent = 1,
            outputBytes = 0L,
            status = ConversionStatus.EXTRACTING
        )

        var lastProgressEmitMs = 0L

        return try {
            val summary = AudioTranscoder.extractAndConvert(
                context = applicationContext,
                sourceUriString = entity.sourceUri,
                outputFile = outFile,
                targetFormat = entity.targetFormat,
                bitrateKbps = entity.bitrateKbps,
                isCancelled = { isStopped },
                onProgress = { pct, bytesWritten ->
                    val now = System.currentTimeMillis()
                    if (now - lastProgressEmitMs >= 350L) {
                        lastProgressEmitMs = now
                        kotlinx.coroutines.runBlocking {
                            conversionDao.updateProgress(
                                id = conversionId,
                                progressPercent = pct,
                                outputBytes = bytesWritten,
                                status = ConversionStatus.EXTRACTING
                            )
                            setForeground(
                                createForegroundInfo(
                                    conversionId = conversionId,
                                    title = "Extracting ${entity.targetFormat.label}: ${entity.sourceName}",
                                    text = "$pct% • ${entity.bitrateKbps} kbps ${entity.targetFormat.label}",
                                    percent = pct
                                )
                            )
                        }
                    }
                }
            )

            val latest = conversionDao.getById(conversionId)
            if (isStopped || latest == null || latest.status == ConversionStatus.CANCELLED) {
                runCatching { outFile.delete() }
                return Result.success()
            }

            conversionDao.markCompleted(
                id = conversionId,
                outputFilePath = summary.outputFile.absolutePath,
                outputBytes = summary.outputBytes
            )

            // Also surface converted MP3 / AAC track inside the Offline Media Vault (Audio tab)
            val baseTitle = entity.sourceName.substringBeforeLast('.').ifBlank { entity.sourceName }
            downloadDao.insert(
                DownloadEntity(
                    title = "$baseTitle (${entity.targetFormat.label})",
                    sourceUrl = entity.sourceUri,
                    mediaUrl = entity.sourceUri,
                    localFilePath = summary.outputFile.absolutePath,
                    mimeType = entity.targetFormat.mimeType,
                    mediaType = MediaType.AUDIO,
                    formatLabel = entity.targetFormat.label,
                    qualityLabel = "${entity.bitrateKbps} kbps",
                    totalBytes = summary.outputBytes,
                    downloadedBytes = summary.outputBytes,
                    speedBytesPerSec = 0L,
                    status = DownloadStatus.COMPLETED
                )
            )
            Result.success()
        } catch (cancel: CancellationException) {
            runCatching { outFile.delete() }
            throw cancel
        } catch (err: Exception) {
            runCatching { outFile.delete() }
            conversionDao.updateStatus(
                id = conversionId,
                status = ConversionStatus.FAILED,
                errorMessage = err.message ?: "Audio extraction failed"
            )
            Result.failure()
        }
    }

    private fun createForegroundInfo(
        conversionId: Long,
        title: String,
        text: String,
        percent: Int
    ): ForegroundInfo {
        ensureNotificationChannel()
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notificationId = (NOTIFICATION_ID_BASE + (conversionId % 10000L)).toInt()

        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
                    "PureSnap Audio Extractor",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Background MP3 and AAC audio conversion queue"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        const val KEY_CONVERSION_ID = "key_conversion_id"
        private const val CHANNEL_ID = "puresnap_audio_extract_channel"
        private const val NOTIFICATION_ID_BASE = 6100
    }
}
