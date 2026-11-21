package com.example.data

import kotlinx.coroutines.flow.Flow
import java.io.File

class MediaVaultRepository(private val downloadDao: DownloadDao) {

    val downloads: Flow<List<DownloadEntity>> = downloadDao.observeAllDownloads()

    suspend fun getIncompleteDownloads(): List<DownloadEntity> = downloadDao.getIncompleteDownloads()

    suspend fun getById(id: Long): DownloadEntity? = downloadDao.getById(id)

    suspend fun insertDownload(entity: DownloadEntity): Long = downloadDao.insert(entity)

    suspend fun updateDownload(entity: DownloadEntity) = downloadDao.update(entity)

    suspend fun updateProgress(
        id: Long,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        status: DownloadStatus
    ) {
        downloadDao.updateProgress(id, downloadedBytes, totalBytes, speedBytesPerSec, status)
    }

    suspend fun updateTransferMetadata(
        id: Long,
        etag: String?,
        supportsRange: Boolean,
        threadCount: Int,
        totalBytes: Long
    ) {
        downloadDao.updateTransferMetadata(id, etag, supportsRange, threadCount, totalBytes)
    }

    suspend fun updateWorkId(id: Long, workId: String?) {
        downloadDao.updateWorkId(id, workId)
    }

    suspend fun updateStatus(id: Long, status: DownloadStatus, errorMessage: String? = null) {
        downloadDao.updateStatus(id, status, errorMessage)
    }

    suspend fun markExported(id: Long) = downloadDao.markExported(id)

    suspend fun deleteDownload(entity: DownloadEntity) {
        cleanupTempArtifacts(entity.localFilePath)
        runCatching {
            val file = File(entity.localFilePath)
            if (file.exists()) file.delete()
        }
        downloadDao.deleteById(entity.id)
    }

    suspend fun cleanupTempArtifacts(localFilePath: String) {
        runCatching {
            val dest = File(localFilePath)
            val parent = dest.parentFile ?: return@runCatching
            val baseName = dest.name
            parent.listFiles()?.forEach { candidate ->
                if (candidate.name.startsWith("$baseName.part.") ||
                    candidate.name.startsWith("$baseName.seg.")
                ) {
                    candidate.delete()
                }
            }
        }
    }

    suspend fun clearFailed() = downloadDao.clearInactiveFailed()
}
