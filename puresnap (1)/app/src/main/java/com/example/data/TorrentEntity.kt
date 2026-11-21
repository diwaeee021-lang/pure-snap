package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.torrent.TorrentFileEntry

enum class TorrentStatus {
    QUEUED,
    RESOLVING_METADATA,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Entity(tableName = "torrents")
data class TorrentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val infoHashHex: String,
    val name: String,
    val sourceUri: String,
    val torrentFilePath: String? = null,
    val saveDirPath: String,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val downloadSpeedBytesPerSec: Long = 0L,
    val totalPieces: Int = 0,
    val completedPieces: Int = 0,
    val completedBitfieldBase64: String = "",
    val selectedFileIndicesCsv: String = "",
    val serializedFiles: String = "",
    val sequentialMode: Boolean = true,
    val seeders: Int = 0,
    val leechers: Int = 0,
    val connectedPeers: Int = 0,
    val status: TorrentStatus = TorrentStatus.QUEUED,
    val errorMessage: String? = null,
    val workId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = when {
            status == TorrentStatus.COMPLETED -> 1f
            totalPieces > 0 -> (completedPieces.toFloat() / totalPieces.toFloat()).coerceIn(0f, 1f)
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }

    fun selectedIndicesSet(): Set<Int> {
        if (selectedFileIndicesCsv.isBlank()) return emptySet()
        return selectedFileIndicesCsv.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
    }

    fun parsedFiles(): List<TorrentFileEntry> {
        if (serializedFiles.isBlank()) return emptyList()
        return serializedFiles.lines().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 4) return@mapNotNull null
            val idx = parts[0].toIntOrNull() ?: return@mapNotNull null
            val len = parts[1].toLongOrNull() ?: return@mapNotNull null
            val offset = parts[2].toLongOrNull() ?: return@mapNotNull null
            val path = parts.drop(3).joinToString("|")
            TorrentFileEntry(
                index = idx,
                relativePath = path,
                length = len,
                offsetInTorrent = offset
            )
        }
    }

    companion object {
        fun serializeFiles(files: List<TorrentFileEntry>): String {
            return files.joinToString("\n") { f ->
                "${f.index}|${f.length}|${f.offsetInTorrent}|${f.relativePath.replace("\n", " ")}"
            }
        }
    }
}
