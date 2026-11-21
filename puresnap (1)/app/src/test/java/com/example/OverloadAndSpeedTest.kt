package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.AppDatabase
import com.example.data.DownloadEntity
import com.example.data.DownloadStatus
import com.example.data.MediaType
import com.example.data.MediaVaultRepository
import com.example.data.TorrentEntity
import com.example.data.TorrentStatus
import com.example.network.ChunkDownloadPlanner
import com.example.network.MediaDownloadEngine
import com.example.network.NetworkDiagnosticsEngine
import com.example.torrent.PeerAddress
import com.example.torrent.PeerSession
import com.example.torrent.PeerWireCodec
import com.example.torrent.TorrentEngine
import com.example.torrent.TorrentFileEntry
import com.example.torrent.TorrentMetadata
import com.example.torrent.TorrentParser
import com.example.torrent.TorrentStorageWriter
import com.example.torrent.TrackerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.BitSet
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OverloadAndSpeedTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun overloadTest_concurrentDownloadAndTorrentQueueSaturation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val downloadDao = db.downloadDao()
            val torrentDao = db.torrentDao()
            val downloadRepo = MediaVaultRepository(downloadDao)
            val mediaEngine = MediaDownloadEngine(context, downloadRepo)
            val torrentEngine = TorrentEngine(context, torrentDao)

            val downloadCount = 100
            val torrentCount = 60

            // Burst-insert 100 downloads and 60 torrents across 10 concurrent coroutines
            val insertedDownloadIds = (0 until 10).map { group ->
                async(Dispatchers.IO) {
                    (0 until 10).map { offset ->
                        val idx = group * 10 + offset
                        downloadDao.insert(
                            DownloadEntity(
                                title = "Overload Media #$idx",
                                sourceUrl = "https://example.org/watch/$idx",
                                mediaUrl = "https://cdn.example.org/media/$idx.mp4",
                                localFilePath = "${tempFolder.root.absolutePath}/media_$idx.mp4",
                                mimeType = "video/mp4",
                                mediaType = MediaType.VIDEO,
                                formatLabel = "MP4",
                                qualityLabel = "1080p",
                                totalBytes = 12_000_000L,
                                downloadedBytes = 1_000_000L,
                                status = DownloadStatus.DOWNLOADING
                            )
                        )
                    }
                }
            }.awaitAll().flatten()

            val insertedTorrentIds = (0 until 6).map { group ->
                async(Dispatchers.IO) {
                    (0 until 10).map { offset ->
                        val idx = group * 10 + offset
                        val hex = String.format(Locale.US, "%040x", idx + 1)
                        torrentDao.insert(
                            TorrentEntity(
                                infoHashHex = hex,
                                name = "Overload Swarm #$idx",
                                sourceUri = "magnet:?xt=urn:btih:$hex",
                                saveDirPath = "${tempFolder.root.absolutePath}/t_$idx",
                                totalBytes = 50_000_000L,
                                totalPieces = 200,
                                status = TorrentStatus.DOWNLOADING
                            )
                        )
                    }
                }
            }.awaitAll().flatten()

            assertEquals(downloadCount, insertedDownloadIds.size)
            assertEquals(torrentCount, insertedTorrentIds.size)
            assertEquals(downloadCount, downloadDao.getIncompleteDownloads().size)
            assertEquals(torrentCount, torrentDao.getIncompleteTorrents().size)

            // Trigger WorkManager recovery under heavy backlog
            mediaEngine.resumePendingDownloadsIfNeeded(wifiOnly = false)
            torrentEngine.resumePendingTorrentsIfNeeded(wifiOnly = false)

            val wm = WorkManager.getInstance(context)
            var enqueuedDownloads = 0
            var enqueuedTorrents = 0
            repeat(40) {
                enqueuedDownloads = insertedDownloadIds.count { id ->
                    wm.getWorkInfosForUniqueWork(MediaDownloadEngine.uniqueWorkName(id)).get()
                        .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
                }
                enqueuedTorrents = insertedTorrentIds.count { id ->
                    wm.getWorkInfosForUniqueWork(TorrentEngine.uniqueWorkName(id)).get()
                        .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
                }
                if (enqueuedDownloads == downloadCount && enqueuedTorrents == torrentCount) return@repeat
                delay(50L)
            }

            assertEquals("All 100 interrupted downloads must re-attach", downloadCount, enqueuedDownloads)
            assertEquals("All 60 interrupted torrents must re-attach", torrentCount, enqueuedTorrents)
        } finally {
            db.close()
        }
    }

    @Test
    fun speedTest_multiThreadedHttpRangeTransferAndResumeIntegrity() = runBlocking {
        // 4 MB synthetic media payload
        val totalSize = 4 * 1024 * 1024
        val payload = ByteArray(totalSize) { idx -> ((idx * 17 + 31) and 0xFF).toByte() }
        val expectedSha256 = sha256(payload)

        val running = AtomicBoolean(true)
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val serverPort = server.localPort
        val serverThread = Thread {
            while (running.get()) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    sock.use { clientSock ->
                        val inp = BufferedInputStream(clientSock.getInputStream())
                        val headers = readHttpHeaders(inp)
                        val rangeLine = headers.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                        val out = clientSock.getOutputStream()
                        if (rangeLine != null) {
                            val spec = rangeLine.substringAfter("bytes=").trim()
                            val start = spec.substringBefore("-").toIntOrNull() ?: 0
                            val end = spec.substringAfter("-").toIntOrNull() ?: (totalSize - 1)
                            val clampedEnd = end.coerceAtMost(totalSize - 1)
                            val len = (clampedEnd - start) + 1
                            val head = "HTTP/1.1 206 Partial Content\r\n" +
                                "Accept-Ranges: bytes\r\n" +
                                "Content-Range: bytes $start-$clampedEnd/$totalSize\r\n" +
                                "Content-Length: $len\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(head.toByteArray(StandardCharsets.US_ASCII))
                            out.write(payload, start, len)
                        } else {
                            val head = "HTTP/1.1 200 OK\r\n" +
                                "Accept-Ranges: bytes\r\n" +
                                "Content-Length: $totalSize\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(head.toByteArray(StandardCharsets.US_ASCII))
                            out.write(payload)
                        }
                        out.flush()
                    }
                }.start()
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            val httpClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val url = "http://127.0.0.1:$serverPort/video.mp4"
            val chunks = ChunkDownloadPlanner.planChunks(
                totalBytes = totalSize.toLong(),
                supportsRange = true,
                maxThreads = 4
            )
            assertEquals(4, chunks.size)

            val outDir = tempFolder.newFolder("http_speed")
            // Pre-seed chunk 0 with partial 256 KB to test mid-chunk exact offset resume under parallel execution
            val preSeedBytes = 256 * 1024
            File(outDir, "part.0").writeBytes(payload.copyOfRange(0, preSeedBytes))

            val startNs = System.nanoTime()
            chunks.map { range ->
                async(Dispatchers.IO) {
                    val partFile = File(outDir, "part.${range.index}")
                    val existing = if (partFile.exists()) partFile.length() else 0L
                    val resumeOffset = ChunkDownloadPlanner.computeResumeOffset(range, existing) ?: return@async
                    val req = Request.Builder()
                        .url(url)
                        .header("Range", "bytes=$resumeOffset-${range.endByte}")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        assertEquals(206, resp.code)
                        val bytes = resp.body!!.bytes()
                        if (existing > 0L) {
                            partFile.appendBytes(bytes)
                        } else {
                            partFile.writeBytes(bytes)
                        }
                    }
                }
            }.awaitAll()

            val merged = ByteArrayOutputStream(totalSize)
            chunks.forEach { range ->
                merged.write(File(outDir, "part.${range.index}").readBytes())
            }
            val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.001)
            val assembled = merged.toByteArray()
            val throughputMbPerSec = (totalSize / (1024.0 * 1024.0)) / elapsedSec

            assertEquals(totalSize, assembled.size)
            assertArrayEquals(expectedSha256, sha256(assembled))
            assertTrue("Loopback 4-thread HTTP throughput should exceed 2 MB/s (got $throughputMbPerSec MB/s)", throughputMbPerSec > 2.0)
        } finally {
            running.set(false)
            runCatching { server.close() }
        }
    }

    @Test
    fun overloadTest_bitTorrentMultiPeerSwarmConcurrentTransferAndHashVerification() = runBlocking {
        // 3 files totaling 1.5 MB split into 24 x 64 KB pieces served concurrently by 4 BitTorrent peers
        val file1 = ByteArray(400_000) { ((it * 13) and 0xFF).toByte() }
        val file2 = ByteArray(600_000) { ((it * 29 + 3) and 0xFF).toByte() }
        val file3 = ByteArray(500_000) { ((it * 43 + 9) and 0xFF).toByte() }
        val combined = file1 + file2 + file3
        val pieceLength = 65_536
        val totalPieces = (combined.size + pieceLength - 1) / pieceLength

        val pieceDataList = (0 until totalPieces).map { idx ->
            val start = idx * pieceLength
            val end = minOf(start + pieceLength, combined.size)
            combined.copyOfRange(start, end)
        }
        val pieceHashes = pieceDataList.map { TorrentParser.sha1(it) }
        val infoHash = TorrentParser.sha1("overload-swarm-info".toByteArray(StandardCharsets.UTF_8))

        val metadata = TorrentMetadata(
            name = "SwarmOverloadBundle",
            infoHashBytes = infoHash,
            infoHashHex = TorrentParser.bytesToHex(infoHash),
            pieceLength = pieceLength.toLong(),
            totalLength = combined.size.toLong(),
            pieceHashes = pieceHashes,
            files = listOf(
                TorrentFileEntry(0, "episode_1.mp4", file1.size.toLong(), 0L),
                TorrentFileEntry(1, "sub/episode_2.mkv", file2.size.toLong(), file1.size.toLong()),
                TorrentFileEntry(2, "sub/soundtrack.m4a", file3.size.toLong(), (file1.size + file2.size).toLong())
            ),
            trackers = emptyList(),
            webSeeds = emptyList(),
            rawInfoBytes = null
        )

        // Spin up 4 local BitTorrent peer TCP servers speaking BEP-3 handshake + unchoke + bitfield + block replies
        val running = AtomicBoolean(true)
        val peerServers = (0 until 4).map { ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")) }
        peerServers.forEachIndexed { idx, srv ->
            val thread = Thread {
                val peerPid = TrackerClient.generatePeerId()
                while (running.get()) {
                    val sock = runCatching { srv.accept() }.getOrNull() ?: break
                    Thread {
                        sock.use { client ->
                            val dis = DataInputStream(client.getInputStream())
                            val dos = DataOutputStream(client.getOutputStream())
                            val handshakeIn = ByteArray(68)
                            dis.readFully(handshakeIn)
                            dos.write(PeerWireCodec.buildHandshake(infoHash, peerPid))
                            dos.write(PeerWireCodec.buildSimpleMessage(PeerWireCodec.MSG_UNCHOKE))
                            // Full bitfield (all 1s)
                            val bfBytes = ByteArray((totalPieces + 7) / 8) { 0xFF.toByte() }
                            val bfBuf = ByteBuffer.allocate(5 + bfBytes.size)
                                .putInt(1 + bfBytes.size)
                                .put(PeerWireCodec.MSG_BITFIELD.toByte())
                                .put(bfBytes)
                                .array()
                            dos.write(bfBuf)
                            dos.flush()

                            while (running.get()) {
                                val msgLen = runCatching { dis.readInt() }.getOrNull() ?: break
                                if (msgLen == 0) continue
                                val msgId = dis.readUnsignedByte()
                                val payload = ByteArray(msgLen - 1)
                                if (payload.isNotEmpty()) dis.readFully(payload)
                                if (msgId == PeerWireCodec.MSG_REQUEST && payload.size == 12) {
                                    val buf = ByteBuffer.wrap(payload)
                                    val pIdx = buf.int
                                    val begin = buf.int
                                    val reqLen = buf.int
                                    val slice = pieceDataList[pIdx].copyOfRange(begin, begin + reqLen)
                                    val reply = ByteBuffer.allocate(13 + slice.size)
                                        .putInt(9 + slice.size)
                                        .put(PeerWireCodec.MSG_PIECE.toByte())
                                        .putInt(pIdx)
                                        .putInt(begin)
                                        .put(slice)
                                        .array()
                                    dos.write(reply)
                                    dos.flush()
                                }
                            }
                        }
                    }.start()
                }
            }
            thread.name = "MockPeer-$idx"
            thread.isDaemon = true
            thread.start()
        }

        try {
            val saveDir = tempFolder.newFolder("swarm_output")
            val pending = ConcurrentLinkedDeque((0 until totalPieces).toList())
            val bitSet = BitSet(totalPieces)
            val lock = Any()
            val bytesReceived = AtomicLong(0L)
            val startNs = System.nanoTime()

            // Run 4 concurrent PeerSession workers
            peerServers.map { srv ->
                async(Dispatchers.IO) {
                    val peer = PeerAddress("127.0.0.1", srv.localPort)
                    val session = PeerSession(peer, infoHash, TrackerClient.generatePeerId())
                    try {
                        assertTrue("Handshake with peer ${peer.port} failed", session.connectAndHandshake(timeoutMs = 4000))
                        session.sendInterested()
                        session.readKeepAliveOrHandshakeState(timeoutMs = 500)
                        while (true) {
                            val pieceIdx = pending.pollFirst() ?: break
                            val bytes = session.downloadPiece(
                                pieceIndex = pieceIdx,
                                pieceLength = metadata.pieceSize(pieceIdx).toInt(),
                                isCancelled = { false }
                            )
                            require(bytes != null) { "Failed to fetch piece $pieceIdx" }
                            require(TorrentParser.sha1(bytes).contentEquals(metadata.pieceHashes[pieceIdx])) {
                                "SHA-1 mismatch on piece $pieceIdx"
                            }
                            TorrentStorageWriter.writePieceToFiles(
                                saveDir = saveDir,
                                metadata = metadata,
                                pieceIndex = pieceIdx,
                                pieceData = bytes,
                                selectedFileIndices = setOf(0, 1, 2)
                            )
                            synchronized(lock) { bitSet.set(pieceIdx) }
                            bytesReceived.addAndGet(bytes.size.toLong())
                        }
                    } finally {
                        session.close()
                    }
                }
            }.awaitAll()

            val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.001)
            val swarmMbPerSec = (bytesReceived.get() / (1024.0 * 1024.0)) / elapsedSec

            assertEquals(totalPieces, bitSet.cardinality())
            assertArrayEquals(file1, File(saveDir, "episode_1.mp4").readBytes())
            assertArrayEquals(file2, File(saveDir, "sub/episode_2.mkv").readBytes())
            assertArrayEquals(file3, File(saveDir, "sub/soundtrack.m4a").readBytes())
            assertTrue("Multi-peer BitTorrent swarm speed should exceed 1 MB/s (got $swarmMbPerSec MB/s)", swarmMbPerSec > 1.0)
        } finally {
            running.set(false)
            peerServers.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun speedTest_diagnosticsEngineSpeedAndOverloadStressEndToEnd() = runBlocking {
        // 1. Run full Overload Stress Test via NetworkDiagnosticsEngine
        val overloadResult = NetworkDiagnosticsEngine.runOverloadStressTest(context)
        assertTrue(overloadResult.passed)
        assertTrue("Bencode rate should be positive", overloadResult.bencodeOpsPerSec > 50L)
        assertTrue("SHA-1 rate should exceed 20 MB/s", overloadResult.sha1ThroughputMbPerSec > 20.0)
        assertTrue("Disk I/O rate should exceed 5 MB/s", overloadResult.diskWriteMbPerSec > 5.0)
        assertTrue("PrivacyShield rate should exceed 1000 URLs/s", overloadResult.filterUrlsPerSec > 1_000L)
        assertTrue("Room DB concurrency ops/s should be positive", overloadResult.dbConcurrencyOpsPerSec > 100L)

        // 2. Run Speed Test via NetworkDiagnosticsEngine against localhost speed server
        val testPayload = ByteArray(1_048_576) { (it and 0xFF).toByte() } // 1 MB per probe
        val running = AtomicBoolean(true)
        val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val t = Thread {
            while (running.get()) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    sock.use { s ->
                        val headers = readHttpHeaders(BufferedInputStream(s.getInputStream()))
                        val firstLine = headers.firstOrNull().orEmpty()
                        val isPing = firstLine.contains("bytes=0")
                        val out = s.getOutputStream()
                        if (isPing) {
                            val resp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            out.write(resp.toByteArray(StandardCharsets.US_ASCII))
                        } else {
                            val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${testPayload.size}\r\nConnection: close\r\n\r\n"
                            out.write(resp.toByteArray(StandardCharsets.US_ASCII))
                            out.write(testPayload)
                        }
                        out.flush()
                    }
                }.start()
            }
        }
        t.isDaemon = true
        t.start()

        try {
            val speedResult = NetworkDiagnosticsEngine.runNetworkSpeedTest(
                pingUrl = "http://127.0.0.1:$port/__down?bytes=0",
                singleStreamUrl = "http://127.0.0.1:$port/__down?bytes=1048576",
                multiStreamUrl = "http://127.0.0.1:$port/__down?bytes=1048576",
                endpointLabel = "Localhost Loopback Benchmark"
            ).getOrThrow()

            assertTrue("Ping should be >= 1 ms", speedResult.pingMs >= 1L)
            assertTrue("Single-stream Mbps should be positive", speedResult.singleStreamMbps > 1.0)
            assertTrue("4-thread Mbps should be positive", speedResult.multiThreadMbps > 1.0)
            assertEquals(5L * testPayload.size, speedResult.bytesTransferred)
        } finally {
            running.set(false)
            runCatching { server.close() }
        }
    }

    private fun readHttpHeaders(inp: BufferedInputStream): List<String> {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) break
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                sb.clear()
                if (line.isEmpty()) break
                lines.add(line)
            } else {
                sb.append(b.toChar())
            }
        }
        return lines
    }

    private fun sha256(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
