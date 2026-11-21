package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AudioOutputFormat(
    val label: String,
    val extension: String,
    val mimeType: String
) {
    MP3(label = "MP3", extension = "mp3", mimeType = "audio/mpeg"),
    AAC(label = "AAC", extension = "aac", mimeType = "audio/aac")
}

enum class ConversionStatus {
    QUEUED,
    EXTRACTING,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Entity(tableName = "audio_conversions")
data class AudioConversionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sourceName: String,
    val sourceUri: String,
    val outputFilePath: String,
    val targetFormat: AudioOutputFormat,
    val bitrateKbps: Int = 192,
    val status: ConversionStatus = ConversionStatus.QUEUED,
    val progressPercent: Int = 0,
    val outputBytes: Long = 0L,
    val errorMessage: String? = null,
    val workId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
