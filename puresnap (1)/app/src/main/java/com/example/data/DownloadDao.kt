package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun getIncompleteDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("UPDATE downloads SET downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, speedBytesPerSec = :speedBytesPerSec, status = :status WHERE id = :id")
    suspend fun updateProgress(
        id: Long,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        status: DownloadStatus
    )

    @Query("UPDATE downloads SET etag = :etag, supportsRange = :supportsRange, threadCount = :threadCount, totalBytes = :totalBytes WHERE id = :id")
    suspend fun updateTransferMetadata(
        id: Long,
        etag: String?,
        supportsRange: Boolean,
        threadCount: Int,
        totalBytes: Long
    )

    @Query("UPDATE downloads SET workId = :workId WHERE id = :id")
    suspend fun updateWorkId(id: Long, workId: String?)

    @Query("UPDATE downloads SET status = :status, errorMessage = :errorMessage, speedBytesPerSec = 0 WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus, errorMessage: String? = null)

    @Query("UPDATE downloads SET exportedToDevice = 1 WHERE id = :id")
    suspend fun markExported(id: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM downloads WHERE status IN ('FAILED', 'CANCELLED')")
    suspend fun clearInactiveFailed()
}
