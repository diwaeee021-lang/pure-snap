package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioConversionDao {
    @Query("SELECT * FROM audio_conversions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AudioConversionEntity>>

    @Query("SELECT * FROM audio_conversions WHERE status IN ('QUEUED', 'EXTRACTING')")
    suspend fun getIncomplete(): List<AudioConversionEntity>

    @Query("SELECT * FROM audio_conversions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): AudioConversionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AudioConversionEntity): Long

    @Query(
        "UPDATE audio_conversions SET progressPercent = :progressPercent, outputBytes = :outputBytes, status = :status WHERE id = :id"
    )
    suspend fun updateProgress(
        id: Long,
        progressPercent: Int,
        outputBytes: Long,
        status: ConversionStatus
    )

    @Query(
        "UPDATE audio_conversions SET outputFilePath = :outputFilePath, outputBytes = :outputBytes, progressPercent = 100, status = 'COMPLETED', errorMessage = NULL WHERE id = :id"
    )
    suspend fun markCompleted(
        id: Long,
        outputFilePath: String,
        outputBytes: Long
    )

    @Query("UPDATE audio_conversions SET status = :status, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ConversionStatus, errorMessage: String? = null)

    @Query("UPDATE audio_conversions SET workId = :workId WHERE id = :id")
    suspend fun updateWorkId(id: Long, workId: String?)

    @Query("DELETE FROM audio_conversions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM audio_conversions WHERE status IN ('FAILED', 'CANCELLED')")
    suspend fun clearInactive()
}
