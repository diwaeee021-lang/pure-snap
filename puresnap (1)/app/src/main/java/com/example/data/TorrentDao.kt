package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TorrentDao {
    @Query("SELECT * FROM torrents ORDER BY createdAt DESC")
    fun observeAllTorrents(): Flow<List<TorrentEntity>>

    @Query("SELECT * FROM torrents WHERE status IN ('QUEUED', 'RESOLVING_METADATA', 'DOWNLOADING')")
    suspend fun getIncompleteTorrents(): List<TorrentEntity>

    @Query("SELECT * FROM torrents WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TorrentEntity?

    @Query("SELECT * FROM torrents WHERE infoHashHex = :infoHashHex LIMIT 1")
    suspend fun getByInfoHash(infoHashHex: String): TorrentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TorrentEntity): Long

    @Query(
        """
        UPDATE torrents SET
            downloadedBytes = :downloadedBytes,
            downloadSpeedBytesPerSec = :speedBytesPerSec,
            completedPieces = :completedPieces,
            completedBitfieldBase64 = :bitfieldBase64,
            seeders = :seeders,
            leechers = :leechers,
            connectedPeers = :connectedPeers,
            status = :status
        WHERE id = :id
        """
    )
    suspend fun updateSwarmProgress(
        id: Long,
        downloadedBytes: Long,
        speedBytesPerSec: Long,
        completedPieces: Int,
        bitfieldBase64: String,
        seeders: Int,
        leechers: Int,
        connectedPeers: Int,
        status: TorrentStatus
    )

    @Query(
        """
        UPDATE torrents SET
            name = :name,
            torrentFilePath = :torrentFilePath,
            totalBytes = :totalBytes,
            totalPieces = :totalPieces,
            serializedFiles = :serializedFiles,
            selectedFileIndicesCsv = :selectedFileIndicesCsv
        WHERE id = :id
        """
    )
    suspend fun updateResolvedMetadata(
        id: Long,
        name: String,
        torrentFilePath: String,
        totalBytes: Long,
        totalPieces: Int,
        serializedFiles: String,
        selectedFileIndicesCsv: String
    )

    @Query("UPDATE torrents SET status = :status, errorMessage = :errorMessage, downloadSpeedBytesPerSec = 0, connectedPeers = 0 WHERE id = :id")
    suspend fun updateStatus(id: Long, status: TorrentStatus, errorMessage: String? = null)

    @Query("UPDATE torrents SET sequentialMode = :sequential WHERE id = :id")
    suspend fun updateSequentialMode(id: Long, sequential: Boolean)

    @Query("UPDATE torrents SET workId = :workId WHERE id = :id")
    suspend fun updateWorkId(id: Long, workId: String?)

    @Query("DELETE FROM torrents WHERE id = :id")
    suspend fun deleteById(id: Long)
}
