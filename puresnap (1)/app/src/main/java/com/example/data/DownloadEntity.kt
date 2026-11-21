package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MediaType {
    VIDEO,
    AUDIO
}

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val sourceUrl: String,
    val mediaUrl: String,
    val thumbnailUrl: String? = null,
    val localFilePath: String,
    val mimeType: String,
    val mediaType: MediaType,
    val formatLabel: String,
    val qualityLabel: String,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val exportedToDevice: Boolean = false,
    val etag: String? = null,
    val supportsRange: Boolean = false,
    val threadCount: Int = 1,
    val workId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = when {
            status == DownloadStatus.COMPLETED -> 1f
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}
