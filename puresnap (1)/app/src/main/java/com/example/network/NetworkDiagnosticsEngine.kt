package com.example.network

import android.content.Context
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.data.DownloadEntity
import com.example.data.DownloadStatus
import com.example.data.MediaType
import com.example.privacy.PrivacyShield
import com.example.torrent.Bencode
import com.example.torrent.BencodeValue
import com.example.torrent.TorrentFileEntry
import com.example.torrent.TorrentMetadata
import com.example.torrent.TorrentParser
import com.example.torrent.TorrentStorageWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.BitSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToLong

data class SpeedTestProgress(
    val phase: String,
    val currentMbps: Double,
    val progressFraction: Float
)

data class SpeedTestResult(
    val pingMs: Long,
    val jitterMs: Long,
    val singleStreamMbps: Double,
    val multiThreadMbps: Double,
    val bytesTransferred: Long,
    val elapsedMs: Long,
    val endpointLabel: String
)

data class OverloadTestProgress(
    val stage: String,
    val progressFraction: Float
)

data class OverloadTestResult(
    val bencodeOpsPerSec: Long,
    val sha1ThroughputMbPerSec: Double,
    val diskWriteMbPerSec: Double,
    val filterUrlsPerSec: Long,
    val dbConcurrencyOpsPerSec: Long,
    val peakConcurrentWorkers: Int,
    val totalElapsedMs: Long,
    val passed: Boolean
)

object NetworkDiagnosticsEngine {

    private const val DEFAULT_PING_URL = "https://speed.cloudflare.com/__down?bytes=0"
    private const val DEFAULT_SINGLE_URL = "https://speed.cloudflare.com/__down?bytes=2000000"
    private const val DEFAULT_MULTI_URL = "https://speed.cloudflare.com/__down?bytes=2000000"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun runNetworkSpeedTest(
        pingUrl: String = DEFAULT_PING_URL,
        singleStreamUrl: String = DEFAULT_SINGLE_URL,
        multiStreamUrl: String = DEFAULT_MULTI_URL,
        endpointLabel: String = "Cloudflare Edge CDN (4-Thread)",
        onProgress: (SpeedTestProgress) -> Unit = {}
    ): Result<SpeedTestResult> = withContext(Dispatchers.IO) {
        runCatching {
            val overallStart = System.nanoTime()

            // Phase 1: Latency & Jitter (4 probes)
            onProgress(SpeedTestProgress("Probing latency & jitter…", 0.0, 0.1f))
            val rtts = mutableListOf<Long>()
            repeat(4) { idx ->
                currentCoroutineContext().ensureActive()
                val t0 = System.nanoTime()
                val req = Request.Builder()
                    .url(pingUrl)
                    .header("User-Agent", "PureSnap/1.0")
                    .header("Cache-Control", "no-store")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} during ping probe")
                    resp.body?.bytes()
                }
                val rttMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                rtts.add(rttMs)
                onProgress(
                    SpeedTestProgress(
                        phase = "Latency probe ${idx + 1}/4 (${rttMs} ms)…",
                        currentMbps = 0.0,
                        progressFraction = 0.10f + (idx + 1) * 0.05f
                    )
                )
            }

            val sortedRtts = rtts.sorted()
            val medianPing = sortedRtts[sortedRtts.size / 2]
            val avgRtt = rtts.average()
            val jitter = rtts.map { abs(it - avgRtt) }.average().roundToLong()

            // Phase 2: Single-Stream Baseline
            onProgress(SpeedTestProgress("Measuring single-stream speed…", 0.0, 0.35f))
            val singleStart = System.nanoTime()
            val singleBytes = streamUrlBytes(singleStreamUrl) { bytesSoFar ->
                val sec = ((System.nanoTime() - singleStart) / 1_000_000_000.0).coerceAtLeast(0.02)
                val mbps = (bytesSoFar * 8.0) / (sec * 1_000_000.0)
                onProgress(SpeedTestProgress("Single-stream download…", mbps, 0.50f))
            }
            val singleSec = ((System.nanoTime() - singleStart) / 1_000_000_000.0).coerceAtLeast(0.001)
            val singleMbps = (singleBytes * 8.0) / (singleSec * 1_000_000.0)

