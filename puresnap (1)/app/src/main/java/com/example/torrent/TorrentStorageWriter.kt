package com.example.torrent

import android.util.Base64
import java.io.File
import java.io.RandomAccessFile
import java.util.BitSet

object TorrentStorageWriter {

    fun writePieceToFiles(
        saveDir: File,
        metadata: TorrentMetadata,
        pieceIndex: Int,
        pieceData: ByteArray,
        selectedFileIndices: Set<Int>
    ) {
        val pieceStart = pieceIndex.toLong() * metadata.pieceLength
        val pieceEnd = pieceStart + pieceData.size.toLong() // exclusive

        for (fileEntry in metadata.files) {
            if (selectedFileIndices.isNotEmpty() && fileEntry.index !in selectedFileIndices) {
                continue
            }
            val fileStart = fileEntry.offsetInTorrent
            val fileEnd = fileStart + fileEntry.length

            // Check intersection [pieceStart, pieceEnd) and [fileStart, fileEnd)
            val overlapStart = maxOf(pieceStart, fileStart)
            val overlapEnd = minOf(pieceEnd, fileEnd)
            if (overlapStart >= overlapEnd) continue

            val lengthToWrite = (overlapEnd - overlapStart).toInt()
            val offsetInPiece = (overlapStart - pieceStart).toInt()
            val offsetInFile = overlapStart - fileStart

            val targetFile = resolveTargetFile(saveDir, fileEntry.relativePath)
            targetFile.parentFile?.mkdirs()

            RandomAccessFile(targetFile, "rw").use { raf ->
                if (raf.length() < fileEntry.length) {
                    raf.setLength(fileEntry.length)
                }
                raf.seek(offsetInFile)
                raf.write(pieceData, offsetInPiece, lengthToWrite)
            }
        }
    }

    fun resolveTargetFile(saveDir: File, relativePath: String): File {
        val safeRelative = relativePath
            .split('/', '\\')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString(File.separator)
            .ifBlank { "torrent_file" }
        return File(saveDir, safeRelative)
    }

    fun encodeBitSet(bitSet: BitSet, totalPieces: Int): String {
        if (totalPieces <= 0) return ""
        val byteLen = (totalPieces + 7) / 8
        val bytes = ByteArray(byteLen)
        for (i in 0 until totalPieces) {
            if (bitSet.get(i)) {
                bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
            }
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun decodeBitSet(encoded: String?): BitSet {
        val bitSet = BitSet()
        if (encoded.isNullOrBlank()) return bitSet
        val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return bitSet
        for (i in 0 until bytes.size * 8) {
            val b = bytes[i / 8].toInt() and 0xFF
            if ((b and (1 shl (i % 8))) != 0) {
                bitSet.set(i)
            }
        }
        return bitSet
    }

    fun calculateSelectedBytes(metadata: TorrentMetadata, selectedFileIndices: Set<Int>): Long {
        if (selectedFileIndices.isEmpty()) return metadata.totalLength
        return metadata.files.filter { it.index in selectedFileIndices }.sumOf { it.length }
    }

    fun buildSyntheticTorrentBytes(metadata: TorrentMetadata): ByteArray? {
        val rawInfo = metadata.rawInfoBytes ?: return null
        val infoValue = runCatching { Bencode.decode(rawInfo) }.getOrNull() ?: return null
        val rootMap = LinkedHashMap<String, BencodeValue>()
        if (metadata.trackers.isNotEmpty()) {
            rootMap["announce"] = BencodeValue.BBytes(metadata.trackers.first().toByteArray(Charsets.UTF_8))
            val tiers = metadata.trackers.map { tr ->
                BencodeValue.BList(listOf(BencodeValue.BBytes(tr.toByteArray(Charsets.UTF_8))))
            }
            rootMap["announce-list"] = BencodeValue.BList(tiers)
        }
        if (metadata.webSeeds.isNotEmpty()) {
            rootMap["url-list"] = BencodeValue.BList(
                metadata.webSeeds.map { BencodeValue.BBytes(it.toByteArray(Charsets.UTF_8)) }
            )
        }
        rootMap["info"] = infoValue
        return Bencode.encode(BencodeValue.BDict(rootMap))
    }
}
