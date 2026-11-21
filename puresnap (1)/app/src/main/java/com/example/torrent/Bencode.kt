package com.example.torrent

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

sealed interface BencodeValue {
    data class BInt(val value: Long) : BencodeValue
    data class BBytes(val bytes: ByteArray) : BencodeValue {
        fun asUtf8(): String = String(bytes, StandardCharsets.UTF_8)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BBytes) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class BList(val items: List<BencodeValue>) : BencodeValue
    data class BDict(val entries: LinkedHashMap<String, BencodeValue>) : BencodeValue {
        operator fun get(key: String): BencodeValue? = entries[key]
        fun getString(key: String): String? = (entries[key] as? BBytes)?.asUtf8()
        fun getLong(key: String): Long? = (entries[key] as? BInt)?.value
        fun getList(key: String): BList? = entries[key] as? BList
        fun getDict(key: String): BDict? = entries[key] as? BDict
    }
}

data class DecodedTorrentRoot(
    val root: BencodeValue.BDict,
    val rawInfoBytes: ByteArray?
)

object Bencode {

    fun decode(data: ByteArray): BencodeValue {
        val reader = Reader(data)
        return reader.readValue()
    }

    fun decodeWithInfoSlice(data: ByteArray): DecodedTorrentRoot {
        val reader = Reader(data)
        val root = reader.readValue() as? BencodeValue.BDict
            ?: throw IllegalArgumentException("Torrent root must be a Bencode dictionary")
        val rawInfo = reader.infoSliceStart?.let { start ->
            val end = reader.infoSliceEnd ?: return@let null
            if (end > start && end <= data.size) data.copyOfRange(start, end) else null
        }
        return DecodedTorrentRoot(root = root, rawInfoBytes = rawInfo)
    }

    fun encode(value: BencodeValue): ByteArray {
        val out = ByteArrayOutputStream()
        writeValue(value, out)
        return out.toByteArray()
    }

    private fun writeValue(value: BencodeValue, out: ByteArrayOutputStream) {
        when (value) {
            is BencodeValue.BInt -> {
                out.write('i'.code)
                out.write(value.value.toString().toByteArray(StandardCharsets.US_ASCII))
                out.write('e'.code)
            }
            is BencodeValue.BBytes -> {
                val len = value.bytes.size.toString().toByteArray(StandardCharsets.US_ASCII)
                out.write(len)
                out.write(':'.code)
                out.write(value.bytes)
            }
            is BencodeValue.BList -> {
                out.write('l'.code)
                for (item in value.items) writeValue(item, out)
                out.write('e'.code)
            }
            is BencodeValue.BDict -> {
                out.write('d'.code)
                // Canonical Bencode dictionary keys are sorted lexicographically by raw UTF-8 bytes
                val sortedKeys = value.entries.keys.sortedBy { it }
                for (key in sortedKeys) {
                    val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
                    writeValue(BencodeValue.BBytes(keyBytes), out)
                    writeValue(value.entries.getValue(key), out)
                }
                out.write('e'.code)
            }
        }
    }

    private class Reader(private val data: ByteArray) {
        var pos = 0
        var infoSliceStart: Int? = null
        var infoSliceEnd: Int? = null

        fun readValue(captureInfoKey: Boolean = false): BencodeValue {
            if (pos >= data.size) throw IllegalArgumentException("Unexpected end of Bencode stream")
            return when (val ch = data[pos].toInt().toChar()) {
                'i' -> readInt()
                'l' -> readList()
                'd' -> readDict(captureInfoKey = captureInfoKey || pos == 0)
                in '0'..'9' -> readBytes()
                else -> throw IllegalArgumentException("Invalid Bencode token '$ch' at $pos")
            }
        }

        private fun readInt(): BencodeValue.BInt {
            pos++ // skip 'i'
            val end = indexOf('e'.code.toByte())
            if (end == -1) throw IllegalArgumentException("Unterminated Bencode integer")
            val numStr = String(data, pos, end - pos, StandardCharsets.US_ASCII)
            pos = end + 1
            return BencodeValue.BInt(numStr.toLong())
        }

        private fun readBytes(): BencodeValue.BBytes {
            val colon = indexOf(':'.code.toByte())
            if (colon == -1) throw IllegalArgumentException("Malformed Bencode byte string length")
            val len = String(data, pos, colon - pos, StandardCharsets.US_ASCII).toInt()
            if (len < 0) throw IllegalArgumentException("Negative Bencode byte length")
            val start = colon + 1
            val end = start + len
            if (end > data.size) throw IllegalArgumentException("Truncated Bencode byte string")
            pos = end
            return BencodeValue.BBytes(data.copyOfRange(start, end))
        }

        private fun readList(): BencodeValue.BList {
            pos++ // skip 'l'
            val list = mutableListOf<BencodeValue>()
            while (pos < data.size && data[pos] != 'e'.code.toByte()) {
                list.add(readValue())
            }
            if (pos >= data.size) throw IllegalArgumentException("Unterminated Bencode list")
            pos++ // skip 'e'
            return BencodeValue.BList(list)
        }

        private fun readDict(captureInfoKey: Boolean): BencodeValue.BDict {
            pos++ // skip 'd'
            val map = LinkedHashMap<String, BencodeValue>()
            while (pos < data.size && data[pos] != 'e'.code.toByte()) {
                val key = readBytes().asUtf8()
                val isRootInfo = captureInfoKey && key == "info" && infoSliceStart == null
                if (isRootInfo) infoSliceStart = pos
                val value = readValue(captureInfoKey = false)
                if (isRootInfo) infoSliceEnd = pos
                map[key] = value
            }
            if (pos >= data.size) throw IllegalArgumentException("Unterminated Bencode dictionary")
            pos++ // skip 'e'
            return BencodeValue.BDict(map)
        }

        private fun indexOf(target: Byte): Int {
            for (i in pos until data.size) {
                if (data[i] == target) return i
            }
            return -1
        }
    }
}
