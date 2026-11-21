package com.example.network

data class ByteRange(
    val index: Int,
    val startByte: Long,
    val endByte: Long
) {
    val expectedLength: Long
        get() = (endByte - startByte) + 1L
}

object ChunkDownloadPlanner {

    const val DEFAULT_MAX_THREADS = 4
    const val DEFAULT_MIN_BYTES_PER_CHUNK = 512L * 1024L // 512 KB

    /**
     * Splits [totalBytes] into balanced inclusive [ByteRange] segments when HTTP Range requests
     * are supported and the file is large enough to benefit from concurrent threads.
     */
    fun planChunks(
        totalBytes: Long,
        supportsRange: Boolean,
        maxThreads: Int = DEFAULT_MAX_THREADS,
        minBytesPerChunk: Long = DEFAULT_MIN_BYTES_PER_CHUNK
    ): List<ByteRange> {
        if (totalBytes <= 0L) return emptyList()
        if (!supportsRange || maxThreads <= 1 || totalBytes < minBytesPerChunk * 2L) {
            return listOf(ByteRange(index = 0, startByte = 0L, endByte = totalBytes - 1L))
        }

        val optimalThreads = (totalBytes / minBytesPerChunk)
            .toInt()
            .coerceIn(1, maxThreads)

        if (optimalThreads <= 1) {
            return listOf(ByteRange(index = 0, startByte = 0L, endByte = totalBytes - 1L))
        }

        val chunkSize = totalBytes / optimalThreads
        val ranges = ArrayList<ByteRange>(optimalThreads)
        var currentStart = 0L

        for (i in 0 until optimalThreads) {
            val isLast = i == optimalThreads - 1
            val currentEnd = if (isLast) totalBytes - 1L else (currentStart + chunkSize - 1L)
            ranges.add(ByteRange(index = i, startByte = currentStart, endByte = currentEnd))
            currentStart = currentEnd + 1L
        }

        return ranges
    }

    /**
     * Computes the exact byte offset to request in `Range: bytes=<offset>-<endByte>` when resuming
     * a chunk from an existing `.part.<index>` file on disk.
     *
     * Returns null if the chunk is already fully downloaded.
     */
    fun computeResumeOffset(range: ByteRange, existingPartBytes: Long): Long? {
        if (existingPartBytes <= 0L) return range.startByte
        if (existingPartBytes >= range.expectedLength) return null
        return range.startByte + existingPartBytes
    }
}
