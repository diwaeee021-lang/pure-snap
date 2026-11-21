package com.example.torrent

import android.net.Uri
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class TorrentFileEntry(
    val index: Int,
    val relativePath: String,
    val length: Long,
    val offsetInTorrent: Long
) {
    val isMediaFile: Boolean
        get() {
            val lower = relativePath.lowercase(Locale.US)
            return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
                lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".m4v") ||
                lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".flac") ||
                lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".aac")
        }
}

data class TorrentMetadata(
    val infoHashBytes: ByteArray,
    val infoHashHex: String,
    val name: String,
    val pieceLength: Long,
    val totalLength: Long,
    val pieceHashes: List<ByteArray>,
    val files: List<TorrentFileEntry>,
    val trackers: List<String>,
    val webSeeds: List<String>,
    val rawInfoBytes: ByteArray? = null
) {
    val totalPieces: Int get() = pieceHashes.size

    fun pieceSize(pieceIndex: Int): Long {
        if (pieceIndex < 0 || pieceIndex >= totalPieces) return 0L
        return if (pieceIndex == totalPieces - 1) {
            val rem = totalLength % pieceLength
            if (rem == 0L) pieceLength else rem
        } else {
            pieceLength
        }
    }

    fun piecesForSelectedFiles(selectedIndices: Set<Int>): Set<Int> {
        if (totalPieces == 0 || pieceLength <= 0L) return emptySet()
        if (selectedIndices.isEmpty() || selectedIndices.size == files.size) {
            return (0 until totalPieces).toSet()
        }
        val result = HashSet<Int>()
        for (file in files) {
            if (file.index !in selectedIndices || file.length <= 0L) continue
            val startPiece = (file.offsetInTorrent / pieceLength).toInt()
            val endPiece = ((file.offsetInTorrent + file.length - 1L) / pieceLength).toInt()
            for (p in startPiece..endPiece.coerceAtMost(totalPieces - 1)) {
                result.add(p)
            }
        }
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TorrentMetadata) return false
        return infoHashHex == other.infoHashHex
    }

    override fun hashCode(): Int = infoHashHex.hashCode()
}

data class MagnetLink(
    val rawUri: String,
    val infoHashBytes: ByteArray,
    val infoHashHex: String,
    val displayName: String,
    val trackers: List<String>,
    val webSeeds: List<String>,
    val exactLength: Long = -1L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MagnetLink) return false
        return infoHashHex == other.infoHashHex
    }

    override fun hashCode(): Int = infoHashHex.hashCode()
}

object TorrentParser {

