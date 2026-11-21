package com.example.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.AudioConversionEntity
import com.example.data.AudioOutputFormat
import com.example.data.ConversionStatus
import com.example.data.DownloadEntity
import com.example.data.DownloadStatus
import com.example.data.MediaType
import com.example.data.MediaVaultRepository
import com.example.data.TorrentEntity
import com.example.network.BatchConversionEngine
import com.example.network.ExtractionResult
import com.example.network.MediaDownloadEngine
import com.example.network.MediaExtractor
import com.example.network.MediaStreamVariant
import com.example.network.NetworkDiagnosticsEngine
import com.example.network.OverloadTestProgress
import com.example.network.OverloadTestResult
import com.example.network.SpeedTestProgress
import com.example.network.SpeedTestResult
import com.example.network.StagedVideoInput
import com.example.privacy.BlockedRequestEvent
import com.example.privacy.PrivacyPreferences
import com.example.privacy.PrivacySettings
import com.example.privacy.PrivacyShield
import com.example.player.SupportedFormatCatalog
import com.example.torrent.MagnetLink
import com.example.torrent.TorrentEngine
import com.example.torrent.TorrentFileEntry
import com.example.torrent.TorrentMetadata
import com.example.torrent.TorrentParser
import com.example.torrent.TorrentStorageWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

sealed interface ExtractionUiState {
    data object Idle : ExtractionUiState
    data object Loading : ExtractionUiState
    data class Success(val result: ExtractionResult) : ExtractionUiState
    data class Error(val message: String) : ExtractionUiState
}

sealed interface SpeedTestUiState {
    data object Idle : SpeedTestUiState
    data class Running(val progress: SpeedTestProgress) : SpeedTestUiState
    data class Completed(val result: SpeedTestResult) : SpeedTestUiState
    data class Error(val message: String) : SpeedTestUiState
}

sealed interface OverloadTestUiState {
    data object Idle : OverloadTestUiState
    data class Running(val progress: OverloadTestProgress) : OverloadTestUiState
    data class Completed(val result: OverloadTestResult) : OverloadTestUiState
    data class Error(val message: String) : OverloadTestUiState
}

enum class VaultFilter(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    CONVERSIONS("MP3 / AAC")
}

data class PlayerTarget(
    val title: String,
    val mediaUri: String,
    val isAudioOnly: Boolean,
    val subtitle: String,
    val mimeType: String? = null,
    val subtitleUris: List<String> = emptyList()
)

data class BrowserState(
    val currentUrl: String = "https://archive.org/details/movies",
    val pageTitle: String = "Privacy Browser",
    val isLoading: Boolean = false,
    val pageBlockedCount: Int = 0,
    val recentBlockedEvents: List<BlockedRequestEvent> = emptyList(),
    val detectedStreams: List<MediaStreamVariant> = emptyList()
)

class PureSnapViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val repository = MediaVaultRepository(database.downloadDao())
    private val preferences = PrivacyPreferences(application)
    private val extractor = MediaExtractor()
    private val downloadEngine = MediaDownloadEngine(application, repository)
    private val torrentEngine = TorrentEngine(application, database.torrentDao())
    private val batchConversionEngine = BatchConversionEngine(application, database.conversionDao())

    val privacySettings: StateFlow<PrivacySettings> = preferences.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PrivacySettings()
        )

    private val _vaultFilter = MutableStateFlow(VaultFilter.ALL)
    val vaultFilter: StateFlow<VaultFilter> = _vaultFilter.asStateFlow()

    val allDownloads: StateFlow<List<DownloadEntity>> = repository.downloads
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val filteredDownloads: StateFlow<List<DownloadEntity>> = combine(
        allDownloads,
        _vaultFilter
    ) { items, filter ->
        when (filter) {
            VaultFilter.ALL -> items
            VaultFilter.ACTIVE -> items.filter {
                it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
            }
            VaultFilter.VIDEO -> items.filter { it.mediaType == MediaType.VIDEO }
            VaultFilter.AUDIO -> items.filter { it.mediaType == MediaType.AUDIO }
            VaultFilter.CONVERSIONS -> emptyList()
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _clipboardUrl = MutableStateFlow<String?>(null)
    val clipboardUrl: StateFlow<String?> = _clipboardUrl.asStateFlow()
    private var dismissedClipboardUrl: String? = null

    private val _extractionState = MutableStateFlow<ExtractionUiState>(ExtractionUiState.Idle)
    val extractionState: StateFlow<ExtractionUiState> = _extractionState.asStateFlow()

    private val _browserState = MutableStateFlow(BrowserState())
    val browserState: StateFlow<BrowserState> = _browserState.asStateFlow()

    private val _playerTarget = MutableStateFlow<PlayerTarget?>(null)
    val playerTarget: StateFlow<PlayerTarget?> = _playerTarget.asStateFlow()

    private val _playerQueue = MutableStateFlow<List<PlayerTarget>>(emptyList())
    val playerQueue: StateFlow<List<PlayerTarget>> = _playerQueue.asStateFlow()

    private val _playerQueueIndex = MutableStateFlow(0)
    val playerQueueIndex: StateFlow<Int> = _playerQueueIndex.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _extractingAudioId = MutableStateFlow<Long?>(null)
    val extractingAudioId: StateFlow<Long?> = _extractingAudioId.asStateFlow()

    val allTorrents: StateFlow<List<TorrentEntity>> = torrentEngine.torrentsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _torrentInput = MutableStateFlow("")
    val torrentInput: StateFlow<String> = _torrentInput.asStateFlow()

    private val _torrentInspectorState = MutableStateFlow<TorrentInspectorState>(TorrentInspectorState.Idle)
    val torrentInspectorState: StateFlow<TorrentInspectorState> = _torrentInspectorState.asStateFlow()

    private val _speedTestState = MutableStateFlow<SpeedTestUiState>(SpeedTestUiState.Idle)
    val speedTestState: StateFlow<SpeedTestUiState> = _speedTestState.asStateFlow()

    private val _overloadTestState = MutableStateFlow<OverloadTestUiState>(OverloadTestUiState.Idle)
    val overloadTestState: StateFlow<OverloadTestUiState> = _overloadTestState.asStateFlow()

    private var speedTestJob: Job? = null
    private var overloadTestJob: Job? = null

    val allConversions: StateFlow<List<AudioConversionEntity>> = batchConversionEngine.conversionsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _stagedConverterVideos = MutableStateFlow<List<StagedVideoInput>>(emptyList())
    val stagedConverterVideos: StateFlow<List<StagedVideoInput>> = _stagedConverterVideos.asStateFlow()

    private val _showBatchConverterSheet = MutableStateFlow(false)
    val showBatchConverterSheet: StateFlow<Boolean> = _showBatchConverterSheet.asStateFlow()

    private val _showBatchUrlSheet = MutableStateFlow(false)
    val showBatchUrlSheet: StateFlow<Boolean> = _showBatchUrlSheet.asStateFlow()

    private val _isBatchUrlBusy = MutableStateFlow(false)
    val isBatchUrlBusy: StateFlow<Boolean> = _isBatchUrlBusy.asStateFlow()

    init {
        viewModelScope.launch {
            val wifiOnly = privacySettings.value.wifiOnlyDownloads
            downloadEngine.resumePendingDownloadsIfNeeded(wifiOnly = wifiOnly)
            torrentEngine.resumePendingTorrentsIfNeeded(wifiOnly = wifiOnly)
            batchConversionEngine.resumePendingConversionsIfNeeded()
        }
    }

    fun updateUrlInput(value: String) {
        _urlInput.value = value
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun showStatus(message: String) {
        _statusBanner.value = message
    }

    fun checkClipboard() {
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: return
        val extracted = extractHttpUrl(text)
        if (extracted != null && extracted != dismissedClipboardUrl && extracted != _urlInput.value) {
            _clipboardUrl.value = extracted
        }
    }

    fun pasteFromClipboard(): Boolean {
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: return false
        val extracted = extractHttpUrl(text) ?: text
        _urlInput.value = extracted
        _clipboardUrl.value = null
        return true
    }

    fun useClipboardUrlAndInspect() {
        val url = _clipboardUrl.value ?: return
        _urlInput.value = url
        _clipboardUrl.value = null
        inspectUrl(url)
    }

    fun dismissClipboardBanner() {
        dismissedClipboardUrl = _clipboardUrl.value
        _clipboardUrl.value = null
    }

    fun handleSharedText(sharedText: String?) {
        if (sharedText.isNullOrBlank()) return
        val extracted = extractActionableUri(sharedText) ?: sharedText.trim()
        if (TorrentParser.isMagnetUri(extracted) || TorrentParser.isTorrentUrl(extracted)) {
            _torrentInput.value = extracted
            inspectTorrentInput(extracted)
        } else {
            _urlInput.value = extracted
            inspectUrl(extracted)
        }
    }

    fun inspectUrl(rawUrl: String = _urlInput.value) {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            _extractionState.value = ExtractionUiState.Error("Paste a media or webpage link first.")
            return
        }
        if (TorrentParser.isMagnetUri(trimmed) || TorrentParser.isTorrentUrl(trimmed)) {
            _torrentInput.value = trimmed
            inspectTorrentInput(trimmed)
            return
        }
        viewModelScope.launch {
            _extractionState.value = ExtractionUiState.Loading
            val sanitizeEnabled = privacySettings.value.urlSanitizerEnabled
            val result = extractor.inspectUrl(trimmed, sanitizeEnabled)
            result.onSuccess { extraction ->
                if (extraction.strippedParams.isNotEmpty()) {
                    preferences.incrementParamsStripped(extraction.strippedParams.size.toLong())
                }
                _urlInput.value = extraction.cleanUrl
                _extractionState.value = ExtractionUiState.Success(extraction)
            }.onFailure { err ->
                _extractionState.value = ExtractionUiState.Error(
                    err.message ?: "Unable to extract media from this link."
                )
            }
        }
    }

    fun dismissExtractionSheet() {
        _extractionState.value = ExtractionUiState.Idle
    }

    fun startDownload(
        title: String,
        sourceUrl: String,
        variant: MediaStreamVariant,
        thumbnailUrl: String?
    ) {
        val settings = privacySettings.value
        val wifiOnly = settings.wifiOnlyDownloads
        downloadEngine.startDownload(
            title = title,
            sourceUrl = sourceUrl,
            variant = variant,
            thumbnailUrl = thumbnailUrl,
            wifiOnly = wifiOnly
        )
        _extractionState.value = ExtractionUiState.Idle
        _statusBanner.value = if (wifiOnly && !isOnWifi()) {
            "Queued \"$title\" — will auto-start in background on Wi-Fi"
        } else {
            "Started background download: \"$title\" (${variant.formatLabel})"
        }
    }

    fun startBatchDownloads(
        title: String,
        sourceUrl: String,
        variants: List<MediaStreamVariant>,
        thumbnailUrl: String?,
        autoExtractFormat: AudioOutputFormat?
    ) {
        if (variants.isEmpty()) return
        val wifiOnly = privacySettings.value.wifiOnlyDownloads
        val batchItems = variants.map { v ->
            val labelSuffix = if (variants.size > 1) " (${v.formatLabel} ${v.qualityLabel})" else ""
            MediaDownloadEngine.BatchDownloadItem(
                title = "$title$labelSuffix",
                sourceUrl = sourceUrl,
                variant = v,
                thumbnailUrl = thumbnailUrl
            )
        }
        downloadEngine.startBatchDownloads(
            items = batchItems,
            autoExtractFormat = autoExtractFormat,
            extractBitrateKbps = 192,
            wifiOnly = wifiOnly
        )
        _extractionState.value = ExtractionUiState.Idle
        val extra = autoExtractFormat?.let { " + auto-extract ${it.label}" } ?: ""
        _statusBanner.value = "Batch queued ${variants.size} stream(s)$extra via WorkManager"
    }

    fun openBatchUrlSheet() {
        _showBatchUrlSheet.value = true
    }

    fun dismissBatchUrlSheet() {
        _showBatchUrlSheet.value = false
    }

    fun queueBatchUrls(urls: List<String>, mode: BatchUrlAudioMode, bitrateKbps: Int) {
        if (urls.isEmpty() || _isBatchUrlBusy.value) return
        viewModelScope.launch {
            _isBatchUrlBusy.value = true
            val sanitizeEnabled = privacySettings.value.urlSanitizerEnabled
            val wifiOnly = privacySettings.value.wifiOnlyDownloads
            val batchItems = mutableListOf<MediaDownloadEngine.BatchDownloadItem>()

            for (rawUrl in urls) {
                val res = extractor.inspectUrl(rawUrl, sanitizeEnabled).getOrNull() ?: continue
                val chosenVariant = if (mode.preferAudioStream) {
                    res.variants.firstOrNull { it.mediaType == MediaType.AUDIO }
                        ?: res.variants.firstOrNull()
                } else {
                    res.variants.firstOrNull { it.mediaType == MediaType.VIDEO }
                        ?: res.variants.firstOrNull()
                }
                if (chosenVariant != null) {
                    batchItems.add(
                        MediaDownloadEngine.BatchDownloadItem(
                            title = res.title,
                            sourceUrl = res.cleanUrl,
                            variant = chosenVariant,
                            thumbnailUrl = res.thumbnailUrl
                        )
                    )
                }
            }

            _isBatchUrlBusy.value = false
            _showBatchUrlSheet.value = false
            if (batchItems.isEmpty()) {
                _statusBanner.value = "No downloadable streams detected in pasted links"
            } else {
                downloadEngine.startBatchDownloads(
                    items = batchItems,
                    autoExtractFormat = mode.extractFormat,
                    extractBitrateKbps = bitrateKbps,
                    wifiOnly = wifiOnly
                )
                val suffix = mode.extractFormat?.let { " + ${it.label} extract" } ?: ""
                _statusBanner.value = "Batch queued ${batchItems.size} link(s)$suffix in background"
            }
        }
    }

    fun openBatchConverterSheet() {
        _showBatchConverterSheet.value = true
    }

    fun dismissBatchConverterSheet() {
        _showBatchConverterSheet.value = false
    }

    fun addPickedLocalVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _stagedConverterVideos.update { current ->
            val existing = current.map { it.sourceUri }.toSet()
            val added = uris.mapNotNull { uri ->
                val uriStr = uri.toString()
                if (uriStr in existing) null
                else StagedVideoInput(
                    displayName = batchConversionEngine.resolveDisplayName(uri),
                    sourceUri = uriStr,
                    isVaultItem = false
                )
            }
            current + added
        }
    }

    fun toggleVaultVideoForConversion(entity: DownloadEntity) {
        _stagedConverterVideos.update { current ->
            val exists = current.any { it.sourceUri == entity.localFilePath }
            if (exists) {
                current.filterNot { it.sourceUri == entity.localFilePath }
            } else {
                current + StagedVideoInput(
                    displayName = entity.title,
                    sourceUri = entity.localFilePath,
                    isVaultItem = true
                )
            }
        }
    }

    fun removeStagedVideo(sourceUri: String) {
        _stagedConverterVideos.update { current -> current.filterNot { it.sourceUri == sourceUri } }
    }

    fun startBatchAudioConversion(format: AudioOutputFormat, bitrateKbps: Int) {
        val staged = _stagedConverterVideos.value
        if (staged.isEmpty()) return
        batchConversionEngine.enqueueBatchConversions(
            items = staged,
            targetFormat = format,
            bitrateKbps = bitrateKbps
        )
        _stagedConverterVideos.value = emptyList()
        _showBatchConverterSheet.value = false
        _statusBanner.value = "Queued ${staged.size} video(s) for ${format.label} conversion in background"
    }

    fun cancelConversion(id: Long) = batchConversionEngine.cancelConversion(id)

    fun retryConversion(entity: AudioConversionEntity) = batchConversionEngine.retryConversion(entity)

    fun deleteConversion(entity: AudioConversionEntity) = batchConversionEngine.deleteConversion(entity)

    fun openPlayerForConversion(entity: AudioConversionEntity) {
        val file = File(entity.outputFilePath)
        if (!file.exists()) {
            _statusBanner.value = "Converted audio file is missing on device"
            return
        }
        val completed = allConversions.value.filter {
            it.status == ConversionStatus.COMPLETED && File(it.outputFilePath).exists()
        }
        val queue = completed.ifEmpty { listOf(entity) }.map { item ->
            PlayerTarget(
                title = item.sourceName.substringBeforeLast('.'),
                mediaUri = File(item.outputFilePath).toURI().toString(),
                isAudioOnly = true,
                subtitle = "${item.targetFormat.label} • ${item.bitrateKbps} kbps",
                mimeType = item.targetFormat.mimeType
            )
        }
        val startIndex = queue.indexOfFirst { it.mediaUri == file.toURI().toString() }.coerceAtLeast(0)
        launchPlayerQueue(queue, startIndex)
    }

    fun cancelDownload(id: Long) {
        downloadEngine.cancelDownload(id)
    }

    fun retryDownload(entity: DownloadEntity) {
        val wifiOnly = privacySettings.value.wifiOnlyDownloads
        downloadEngine.retryDownload(entity, wifiOnly = wifiOnly)
        _statusBanner.value = if (wifiOnly && !isOnWifi()) {
            "Queued for Wi-Fi resume"
        } else {
            "Resuming \"${entity.title}\" in background"
        }
    }

    fun deleteDownload(entity: DownloadEntity) {
        viewModelScope.launch {
            if (entity.status == DownloadStatus.DOWNLOADING || entity.status == DownloadStatus.QUEUED) {
                downloadEngine.cancelDownload(entity.id)
            }
            repository.deleteDownload(entity)
            _statusBanner.value = "Removed from vault"
        }
    }

    fun clearFailedDownloads() {
        viewModelScope.launch {
            repository.clearFailed()
            _statusBanner.value = "Cleared inactive queue items"
        }
    }

    fun extractAudioFromVideo(entity: DownloadEntity) {
        if (_extractingAudioId.value != null) return
        viewModelScope.launch {
            _extractingAudioId.value = entity.id
            val result = downloadEngine.extractAudioToM4a(entity)
            _extractingAudioId.value = null
            result.onSuccess { audioItem ->
                _statusBanner.value = "Extracted audio: ${audioItem.title}"
            }.onFailure { err ->
                _statusBanner.value = err.message ?: "Audio extraction failed"
            }
        }
    }

    fun exportToDeviceDownloads(entity: DownloadEntity) {
        viewModelScope.launch {
            val result = downloadEngine.exportToPublicDownloads(entity)
            result.onSuccess { msg ->
                _statusBanner.value = msg
            }.onFailure { err ->
                _statusBanner.value = err.message ?: "Export failed"
            }
        }
    }

    fun createShareIntent(entity: DownloadEntity): Intent? {
        val file = File(entity.localFilePath)
        if (!file.exists()) {
            _statusBanner.value = "File missing from local vault"
            return null
        }
        val app = getApplication<Application>()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = entity.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun setVaultFilter(filter: VaultFilter) {
        _vaultFilter.value = filter
    }

    fun openPlayerForDownload(entity: DownloadEntity) {
        val file = File(entity.localFilePath)
        if (!file.exists()) {
            _statusBanner.value = "Media file no longer exists on device"
            return
        }
        val completed = allDownloads.value.filter {
            it.status == DownloadStatus.COMPLETED && File(it.localFilePath).exists()
        }
        val queue = completed.ifEmpty { listOf(entity) }.map { item ->
            val f = File(item.localFilePath)
            PlayerTarget(
                title = item.title,
                mediaUri = f.toURI().toString(),
                isAudioOnly = item.mediaType == MediaType.AUDIO ||
                    SupportedFormatCatalog.isAudioOnly(f.name, item.mimeType),
                subtitle = "${item.formatLabel} • ${item.qualityLabel}",
                mimeType = item.mimeType
            )
        }
        val startIndex = queue.indexOfFirst { it.mediaUri == file.toURI().toString() }.coerceAtLeast(0)
        launchPlayerQueue(queue, startIndex)
    }

    fun openPlayerForPreview(title: String, variant: MediaStreamVariant) {
        val target = PlayerTarget(
            title = title,
            mediaUri = variant.mediaUrl,
            isAudioOnly = variant.mediaType == MediaType.AUDIO ||
                SupportedFormatCatalog.isAudioOnly(variant.mediaUrl, variant.mimeType),
            subtitle = "Direct Stream • ${variant.formatLabel}",
            mimeType = variant.mimeType
        )
        launchPlayerQueue(listOf(target), 0)
    }

    fun openLocalMediaFilesInPlayer(uris: List<Uri>, appendToQueue: Boolean = false) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        val cr = app.contentResolver
        val items = uris.map { uri ->
            runCatching {
                cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val displayName = batchConversionEngine.resolveDisplayName(uri)
            val mime = cr.getType(uri)
            val isAudio = SupportedFormatCatalog.isAudioOnly(displayName, mime)
            val fmt = SupportedFormatCatalog.detectDisplayFormatLabel(displayName, mime)
            PlayerTarget(
                title = displayName.substringBeforeLast('.').ifBlank { displayName },
                mediaUri = uri.toString(),
                isAudioOnly = isAudio,
                subtitle = "Local Device • $fmt",
                mimeType = mime
            )
        }
        if (appendToQueue && _playerQueue.value.isNotEmpty()) {
            val combined = _playerQueue.value + items
            _playerQueue.value = combined
            _statusBanner.value = "Added ${items.size} item(s) to player queue"
        } else {
            launchPlayerQueue(items, 0)
        }
    }

    fun attachExternalSubtitle(uri: Uri) {
        val cr = getApplication<Application>().contentResolver
        runCatching {
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val subUriStr = uri.toString()
        val currentQueue = _playerQueue.value
        val idx = _playerQueueIndex.value
        if (idx !in currentQueue.indices) return
        val updated = currentQueue.toMutableList()
        val cur = updated[idx]
        if (subUriStr !in cur.subtitleUris) {
            val newTarget = cur.copy(subtitleUris = cur.subtitleUris + subUriStr)
            updated[idx] = newTarget
            _playerQueue.value = updated
            _playerTarget.value = newTarget
        }
        _statusBanner.value = "Loaded subtitle: ${batchConversionEngine.resolveDisplayName(uri)}"
    }

    fun selectPlayerQueueIndex(index: Int) {
        val queue = _playerQueue.value
        if (index in queue.indices) {
            _playerQueueIndex.value = index
            _playerTarget.value = queue[index]
        }
    }

    fun removePlayerQueueIndex(index: Int) {
        val queue = _playerQueue.value.toMutableList()
        if (index !in queue.indices) return
        queue.removeAt(index)
        if (queue.isEmpty()) {
            closePlayer()
            return
        }
        val currentIdx = _playerQueueIndex.value
        val nextIdx = when {
            index < currentIdx -> currentIdx - 1
            currentIdx >= queue.size -> queue.lastIndex
            else -> currentIdx
        }
        _playerQueue.value = queue
        _playerQueueIndex.value = nextIdx
        _playerTarget.value = queue[nextIdx]
    }

    private fun launchPlayerQueue(queue: List<PlayerTarget>, startIndex: Int) {
        if (queue.isEmpty()) return
        val clampedIndex = startIndex.coerceIn(0, queue.lastIndex)
        _playerQueue.value = queue
        _playerQueueIndex.value = clampedIndex
        _playerTarget.value = queue[clampedIndex]
    }

    fun closePlayer() {
        _playerTarget.value = null
        _playerQueue.value = emptyList()
        _playerQueueIndex.value = 0
    }

    // Browser session callbacks
    fun navigateBrowserTo(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        val isLikelyUrl = trimmed.startsWith("http://", true) ||
            trimmed.startsWith("https://", true) ||
            (!trimmed.contains(" ") && trimmed.contains("."))

        val targetUrl = if (isLikelyUrl) {
            val sanitize = privacySettings.value.urlSanitizerEnabled
            if (sanitize) {
                val res = PrivacyShield.sanitizeUrl(trimmed)
                if (res.strippedParams.isNotEmpty()) {
                    viewModelScope.launch {
                        preferences.incrementParamsStripped(res.strippedParams.size.toLong())
                    }
                }
                res.cleanUrl
            } else {
                if (trimmed.startsWith("http", true)) trimmed else "https://$trimmed"
            }
        } else {
            // Privacy-respecting DuckDuckGo HTML/Lite search with zero Google tracking
            "https://duckduckgo.com/?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
        }

        _browserState.update {
            it.copy(
                currentUrl = targetUrl,
                detectedStreams = emptyList(),
                pageBlockedCount = 0
            )
        }
    }

    fun onBrowserPageStarted(url: String) {
        _browserState.update {
            it.copy(
                currentUrl = url,
                isLoading = true,
                detectedStreams = emptyList(),
                pageBlockedCount = 0
            )
        }
    }

    fun onBrowserPageFinished(url: String, title: String?) {
        _browserState.update {
            it.copy(
                currentUrl = url,
                pageTitle = title?.takeIf { t -> t.isNotBlank() } ?: it.pageTitle,
                isLoading = false
            )
        }
    }

    fun recordBlockedTracker(event: BlockedRequestEvent) {
        viewModelScope.launch {
            preferences.incrementTrackersBlocked(1L)
        }
        _browserState.update { state ->
            val updatedEvents = (listOf(event) + state.recentBlockedEvents).take(30)
            state.copy(
                pageBlockedCount = state.pageBlockedCount + 1,
                recentBlockedEvents = updatedEvents
            )
        }
    }

    fun recordBrowserDetectedStream(url: String, mimeHint: String? = null) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
        if (PrivacyShield.matchBlockedHost(url) != null) return

        val (ext, mime) = PrivacyShield.inferExtensionAndMime(url, mimeHint)
        val isAudio = mime.startsWith("audio/")
        val isHls = ext == "m3u8"

        val variant = MediaStreamVariant(
            id = "sniff_${url.hashCode()}",
            mediaUrl = url,
            mediaType = if (isAudio) MediaType.AUDIO else MediaType.VIDEO,
            formatLabel = if (isHls) "HLS" else ext.uppercase(Locale.US),
            qualityLabel = if (isAudio) "Sniffed Audio" else if (isHls) "Adaptive Stream" else "Sniffed Video",
            mimeType = mime,
            isHls = isHls
        )

        _browserState.update { state ->
            if (state.detectedStreams.any { it.mediaUrl == url }) {
                state
            } else {
                state.copy(detectedStreams = listOf(variant) + state.detectedStreams)
            }
        }
    }

    fun openBrowserStreamsSheet() {
        val state = _browserState.value
        if (state.detectedStreams.isEmpty()) return
        _extractionState.value = ExtractionUiState.Success(
            ExtractionResult(
                sourceUrl = state.currentUrl,
                cleanUrl = state.currentUrl,
                strippedParams = emptyList(),
                title = state.pageTitle.ifBlank { "Captured Web Media" },
                thumbnailUrl = null,
                variants = state.detectedStreams
            )
        )
    }

    // Privacy settings toggles
    fun setTrackerBlocker(enabled: Boolean) = viewModelScope.launch {
        preferences.setTrackerBlocker(enabled)
    }

    fun setUrlSanitizer(enabled: Boolean) = viewModelScope.launch {
        preferences.setUrlSanitizer(enabled)
    }

    fun setPrivateBrowserMode(enabled: Boolean) = viewModelScope.launch {
        preferences.setPrivateBrowserMode(enabled)
    }

    fun setWifiOnlyDownloads(enabled: Boolean) = viewModelScope.launch {
        preferences.setWifiOnlyDownloads(enabled)
    }

    fun setDarkTheme(enabled: Boolean) = viewModelScope.launch {
        preferences.setDarkTheme(enabled)
    }

    fun resetShieldCounters() = viewModelScope.launch {
        preferences.resetCounters()
        _browserState.update { it.copy(recentBlockedEvents = emptyList(), pageBlockedCount = 0) }
        _statusBanner.value = "Shield counters reset"
    }

    fun runSpeedTest() {
        speedTestJob?.cancel()
        speedTestJob = viewModelScope.launch {
            _speedTestState.value = SpeedTestUiState.Running(
                SpeedTestProgress("Initializing probes…", 0.0, 0.05f)
            )
            val result = NetworkDiagnosticsEngine.runNetworkSpeedTest { progress ->
                _speedTestState.value = SpeedTestUiState.Running(progress)
            }
            result.onSuccess { res ->
                _speedTestState.value = SpeedTestUiState.Completed(res)
            }.onFailure { err ->
                _speedTestState.value = SpeedTestUiState.Error(
                    err.message ?: "Network speed test failed. Check connectivity."
                )
            }
        }
    }

    fun runOverloadTest() {
        overloadTestJob?.cancel()
        overloadTestJob = viewModelScope.launch {
            _overloadTestState.value = OverloadTestUiState.Running(
                OverloadTestProgress("Starting engine overload suite…", 0.05f)
            )
            val res = runCatching {
                NetworkDiagnosticsEngine.runOverloadStressTest(getApplication()) { progress ->
                    _overloadTestState.value = OverloadTestUiState.Running(progress)
                }
            }
            res.onSuccess { metrics ->
                _overloadTestState.value = OverloadTestUiState.Completed(metrics)
            }.onFailure { err ->
                _overloadTestState.value = OverloadTestUiState.Error(
                    err.message ?: "Overload test aborted"
                )
            }
        }
    }

    fun calculateVaultBytes(items: List<DownloadEntity>): Long {
        return items.filter { it.status == DownloadStatus.COMPLETED }
            .sumOf { File(it.localFilePath).takeIf { f -> f.exists() }?.length() ?: it.downloadedBytes }
    }

    // Torrent actions
    fun updateTorrentInput(value: String) {
        _torrentInput.value = value
    }

    fun pasteTorrentFromClipboard(): Boolean {
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: return false
        val extracted = extractActionableUri(text) ?: text
        _torrentInput.value = extracted
        return true
    }

    fun inspectTorrentInput(rawInput: String = _torrentInput.value) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            _torrentInspectorState.value = TorrentInspectorState.Error("Paste a magnet:? link or .torrent URL first.")
            return
        }
        viewModelScope.launch {
            _torrentInspectorState.value = TorrentInspectorState.Loading
            val result = torrentEngine.inspectMagnetOrTorrent(trimmed)
            result.onSuccess { preview ->
                _torrentInspectorState.value = TorrentInspectorState.Ready(preview)
            }.onFailure { err ->
                _torrentInspectorState.value = TorrentInspectorState.Error(
                    err.message ?: "Unable to parse magnet or .torrent link."
                )
            }
        }
    }

    fun inspectLocalTorrentUri(uri: Uri) {
        viewModelScope.launch {
            _torrentInspectorState.value = TorrentInspectorState.Loading
            val result = torrentEngine.inspectLocalTorrentUri(uri)
            result.onSuccess { preview ->
                _torrentInspectorState.value = TorrentInspectorState.Ready(preview)
            }.onFailure { err ->
                _torrentInspectorState.value = TorrentInspectorState.Error(
                    err.message ?: "Unable to read .torrent file."
                )
            }
        }
    }

    fun dismissTorrentSheet() {
        _torrentInspectorState.value = TorrentInspectorState.Idle
    }

    fun startParsedTorrent(
        metadata: TorrentMetadata,
        rawTorrentBytes: ByteArray?,
        sourceUri: String,
        selectedIndices: Set<Int>,
        sequentialMode: Boolean
    ) {
        val wifiOnly = privacySettings.value.wifiOnlyDownloads
        torrentEngine.startParsedTorrent(
            metadata = metadata,
            rawTorrentBytes = rawTorrentBytes,
            sourceUri = sourceUri,
            selectedFileIndices = selectedIndices,
            sequentialMode = sequentialMode,
            wifiOnly = wifiOnly
        )
        _torrentInspectorState.value = TorrentInspectorState.Idle
        _statusBanner.value = "Started torrent: ${metadata.name}"
    }

    fun startMagnetOnly(magnet: MagnetLink, sequentialMode: Boolean) {
        val wifiOnly = privacySettings.value.wifiOnlyDownloads
        torrentEngine.startMagnet(
            magnet = magnet,
            sequentialMode = sequentialMode,
            wifiOnly = wifiOnly
        )
        _torrentInspectorState.value = TorrentInspectorState.Idle
        _statusBanner.value = "Fetching magnet metadata in background: ${magnet.displayName}"
    }

    fun pauseTorrent(id: Long) = torrentEngine.pauseTorrent(id)

    fun resumeTorrent(entity: TorrentEntity) {
        torrentEngine.resumeTorrent(entity, wifiOnly = privacySettings.value.wifiOnlyDownloads)
        _statusBanner.value = "Resuming torrent: ${entity.name}"
    }

    fun toggleTorrentSequential(entity: TorrentEntity) = torrentEngine.toggleSequentialMode(entity)

    fun deleteTorrent(entity: TorrentEntity) {
        torrentEngine.deleteTorrent(entity, deleteFiles = true)
        _statusBanner.value = "Removed torrent ${entity.name}"
    }

    fun openPlayerForTorrentFile(torrent: TorrentEntity, fileEntry: TorrentFileEntry) {
        val file = TorrentStorageWriter.resolveTargetFile(File(torrent.saveDirPath), fileEntry.relativePath)
        if (!file.exists() || file.length() == 0L) {
            _statusBanner.value = "Waiting for initial pieces to be written to disk…"
            return
        }
        val playableExtensions = SupportedFormatCatalog.supportedVideoExtensions +
            SupportedFormatCatalog.supportedAudioExtensions
        val entries: List<TorrentFileEntry> = torrent.parsedFiles()
            .filter { entry ->
                val ext = SupportedFormatCatalog.extractExtension(entry.relativePath)
                val f = TorrentStorageWriter.resolveTargetFile(File(torrent.saveDirPath), entry.relativePath)
                ext in playableExtensions && f.exists() && f.length() > 0L
            }
            .ifEmpty { listOf(fileEntry) }

        val queue = entries.map { entry ->
            val targetFile = TorrentStorageWriter.resolveTargetFile(File(torrent.saveDirPath), entry.relativePath)
            val isAudio = SupportedFormatCatalog.isAudioOnly(entry.relativePath)
            val fmt = SupportedFormatCatalog.detectDisplayFormatLabel(entry.relativePath)
            PlayerTarget(
                title = entry.relativePath.substringAfterLast('/'),
                mediaUri = targetFile.toURI().toString(),
                isAudioOnly = isAudio,
                subtitle = "Torrent • $fmt • ${torrent.name}"
            )
        }
        val startIndex = queue.indexOfFirst { it.mediaUri == file.toURI().toString() }.coerceAtLeast(0)
        launchPlayerQueue(queue, startIndex)
    }

    private fun extractHttpUrl(text: String): String? = extractActionableUri(text)

    private fun extractActionableUri(text: String): String? {
        val magnetRegex = Regex("""magnet:\?[^\s<>"]+""", RegexOption.IGNORE_CASE)
        magnetRegex.find(text)?.let { return it.value }
        val httpRegex = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
        return httpRegex.find(text)?.value
    }

    private fun isOnWifi(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