            // Phase 3: 4-Thread Concurrent Burst (mirrors ChunkDownloadPlanner.DEFAULT_MAX_THREADS)
            onProgress(SpeedTestProgress("Launching 4-thread parallel burst…", singleMbps, 0.65f))
            val multiCounter = AtomicLong(0L)
            val multiStart = System.nanoTime()
            coroutineScope {
                val jobs = (0 until ChunkDownloadPlanner.DEFAULT_MAX_THREADS).map {
                    async(Dispatchers.IO) {
                        streamUrlBytes(multiStreamUrl) { delta ->
                            val totalSoFar = multiCounter.addAndGet(delta)
                            val sec = ((System.nanoTime() - multiStart) / 1_000_000_000.0).coerceAtLeast(0.02)
                            val liveMbps = (totalSoFar * 8.0) / (sec * 1_000_000.0)
                            onProgress(
                                SpeedTestProgress(
                                    phase = "4-thread concurrent download…",
                                    currentMbps = liveMbps,
                                    progressFraction = 0.85f
                                )
                            )
                        }
                    }
                }
                jobs.awaitAll()
            }
            val multiBytes = multiCounter.get()
            val multiSec = ((System.nanoTime() - multiStart) / 1_000_000_000.0).coerceAtLeast(0.001)
            val multiMbps = (multiBytes * 8.0) / (multiSec * 1_000_000.0)

            val totalElapsedMs = ((System.nanoTime() - overallStart) / 1_000_000L).coerceAtLeast(1L)
            onProgress(SpeedTestProgress("Speed test complete", multiMbps, 1.0f))

