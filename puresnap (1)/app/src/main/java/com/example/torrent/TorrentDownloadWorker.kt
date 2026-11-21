package com.example.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.R
import com.example.data.AppDatabase
import com.example.data.TorrentDao
import com.example.data.TorrentEntity
import com.example.data.TorrentStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.BitSet
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TorrentDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val torrentDao: TorrentDao = AppDatabase.getInstance(appContext).torrentDao()
    private val trackerClient = TrackerClient()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun doWork(): Result {
        val torrentId = inputData.getLong(KEY_TORRENT_ID, -1L)
        if (torrentId <= 0L) return Result.failure()

        var entity = torrentDao.getById(torrentId) ?: return Result.failure()
        if (entity.status == TorrentStatus.COMPLETED ||
            entity.status == TorrentStatus.PAUSED ||
            entity.status == TorrentStatus.CANCELLED
        ) {
            return Result.success()
        }

        setForeground(
            createForegroundInfo(
                torrentId = torrentId,
                title = entity.name,
                text = "Connecting to BitTorrent swarm…",
                percent = -1
            )
        )

        return try {
            val metadata = ensureMetadataLoaded(entity)
            entity = torrentDao.getById(torrentId) ?: return Result.success()

            val saveDir = File(entity.saveDirPath).apply { mkdirs() }
            val selectedIndices = entity.selectedIndicesSet()
            val targetPieces = metadata.piecesForSelectedFiles(selectedIndices)
                .toList()
                .let { if (entity.sequentialMode) it.sorted() else it.shuffled() }

            val bitSet = TorrentStorageWriter.decodeBitSet(entity.completedBitfieldBase64)
            val pendingPieces = ConcurrentLinkedDeque<Int>()
            var alreadyCompletedBytes = 0L
            var completedCountInitial = 0

            for (pieceIdx in targetPieces) {
                if (bitSet.get(pieceIdx)) {
                    completedCountInitial++
                    alreadyCompletedBytes += metadata.pieceSize(pieceIdx)
                } else {
                    pendingPieces.addLast(pieceIdx)
                }
            }

            if (pendingPieces.isEmpty()) {
                torrentDao.updateSwarmProgress(
                    id = torrentId,
                    downloadedBytes = entity.totalBytes.coerceAtLeast(alreadyCompletedBytes),
                    speedBytesPerSec = 0L,
                    completedPieces = targetPieces.size,
                    bitfieldBase64 = TorrentStorageWriter.encodeBitSet(bitSet, metadata.totalPieces),
                    seeders = entity.seeders,
                    leechers = entity.leechers,
                    connectedPeers = 0,
                    status = TorrentStatus.COMPLETED
                )
                return Result.success()
            }

            val peerId = TrackerClient.generatePeerId()
            val totalSelectedBytes = TorrentStorageWriter.calculateSelectedBytes(metadata, selectedIndices)
            val downloadedBytesAtomic = AtomicLong(alreadyCompletedBytes)
            val intervalBytesAtomic = AtomicLong(0L)
            val completedPiecesAtomic = AtomicInteger(completedCountInitial)
            val activePeersAtomic = AtomicInteger(0)
            val seedersAtomic = AtomicInteger(entity.seeders)
            val leechersAtomic = AtomicInteger(entity.leechers)

            // Announce to HTTP/UDP trackers to discover peers
            val announceResult = trackerClient.announceAll(
                trackers = metadata.trackers,
                infoHashBytes = metadata.infoHashBytes,
                peerId = peerId,
                downloaded = alreadyCompletedBytes,
                left = (totalSelectedBytes - alreadyCompletedBytes).coerceAtLeast(0L)
            )
            seedersAtomic.set(announceResult.seeders)
            leechersAtomic.set(announceResult.leechers)

            if (announceResult.peers.isEmpty() && metadata.webSeeds.isEmpty()) {
                torrentDao.updateStatus(
                    torrentId,
                    TorrentStatus.QUEUED,
                    "No reachable peers or web seeds yet — retrying automatically"
                )
                return Result.retry()
            }

            val bitSetLock = Any()

            coroutineScope {
                val reporterJob = launch(Dispatchers.IO) {
                    var lastTime = System.currentTimeMillis()
                    while (isActive) {
                        delay(500L)
                        val latest = torrentDao.getById(torrentId)
                        if (latest == null ||
                            latest.status == TorrentStatus.PAUSED ||
                            latest.status == TorrentStatus.CANCELLED
                        ) {
                            break
                        }

                        val now = System.currentTimeMillis()
                        val elapsed = (now - lastTime).coerceAtLeast(1L)
                        val recent = intervalBytesAtomic.getAndSet(0L)
                        val speed = (recent * 1000L) / elapsed
                        val donePieces = completedPiecesAtomic.get()
                        val doneBytes = downloadedBytesAtomic.get().coerceAtMost(totalSelectedBytes)
                        val totalTargetPieces = targetPieces.size.coerceAtLeast(1)
                        val percent = ((donePieces * 100L) / totalTargetPieces).toInt().coerceIn(0, 100)
                        val encodedBitfield = synchronized(bitSetLock) {
                            TorrentStorageWriter.encodeBitSet(bitSet, metadata.totalPieces)
                        }

                        torrentDao.updateSwarmProgress(
                            id = torrentId,
                            downloadedBytes = doneBytes,
                            speedBytesPerSec = speed,
                            completedPieces = donePieces,
                            bitfieldBase64 = encodedBitfield,
                            seeders = seedersAtomic.get(),
                            leechers = leechersAtomic.get(),
                            connectedPeers = activePeersAtomic.get(),
                            status = TorrentStatus.DOWNLOADING
                        )

                        val progressText = buildString {
                            append("$percent% • $donePieces/$totalTargetPieces pieces")
                            append(" • ${activePeersAtomic.get()} peers")
                            if (speed > 0L) append(" • ${formatSpeed(speed)}")
                        }
                        setForeground(
                            createForegroundInfo(
                                torrentId = torrentId,
                                title = metadata.name,
                                text = progressText,
                                percent = percent
                            )
                        )
                        lastTime = now
                    }
                }

                try {
                    val workerJobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

                    // Launch up to 6 concurrent BitTorrent peer downloaders
                    val peerQueue = ConcurrentLinkedDeque(announceResult.peers.take(40))
                    val peerWorkerCount = minOf(6, peerQueue.size.coerceAtLeast(0))
                    repeat(peerWorkerCount) {
                        workerJobs.add(
                            async(Dispatchers.IO) {
                                runPeerWorker(
                                    torrentId = torrentId,
                                    metadata = metadata,
                                    peerId = peerId,
                                    saveDir = saveDir,
                                    selectedIndices = selectedIndices,
                                    peerQueue = peerQueue,
                                    pendingPieces = pendingPieces,
                                    bitSet = bitSet,
                                    bitSetLock = bitSetLock,
                                    downloadedBytesAtomic = downloadedBytesAtomic,
                                    intervalBytesAtomic = intervalBytesAtomic,
                                    completedPiecesAtomic = completedPiecesAtomic,
                                    activePeersAtomic = activePeersAtomic
                                )
                            }
                        )
                    }

                    // Launch BEP-19 HTTP WebSeed workers if torrent has url-list / ws endpoints
                    if (metadata.webSeeds.isNotEmpty()) {
                        repeat(minOf(2, metadata.webSeeds.size)) { seedIdx ->
                            val seedUrl = metadata.webSeeds[seedIdx]
                            workerJobs.add(
                                async(Dispatchers.IO) {
                                    runWebSeedWorker(
                                        torrentId = torrentId,
                                        metadata = metadata,
                                        webSeedBaseUrl = seedUrl,
                                        saveDir = saveDir,
                                        selectedIndices = selectedIndices,
                                        pendingPieces = pendingPieces,
                                        bitSet = bitSet,
                                        bitSetLock = bitSetLock,
                                        downloadedBytesAtomic = downloadedBytesAtomic,
                                        intervalBytesAtomic = intervalBytesAtomic,
                                        completedPiecesAtomic = completedPiecesAtomic,
                                        activePeersAtomic = activePeersAtomic
                                    )
                                }
                            )
                        }
                    }

                    workerJobs.awaitAll()
                } finally {
                    reporterJob.cancel()
                }
            }

            val latestAfter = torrentDao.getById(torrentId) ?: return Result.success()
            if (latestAfter.status == TorrentStatus.PAUSED || latestAfter.status == TorrentStatus.CANCELLED) {
                return Result.success()
            }

            val encodedFinal = synchronized(bitSetLock) {
                TorrentStorageWriter.encodeBitSet(bitSet, metadata.totalPieces)
            }
            val remaining = pendingPieces.size
            if (remaining == 0) {
                torrentDao.updateSwarmProgress(
                    id = torrentId,
                    downloadedBytes = totalSelectedBytes,
                    speedBytesPerSec = 0L,
                    completedPieces = targetPieces.size,
                    bitfieldBase64 = encodedFinal,
                    seeders = seedersAtomic.get(),
                    leechers = leechersAtomic.get(),
                    connectedPeers = 0,
                    status = TorrentStatus.COMPLETED
                )
                Result.success()
            } else {
                // Save exact partial bitfield and retry automatically via WorkManager
                torrentDao.updateSwarmProgress(
                    id = torrentId,
                    downloadedBytes = downloadedBytesAtomic.get(),
                    speedBytesPerSec = 0L,
                    completedPieces = completedPiecesAtomic.get(),
                    bitfieldBase64 = encodedFinal,
                    seeders = seedersAtomic.get(),
                    leechers = leechersAtomic.get(),
                    connectedPeers = 0,
                    status = TorrentStatus.QUEUED
                )
                Result.retry()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: Exception) {
            torrentDao.updateStatus(
                torrentId,
                TorrentStatus.FAILED,
                err.message ?: "Torrent download error"
            )
            Result.failure()
        }
    }

    private suspend fun ensureMetadataLoaded(entity: TorrentEntity): TorrentMetadata {
        val existingPath = entity.torrentFilePath
        if (!existingPath.isNullOrBlank()) {
            val file = File(existingPath)
            if (file.exists()) {
                return TorrentParser.parseTorrentBytes(file.readBytes())
            }
        }

        torrentDao.updateStatus(entity.id, TorrentStatus.RESOLVING_METADATA, null)
        setForeground(
            createForegroundInfo(
                torrentId = entity.id,
                title = entity.name,
                text = "Fetching torrent metadata from swarm…",
                percent = -1
            )
        )

        val metadata = if (TorrentParser.isMagnetUri(entity.sourceUri)) {
            val magnet = TorrentParser.parseMagnetUri(entity.sourceUri)
            resolveMagnetMetadataFromSwarm(magnet, trackerClient)
                ?: throw IOException("No peers responded with ut_metadata yet; will retry")
        } else {
            val req = Request.Builder()
                .url(entity.sourceUri)
                .header("User-Agent", "PureSnap/1.0")
                .build()
            val bytes = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} fetching .torrent")
                resp.body?.bytes() ?: throw IOException("Empty .torrent file")
            }
            TorrentParser.parseTorrentBytes(bytes)
        }

        val torrentFile = File(entity.saveDirPath, "${metadata.infoHashHex}.torrent").apply {
            parentFile?.mkdirs()
        }
        val syntheticBytes = TorrentStorageWriter.buildSyntheticTorrentBytes(metadata)
        if (syntheticBytes != null) {
            torrentFile.writeBytes(syntheticBytes)
        }

        val selectedCsv = entity.selectedFileIndicesCsv.ifBlank {
            metadata.files.joinToString(",") { it.index.toString() }
        }
        val selectedBytes = TorrentStorageWriter.calculateSelectedBytes(
            metadata,
            selectedCsv.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        )

        torrentDao.updateResolvedMetadata(
            id = entity.id,
            name = metadata.name,
            torrentFilePath = torrentFile.absolutePath,
            totalBytes = selectedBytes,
            totalPieces = metadata.totalPieces,
            serializedFiles = TorrentEntity.serializeFiles(metadata.files),
            selectedFileIndicesCsv = selectedCsv
        )

        return metadata
    }

    private suspend fun runPeerWorker(
        torrentId: Long,
        metadata: TorrentMetadata,
        peerId: ByteArray,
        saveDir: File,
        selectedIndices: Set<Int>,
        peerQueue: ConcurrentLinkedDeque<PeerAddress>,
        pendingPieces: ConcurrentLinkedDeque<Int>,
        bitSet: BitSet,
        bitSetLock: Any,
        downloadedBytesAtomic: AtomicLong,
        intervalBytesAtomic: AtomicLong,
        completedPiecesAtomic: AtomicInteger,
        activePeersAtomic: AtomicInteger
    ) {
        while (!isStopped && pendingPieces.isNotEmpty()) {
            val currentEntity = torrentDao.getById(torrentId) ?: return
            if (currentEntity.status == TorrentStatus.PAUSED ||
                currentEntity.status == TorrentStatus.CANCELLED
            ) {
                return
            }

            val peer = peerQueue.pollFirst() ?: return
            val session = PeerSession(peer, metadata.infoHashBytes, peerId)
            try {
                if (!session.connectAndHandshake(timeoutMs = 5000)) {
                    continue
                }
                activePeersAtomic.incrementAndGet()
                session.sendInterested()
                session.readKeepAliveOrHandshakeState(timeoutMs = 1500)

                var consecutiveFailures = 0
                while (!isStopped && consecutiveFailures < 3) {
                    val stateCheck = torrentDao.getById(torrentId) ?: break
                    if (stateCheck.status == TorrentStatus.PAUSED ||
                        stateCheck.status == TorrentStatus.CANCELLED
                    ) {
                        break
                    }

                    val pieceIndex = pendingPieces.pollFirst() ?: break
                    val alreadyDone = synchronized(bitSetLock) { bitSet.get(pieceIndex) }
                    if (alreadyDone) continue

                    val expectedLen = metadata.pieceSize(pieceIndex).toInt()
                    val pieceBytes = session.downloadPiece(
                        pieceIndex = pieceIndex,
                        pieceLength = expectedLen,
                        isCancelled = { isStopped }
                    )

                    if (pieceBytes == null) {
                        pendingPieces.addLast(pieceIndex)
                        consecutiveFailures++
                        continue
                    }

                    // Verify piece SHA-1 hash against .torrent metadata
                    val actualHash = TorrentParser.sha1(pieceBytes)
                    if (!actualHash.contentEquals(metadata.pieceHashes[pieceIndex])) {
                        pendingPieces.addLast(pieceIndex)
                        consecutiveFailures++
                        continue
                    }

                    consecutiveFailures = 0
                    TorrentStorageWriter.writePieceToFiles(
                        saveDir = saveDir,
                        metadata = metadata,
                        pieceIndex = pieceIndex,
                        pieceData = pieceBytes,
                        selectedFileIndices = selectedIndices
                    )

                    synchronized(bitSetLock) {
                        if (!bitSet.get(pieceIndex)) {
                            bitSet.set(pieceIndex)
                            completedPiecesAtomic.incrementAndGet()
                            downloadedBytesAtomic.addAndGet(pieceBytes.size.toLong())
                            intervalBytesAtomic.addAndGet(pieceBytes.size.toLong())
                        }
                    }
                }
            } finally {
                if (activePeersAtomic.get() > 0) activePeersAtomic.decrementAndGet()
                session.close()
            }
        }
    }

    /**
     * BEP-19 GetRight-style HTTP/HTTPS WebSeed worker: downloads byte ranges directly from
     * HTTP seeds included in `.torrent` `url-list` or magnet `ws=` parameters (e.g. Internet Archive).
     */
    private suspend fun runWebSeedWorker(
        torrentId: Long,
        metadata: TorrentMetadata,
        webSeedBaseUrl: String,
        saveDir: File,
        selectedIndices: Set<Int>,
        pendingPieces: ConcurrentLinkedDeque<Int>,
        bitSet: BitSet,
        bitSetLock: Any,
        downloadedBytesAtomic: AtomicLong,
        intervalBytesAtomic: AtomicLong,
        completedPiecesAtomic: AtomicInteger,
        activePeersAtomic: AtomicInteger
    ) {
        activePeersAtomic.incrementAndGet()
        try {
            while (!isStopped && pendingPieces.isNotEmpty()) {
                val latest = torrentDao.getById(torrentId) ?: return
                if (latest.status == TorrentStatus.PAUSED || latest.status == TorrentStatus.CANCELLED) {
                    return
                }

                val pieceIndex = pendingPieces.pollFirst() ?: return
                val alreadyDone = synchronized(bitSetLock) { bitSet.get(pieceIndex) }
                if (alreadyDone) continue

                val pieceLen = metadata.pieceSize(pieceIndex)
                val pieceBytes = runCatching {
                    fetchPieceFromWebSeed(metadata, webSeedBaseUrl, pieceIndex, pieceLen)
                }.getOrNull()

                if (pieceBytes == null || !TorrentParser.sha1(pieceBytes).contentEquals(metadata.pieceHashes[pieceIndex])) {
                    pendingPieces.addLast(pieceIndex)
                    delay(400L)
                    continue
                }

                TorrentStorageWriter.writePieceToFiles(
                    saveDir = saveDir,
                    metadata = metadata,
                    pieceIndex = pieceIndex,
                    pieceData = pieceBytes,
                    selectedFileIndices = selectedIndices
                )

                synchronized(bitSetLock) {
                    if (!bitSet.get(pieceIndex)) {
                        bitSet.set(pieceIndex)
                        completedPiecesAtomic.incrementAndGet()
                        downloadedBytesAtomic.addAndGet(pieceBytes.size.toLong())
                        intervalBytesAtomic.addAndGet(pieceBytes.size.toLong())
                    }
                }
            }
        } finally {
            activePeersAtomic.decrementAndGet()
        }
    }

    private fun fetchPieceFromWebSeed(
        metadata: TorrentMetadata,
        webSeedBaseUrl: String,
        pieceIndex: Int,
        pieceLength: Long
    ): ByteArray? {
        val pieceStart = pieceIndex.toLong() * metadata.pieceLength
        val pieceEnd = pieceStart + pieceLength
        val out = ByteArrayOutputStream(pieceLength.toInt())

        if (metadata.files.size == 1 && !webSeedBaseUrl.endsWith("/")) {
            val req = Request.Builder()
                .url(webSeedBaseUrl)
                .header("Range", "bytes=$pieceStart-${pieceEnd - 1L}")
                .header("User-Agent", "PureSnap/1.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return null
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.size.toLong() != pieceLength) return null
                return bytes
            }
        }

        val normalizedBase = if (webSeedBaseUrl.endsWith("/")) webSeedBaseUrl else "$webSeedBaseUrl/"
        for (fileEntry in metadata.files) {
            val fileStart = fileEntry.offsetInTorrent
            val fileEnd = fileStart + fileEntry.length
            val overlapStart = maxOf(pieceStart, fileStart)
            val overlapEnd = minOf(pieceEnd, fileEnd)
            if (overlapStart >= overlapEnd) continue

            val relUrl = if (metadata.files.size > 1) {
                "${normalizedBase}${metadata.name}/${fileEntry.relativePath}"
            } else {
                "${normalizedBase}${fileEntry.relativePath}"
            }
            val rangeStart = overlapStart - fileStart
            val rangeEnd = overlapEnd - fileStart - 1L

            val req = Request.Builder()
                .url(relUrl)
                .header("Range", "bytes=$rangeStart-$rangeEnd")
                .header("User-Agent", "PureSnap/1.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return null
                val bytes = resp.body?.bytes() ?: return null
                out.write(bytes)
            }
        }

        val result = out.toByteArray()
        return if (result.size.toLong() == pieceLength) result else null
    }

    private fun createForegroundInfo(
        torrentId: Long,
        title: String,
        text: String,
        percent: Int
    ): ForegroundInfo {
        ensureNotificationChannel()
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notificationId = (NOTIFICATION_ID_BASE + (torrentId % 10000L)).toInt()

        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply {
                if (percent in 0..100) {
                    setProgress(100, percent, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Pause / Stop", cancelIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "PureSnap Torrents",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows background BitTorrent swarm progress"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val kb = bytesPerSec / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) String.format(Locale.US, "%.1f MB/s", mb) else String.format(Locale.US, "%.0f KB/s", kb)
    }

    companion object {
        const val KEY_TORRENT_ID = "key_torrent_id"
        private const val CHANNEL_ID = "puresnap_torrents_channel"
        private const val NOTIFICATION_ID_BASE = 5100
    }
}
