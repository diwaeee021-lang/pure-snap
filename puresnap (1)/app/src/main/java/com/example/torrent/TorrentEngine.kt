package com.example.torrent

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.TorrentDao
import com.example.data.TorrentEntity
import com.example.data.TorrentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface TorrentPreviewResult {
    data class ParsedTorrent(
        val metadata: TorrentMetadata,
        val rawTorrentBytes: ByteArray,
        val sourceUri: String
    ) : TorrentPreviewResult

    data class ParsedMagnet(
        val magnet: MagnetLink,
        val resolvedMetadata: TorrentMetadata?
    ) : TorrentPreviewResult
}

class TorrentEngine(
    private val context: Context,
    private val torrentDao: TorrentDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    private val trackerClient = TrackerClient()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val torrentsFlow: Flow<List<TorrentEntity>> = torrentDao.observeAllTorrents()

    fun getTorrentsRootDirectory(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(base, "PureSnap/Torrents").apply { if (!exists()) mkdirs() }
    }

    suspend fun inspectMagnetOrTorrent(input: String): Result<TorrentPreviewResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = input.trim()
                if (TorrentParser.isMagnetUri(trimmed)) {
                    val magnet = TorrentParser.parseMagnetUri(trimmed)
                    val resolved = runCatching {
                        resolveMagnetMetadataFromSwarm(magnet, trackerClient)
                    }.getOrNull()
                    TorrentPreviewResult.ParsedMagnet(magnet = magnet, resolvedMetadata = resolved)
                } else {
                    val req = Request.Builder()
                        .url(trimmed)
                        .header("User-Agent", "PureSnap/1.0")
                        .build()
                    val bytes = httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} loading .torrent")
                        resp.body?.bytes() ?: throw IllegalStateException("Empty .torrent response")
                    }
                    val metadata = TorrentParser.parseTorrentBytes(bytes)
                    TorrentPreviewResult.ParsedTorrent(
                        metadata = metadata,
                        rawTorrentBytes = bytes,
                        sourceUri = trimmed
                    )
                }
            }
        }

    suspend fun inspectLocalTorrentUri(uri: Uri): Result<TorrentPreviewResult.ParsedTorrent> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Unable to read selected .torrent file")
                val metadata = TorrentParser.parseTorrentBytes(bytes)
                TorrentPreviewResult.ParsedTorrent(
                    metadata = metadata,
                    rawTorrentBytes = bytes,
                    sourceUri = uri.toString()
                )
            }
        }

    fun startParsedTorrent(
        metadata: TorrentMetadata,
        rawTorrentBytes: ByteArray?,
        sourceUri: String,
        selectedFileIndices: Set<Int>,
        sequentialMode: Boolean,
        wifiOnly: Boolean
    ) {
        scope.launch {
            val existing = torrentDao.getByInfoHash(metadata.infoHashHex)
            if (existing != null) {
                torrentDao.updateSequentialMode(existing.id, sequentialMode)
                resumeTorrent(existing, wifiOnly = wifiOnly)
                return@launch
            }

            val saveDir = File(getTorrentsRootDirectory(), sanitizeName(metadata.name)).apply {
                mkdirs()
            }
            val torrentFile = File(saveDir, "${metadata.infoHashHex}.torrent")
            val bytesToWrite = rawTorrentBytes ?: TorrentStorageWriter.buildSyntheticTorrentBytes(metadata)
            if (bytesToWrite != null) {
                torrentFile.writeBytes(bytesToWrite)
            }

            val selectedSet = if (selectedFileIndices.isEmpty()) {
                metadata.files.map { it.index }.toSet()
            } else {
                selectedFileIndices
            }
            val totalSelected = TorrentStorageWriter.calculateSelectedBytes(metadata, selectedSet)

            val entity = TorrentEntity(
                infoHashHex = metadata.infoHashHex,
                name = metadata.name,
                sourceUri = sourceUri,
                torrentFilePath = torrentFile.absolutePath,
                saveDirPath = saveDir.absolutePath,
                totalBytes = totalSelected,
                totalPieces = metadata.totalPieces,
                selectedFileIndicesCsv = selectedSet.sorted().joinToString(","),
                serializedFiles = TorrentEntity.serializeFiles(metadata.files),
                sequentialMode = sequentialMode,
                status = TorrentStatus.QUEUED
            )

            val id = torrentDao.insert(entity)
            enqueueTorrentWork(id, wifiOnly = wifiOnly, policy = ExistingWorkPolicy.REPLACE)
        }
    }

    fun startMagnet(
        magnet: MagnetLink,
        sequentialMode: Boolean,
        wifiOnly: Boolean
    ) {
        scope.launch {
            val existing = torrentDao.getByInfoHash(magnet.infoHashHex)
            if (existing != null) {
                resumeTorrent(existing, wifiOnly = wifiOnly)
                return@launch
            }

            val saveDir = File(getTorrentsRootDirectory(), sanitizeName(magnet.displayName)).apply {
                mkdirs()
            }
            val entity = TorrentEntity(
                infoHashHex = magnet.infoHashHex,
                name = magnet.displayName,
                sourceUri = magnet.rawUri,
                torrentFilePath = null,
                saveDirPath = saveDir.absolutePath,
                totalBytes = magnet.exactLength.coerceAtLeast(0L),
                sequentialMode = sequentialMode,
                status = TorrentStatus.QUEUED
            )
            val id = torrentDao.insert(entity)
            enqueueTorrentWork(id, wifiOnly = wifiOnly, policy = ExistingWorkPolicy.REPLACE)
        }
    }

    fun pauseTorrent(id: Long) {
        scope.launch {
            torrentDao.updateStatus(id, TorrentStatus.PAUSED, null)
            workManager.cancelUniqueWork(uniqueWorkName(id))
        }
    }

    fun resumeTorrent(entity: TorrentEntity, wifiOnly: Boolean) {
        scope.launch {
            torrentDao.updateStatus(entity.id, TorrentStatus.QUEUED, null)
            enqueueTorrentWork(entity.id, wifiOnly = wifiOnly, policy = ExistingWorkPolicy.REPLACE)
        }
    }

    fun toggleSequentialMode(entity: TorrentEntity) {
        scope.launch {
            torrentDao.updateSequentialMode(entity.id, !entity.sequentialMode)
        }
    }

    fun deleteTorrent(entity: TorrentEntity, deleteFiles: Boolean = true) {
        scope.launch {
            torrentDao.updateStatus(entity.id, TorrentStatus.CANCELLED, null)
            workManager.cancelUniqueWork(uniqueWorkName(entity.id))
            if (deleteFiles) {
                runCatching { File(entity.saveDirPath).deleteRecursively() }
            }
            torrentDao.deleteById(entity.id)
        }
    }

    fun resumePendingTorrentsIfNeeded(wifiOnly: Boolean) {
        scope.launch {
            val incomplete = torrentDao.getIncompleteTorrents()
            for (entity in incomplete) {
                val infos = runCatching {
                    workManager.getWorkInfosForUniqueWork(uniqueWorkName(entity.id)).get()
                }.getOrDefault(emptyList())
                val active = infos.any {
                    it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED
                }
                if (!active) {
                    enqueueTorrentWork(entity.id, wifiOnly = wifiOnly, policy = ExistingWorkPolicy.KEEP)
                }
            }
        }
    }

    private suspend fun enqueueTorrentWork(
        torrentId: Long,
        wifiOnly: Boolean,
        policy: ExistingWorkPolicy
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<TorrentDownloadWorker>()
            .setInputData(workDataOf(TorrentDownloadWorker.KEY_TORRENT_ID to torrentId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG_TORRENT_WORK)
            .build()

        torrentDao.updateWorkId(torrentId, request.id.toString())
        workManager.enqueueUniqueWork(uniqueWorkName(torrentId), policy, request)
    }

    private fun sanitizeName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]+"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(60)
            .ifBlank { "torrent" }
    }

    companion object {
        const val TAG_TORRENT_WORK = "puresnap_torrent_download"
        fun uniqueWorkName(torrentId: Long): String = "puresnap_torrent_$torrentId"
    }
}