            SpeedTestResult(
                pingMs = medianPing,
                jitterMs = jitter,
                singleStreamMbps = singleMbps,
                multiThreadMbps = multiMbps,
                bytesTransferred = singleBytes + multiBytes,
                elapsedMs = totalElapsedMs,
                endpointLabel = endpointLabel
            )
        }
    }

    private suspend fun streamUrlBytes(
        url: String,
        onChunkDelta: ((Long) -> Unit)? = null
    ): Long {
        var total = 0L
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "PureSnap/1.0")
            .header("Cache-Control", "no-store")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} during speed test")
            val stream = resp.body?.byteStream() ?: throw IllegalStateException("Empty body")
            val buf = ByteArray(32 * 1024)
            var lastEmit = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(buf)
                if (read == -1) break
                total += read
                lastEmit += read
                if (onChunkDelta != null && lastEmit >= 128 * 1024) {
                    onChunkDelta(lastEmit)
                    lastEmit = 0L
                }
            }
            if (onChunkDelta != null && lastEmit > 0L) {
                onChunkDelta(lastEmit)
            }
        }
        return total
    }

    suspend fun runOverloadStressTest(
        context: Context,
        onProgress: (OverloadTestProgress) -> Unit = {}
    ): OverloadTestResult = withContext(Dispatchers.Default) {
        val totalStart = System.nanoTime()
        val tempRoot = File(context.cacheDir, "puresnap_stress_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        val stressDb = Room.inMemoryDatabaseBuilder(
            context.applicationContext,
            AppDatabase::class.java
        ).build()

        try {
            // 1. Bencode encode/decode overload (1,200 multi-file dictionary cycles)
            onProgress(OverloadTestProgress("Stressing Bencode parser (1,200 trees)…", 0.15f))
            val sampleDict = buildStressTorrentDict(fileCount = 32, pieceCount = 64)
            val encodedSample = Bencode.encode(sampleDict)
            val bencodeStart = System.nanoTime()
            val bencodeCycles = 1200
            for (i in 0 until bencodeCycles) {
                currentCoroutineContext().ensureActive()
                val decoded = Bencode.decodeWithInfoSlice(encodedSample)
                require(decoded.root is BencodeValue.BDict)
            }
            val bencodeSec = ((System.nanoTime() - bencodeStart) / 1_000_000_000.0).coerceAtLeast(0.001)
            val bencodeOpsPerSec = (bencodeCycles / bencodeSec).roundToLong()

            // 2. SHA-1 Torrent Piece Verification Saturation (16 MB across 64 x 256 KB pieces, 8 workers)
            onProgress(OverloadTestProgress("Saturating SHA-1 piece verifier (16 MB)…", 0.35f))
            val pieceSize = 256 * 1024
            val pieceCount = 64
            val samplePiece = ByteArray(pieceSize) { (it * 31 + 7).toByte() }
            val expectedHash = TorrentParser.sha1(samplePiece)
            val shaStart = System.nanoTime()
            coroutineScope {
                (0 until 8).map { workerIdx ->
                    async(Dispatchers.Default) {
                        val piecesPerWorker = pieceCount / 8
                        repeat(piecesPerWorker) { p ->
                            currentCoroutineContext().ensureActive()
                            val actual = TorrentParser.sha1(samplePiece)
                            require(actual.contentEquals(expectedHash)) { "SHA-1 mismatch in worker $workerIdx#$p" }
                        }
                    }
                }.awaitAll()
            }
            val shaSec = ((System.nanoTime() - shaStart) / 1_000_000_000.0).coerceAtLeast(0.001)
            val totalShaMb = (pieceSize.toDouble() * pieceCount) / (1024.0 * 1024.0)
            val sha1ThroughputMbPerSec = totalShaMb / shaSec

            // 3. Concurrent Multi-File RandomAccessFile Piece Boundary Writes + BitSet Checkpointing (8 MB across 64 pieces)
            onProgress(OverloadTestProgress("Stressing concurrent disk boundary I/O…", 0.58f))
            val diskPieceSize = 128 * 1024
            val diskPieces = 64
            val totalDiskBytes = diskPieceSize.toLong() * diskPieces
            val fakeHashes = List(diskPieces) { ByteArray(20) { it.toByte() } }
            val syntheticMetadata = TorrentMetadata(
                name = "StressPack",
                infoHashBytes = ByteArray(20) { 1 },
                infoHashHex = "01".repeat(20),
                pieceLength = diskPieceSize.toLong(),
                totalLength = totalDiskBytes,
                pieceHashes = fakeHashes,
                files = listOf(
                    TorrentFileEntry(0, "chunk_a.mp4", totalDiskBytes / 3, 0L),
                    TorrentFileEntry(1, "sub/chunk_b.mkv", totalDiskBytes / 3, totalDiskBytes / 3),
                    TorrentFileEntry(2, "sub/chunk_c.m4a", totalDiskBytes - 2 * (totalDiskBytes / 3), 2 * (totalDiskBytes / 3))
                ),
                trackers = emptyList(),
                webSeeds = emptyList(),
                rawInfoBytes = null
            )
            val payloadChunk = ByteArray(diskPieceSize) { (it xor 0x5A).toByte() }
            val selectedAll = setOf(0, 1, 2)
            val bitSet = BitSet(diskPieces)
            val diskStart = System.nanoTime()
            withContext(Dispatchers.IO) {
                coroutineScope {
                    (0 until 4).map { worker ->
                        async(Dispatchers.IO) {
                            for (pieceIdx in worker until diskPieces step 4) {
                                currentCoroutineContext().ensureActive()
                                TorrentStorageWriter.writePieceToFiles(
                                    saveDir = tempRoot,
                                    metadata = syntheticMetadata,
                                    pieceIndex = pieceIdx,
                                    pieceData = payloadChunk,
                                    selectedFileIndices = selectedAll
                                )
                                synchronized(bitSet) {
                                    bitSet.set(pieceIdx)
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            val encodedBitfield = TorrentStorageWriter.encodeBitSet(bitSet, diskPieces)
            val decodedBitfield = TorrentStorageWriter.decodeBitSet(encodedBitfield)
            require(decodedBitfield.cardinality() == diskPieces) { "BitSet verification failed" }
            val diskSec = ((System.nanoTime() - diskStart) / 1_000_000_000.0).coerceAtLeast(0.001)
            val diskWriteMbPerSec = (totalDiskBytes / (1024.0 * 1024.0)) / diskSec

            // 4. PrivacyShield URL Filter & Sanitizer Burst (10,000 mixed ad/tracker/clean URLs)
            onProgress(OverloadTestProgress("Benchmarking PrivacyShield (10,000 URLs)…", 0.78f))
            val testUrls = listOf(
                "https://securepubads.g.doubleclick.net/gampad/ads?iu=/1234&utm_source=feed",
                "https://api.mixpanel.com/track?data=xyz&fbclid=abc123",
                "https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4?utm_medium=share&gclid=99",
                "https://cdn.example.org/stream/master.m3u8?token=clean&si=remove"
            )
            val filterIterations = 10_000
            val filterStart = System.nanoTime()
            var blockedCount = 0
            for (i in 0 until filterIterations) {
                val u = testUrls[i and 3]
                if (PrivacyShield.matchBlockedHost(u) != null) blockedCount++
                PrivacyShield.sanitizeUrl(u)
            }
            require(blockedCount == filterIterations / 2) { "Unexpected PrivacyShield match count" }
            val filterSec = ((System.nanoTime() - filterStart) / 1_000_000_000.0).coerceAtLeast(0.001)
            val filterUrlsPerSec = (filterIterations / filterSec).roundToLong()

            // 5. Room Database High-Concurrency Contention Test (120 parallel inserts & reads)
            onProgress(OverloadTestProgress("Saturating Room DB queue (120 concurrent ops)…", 0.92f))
            val downloadDao = stressDb.downloadDao()
            val dbOpsCount = 120
            val dbStart = System.nanoTime()
            withContext(Dispatchers.IO) {
                coroutineScope {
                    (0 until 12).map { group ->
                        async(Dispatchers.IO) {
                            repeat(10) { item ->
                                currentCoroutineContext().ensureActive()
                                val idx = group * 10 + item
                                val id = downloadDao.insert(
                                    DownloadEntity(
                                        title = "Stress Stream #$idx",
                                        sourceUrl = "https://example.org/$idx",
                                        mediaUrl = "https://cdn.example.org/$idx.mp4",
                                        localFilePath = "${tempRoot.absolutePath}/$idx.mp4",
                                        mimeType = "video/mp4",
                                        mediaType = MediaType.VIDEO,
                                        formatLabel = "MP4",
                                        qualityLabel = "1080p",
                                        totalBytes = 10_000_000L,
                                        downloadedBytes = 0L,
                                        status = DownloadStatus.QUEUED
                                    )
                                )
                                downloadDao.updateProgress(id, 5_000_000L, 10_000_000L, 2_000_000L, DownloadStatus.DOWNLOADING)
                                downloadDao.updateStatus(id, DownloadStatus.COMPLETED, null)
                            }
                        }
                    }.awaitAll()
                }
            }
            val incompleteAfter = downloadDao.getIncompleteDownloads()
            require(incompleteAfter.isEmpty()) { "DB concurrency left incomplete records" }
            val dbSec = ((System.nanoTime() - dbStart) / 1_000_000_000.0).coerceAtLeast(0.001)
            val dbConcurrencyOpsPerSec = ((dbOpsCount * 3) / dbSec).roundToLong()

            val totalElapsedMs = ((System.nanoTime() - totalStart) / 1_000_000L).coerceAtLeast(1L)
            onProgress(OverloadTestProgress("Overload test passed", 1.0f))

            OverloadTestResult(
                bencodeOpsPerSec = bencodeOpsPerSec,
                sha1ThroughputMbPerSec = sha1ThroughputMbPerSec,
                diskWriteMbPerSec = diskWriteMbPerSec,
                filterUrlsPerSec = filterUrlsPerSec,
                dbConcurrencyOpsPerSec = dbConcurrencyOpsPerSec,
                peakConcurrentWorkers = 12,
                totalElapsedMs = totalElapsedMs,
                passed = true
            )
        } finally {
            runCatching { stressDb.close() }
            runCatching { tempRoot.deleteRecursively() }
        }
    }

    private fun buildStressTorrentDict(fileCount: Int, pieceCount: Int): BencodeValue.BDict {
        val fileList = (0 until fileCount).map { idx ->
            BencodeValue.BDict(
                linkedMapOf(
                    "length" to BencodeValue.BInt(1_048_576L * (idx + 1)),
                    "path" to BencodeValue.BList(
                        listOf(
                            BencodeValue.BBytes("season_1".toByteArray(StandardCharsets.UTF_8)),
                            BencodeValue.BBytes("episode_$idx.mp4".toByteArray(StandardCharsets.UTF_8))
                        )
                    )
                )
            )
        }
        val info = BencodeValue.BDict(
            linkedMapOf(
                "files" to BencodeValue.BList(fileList),
                "name" to BencodeValue.BBytes("Open_Archive_Bundle".toByteArray(StandardCharsets.UTF_8)),
                "piece length" to BencodeValue.BInt(262_144L),
                "pieces" to BencodeValue.BBytes(ByteArray(pieceCount * 20) { (it and 0xFF).toByte() })
            )
        )
        return BencodeValue.BDict(
            linkedMapOf(
                "announce" to BencodeValue.BBytes("https://tracker.example.org/announce".toByteArray(StandardCharsets.UTF_8)),
                "info" to info
            )
        )
    }
}