    val DEFAULT_PUBLIC_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "https://tracker.tamersunion.org:443/announce"
    )

    fun isMagnetUri(input: String): Boolean {
        return input.trim().startsWith("magnet:?", ignoreCase = true)
    }

    fun isTorrentUrl(input: String): Boolean {
        val lower = input.trim().lowercase(Locale.US).substringBefore('?')
        return lower.endsWith(".torrent")
    }

    fun parseTorrentBytes(torrentBytes: ByteArray): TorrentMetadata {
        val decoded = Bencode.decodeWithInfoSlice(torrentBytes)
        val root = decoded.root
        val infoDict = root.getDict("info")
            ?: throw IllegalArgumentException("Missing 'info' dictionary in torrent file")

        val rawInfo = decoded.rawInfoBytes ?: Bencode.encode(infoDict)
        val infoHashBytes = sha1(rawInfo)
        val infoHashHex = bytesToHex(infoHashBytes)

        val trackers = linkedSetOf<String>()
        root.getString("announce")?.takeIf { it.isNotBlank() }?.let { trackers.add(it.trim()) }
        root.getList("announce-list")?.items?.forEach { tier ->
            (tier as? BencodeValue.BList)?.items?.forEach { item ->
                (item as? BencodeValue.BBytes)?.asUtf8()?.takeIf { it.isNotBlank() }?.let {
                    trackers.add(it.trim())
                }
            }
        }

        val webSeeds = linkedSetOf<String>()
        when (val urlList = root["url-list"]) {
            is BencodeValue.BBytes -> urlList.asUtf8().takeIf { it.startsWith("http", true) }?.let { webSeeds.add(it) }
            is BencodeValue.BList -> urlList.items.forEach { item ->
                (item as? BencodeValue.BBytes)?.asUtf8()?.takeIf { it.startsWith("http", true) }?.let {
                    webSeeds.add(it)
                }
            }
            else -> Unit
        }

        return parseInfoDictionary(
            infoDict = infoDict,
            rawInfoBytes = rawInfo,
            infoHashBytes = infoHashBytes,
            infoHashHex = infoHashHex,
            trackers = trackers.toList(),
            webSeeds = webSeeds.toList()
        )
    }

    fun parseInfoDictionary(
        infoDict: BencodeValue.BDict,
        rawInfoBytes: ByteArray,
        infoHashBytes: ByteArray,
        infoHashHex: String,
        trackers: List<String>,
        webSeeds: List<String>
    ): TorrentMetadata {
        val name = infoDict.getString("name")?.takeIf { it.isNotBlank() } ?: "Torrent_$infoHashHex"
        val pieceLength = infoDict.getLong("piece length")
            ?: throw IllegalArgumentException("Missing 'piece length' in torrent")
        val piecesRaw = (infoDict["pieces"] as? BencodeValue.BBytes)?.bytes
            ?: throw IllegalArgumentException("Missing 'pieces' hashes in torrent")

        if (piecesRaw.size % 20 != 0) {
            throw IllegalArgumentException("Invalid 'pieces' byte length (${piecesRaw.size})")
        }
        val pieceHashes = ArrayList<ByteArray>(piecesRaw.size / 20)
        for (offset in piecesRaw.indices step 20) {
            pieceHashes.add(piecesRaw.copyOfRange(offset, offset + 20))
        }

        val fileEntries = mutableListOf<TorrentFileEntry>()
        val filesList = infoDict.getList("files")
        var totalLength = 0L

        if (filesList != null) {
            filesList.items.forEachIndexed { idx, item ->
                val fDict = item as? BencodeValue.BDict ?: return@forEachIndexed
                val len = fDict.getLong("length") ?: 0L
                val pathParts = fDict.getList("path")?.items
                    ?.mapNotNull { (it as? BencodeValue.BBytes)?.asUtf8() }
                    .orEmpty()
                val relPath = if (pathParts.isNotEmpty()) pathParts.joinToString("/") else "file_$idx"
                fileEntries.add(
                    TorrentFileEntry(
                        index = idx,
                        relativePath = relPath,
                        length = len,
                        offsetInTorrent = totalLength
                    )
                )
                totalLength += len
            }
        } else {
            val singleLen = infoDict.getLong("length") ?: 0L
            fileEntries.add(
                TorrentFileEntry(
                    index = 0,
                    relativePath = name,
                    length = singleLen,
                    offsetInTorrent = 0L
                )
            )
            totalLength = singleLen
        }

        return TorrentMetadata(
            infoHashBytes = infoHashBytes,
            infoHashHex = infoHashHex,
            name = name,
            pieceLength = pieceLength,
            totalLength = totalLength,
            pieceHashes = pieceHashes,
            files = fileEntries,
            trackers = if (trackers.isNotEmpty()) trackers else DEFAULT_PUBLIC_TRACKERS,
            webSeeds = webSeeds,
            rawInfoBytes = rawInfoBytes
        )
    }

    fun parseMagnetUri(rawUri: String): MagnetLink {
        val trimmed = rawUri.trim()
        require(isMagnetUri(trimmed)) { "Not a valid magnet:? URI" }

        val query = trimmed.substringAfter('?', "")
        val params = query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = pair.substring(0, idx).lowercase(Locale.US)
            val valDecoded = runCatching {
                URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
            }.getOrDefault(pair.substring(idx + 1))
            key to valDecoded
        }

        val xtValue = params.firstOrNull { (k, v) ->
            k == "xt" && v.startsWith("urn:btih:", ignoreCase = true)
        }?.second ?: throw IllegalArgumentException("Magnet link is missing urn:btih info-hash")

        val hashToken = xtValue.substringAfter("urn:btih:", "").trim()
        val infoHashBytes = when (hashToken.length) {
            40 -> hexToBytes(hashToken)
            32 -> decodeBase32(hashToken)
            else -> throw IllegalArgumentException("Unsupported info-hash length (${hashToken.length})")
        }
        val infoHashHex = bytesToHex(infoHashBytes)

        val displayName = params.firstOrNull { it.first == "dn" }?.second
            ?.takeIf { it.isNotBlank() }
            ?: "Magnet ${infoHashHex.take(10)}"

        val trackers = params.filter { it.first == "tr" }
            .map { it.second.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { DEFAULT_PUBLIC_TRACKERS }

        val webSeeds = params.filter { it.first == "ws" || it.first == "as" }
            .map { it.second.trim() }
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()

        val exactLength = params.firstOrNull { it.first == "xl" }?.second?.toLongOrNull() ?: -1L

        return MagnetLink(
            rawUri = trimmed,
            infoHashBytes = infoHashBytes,
            infoHashHex = infoHashHex,
            displayName = displayName,
            trackers = trackers,
            webSeeds = webSeeds,
            exactLength = exactLength
        )
    }

    fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Invalid hex string" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun decodeBase32(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = input.trim().uppercase(Locale.US).replace("=", "")
        val out = ArrayList<Byte>((clean.length * 5) / 8)
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            val idx = alphabet.indexOf(ch)
            require(idx >= 0) { "Invalid Base32 character '$ch'" }
            buffer = (buffer shl 5) or idx
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
