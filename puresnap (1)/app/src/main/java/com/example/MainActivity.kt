package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.ConversionStatus
import com.example.data.DownloadStatus
import com.example.data.TorrentStatus
import com.example.ui.BatchAudioConverterSheet
import com.example.ui.BatchUrlQueueSheet
import com.example.ui.BrowserScreen
import com.example.ui.DownloadsScreen
import com.example.ui.ExtractionUiState
import com.example.ui.FormatSelectionSheet
import com.example.ui.HomeScreen
import com.example.ui.PlayerScreen
import com.example.ui.PureSnapViewModel
import com.example.ui.ShieldScreen
import com.example.ui.TorrentInspectorState
import com.example.ui.TorrentSetupSheet
import com.example.ui.TorrentsScreen
import com.example.ui.theme.PureSnapTheme

object PureSnapRoutes {
    const val HOME = "home"
    const val BROWSER = "browser"
    const val TORRENTS = "torrents"
    const val VAULT = "vault"
    const val SHIELD = "shield"
}

private data class NavTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val PureSnapTabs = listOf(
    NavTab(
        route = PureSnapRoutes.HOME,
        label = "Extract",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    ),
    NavTab(
        route = PureSnapRoutes.BROWSER,
        label = "Browser",
        selectedIcon = Icons.Filled.Public,
        unselectedIcon = Icons.Outlined.Public
    ),
    NavTab(
        route = PureSnapRoutes.TORRENTS,
        label = "Torrents",
        selectedIcon = Icons.Filled.Hub,
        unselectedIcon = Icons.Outlined.Hub
    ),
    NavTab(
        route = PureSnapRoutes.VAULT,
        label = "Vault",
        selectedIcon = Icons.Filled.DownloadForOffline,
        unselectedIcon = Icons.Outlined.DownloadForOffline
    ),
    NavTab(
        route = PureSnapRoutes.SHIELD,
        label = "Shield",
        selectedIcon = Icons.Filled.Security,
        unselectedIcon = Icons.Outlined.Security
    )
)

class MainActivity : ComponentActivity() {

    private val viewModel: PureSnapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val privacySettings by viewModel.privacySettings.collectAsStateWithLifecycle()
            PureSnapTheme(darkTheme = privacySettings.darkTheme) {
                PureSnapApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> if (intent.type == "text/plain") {
                viewModel.handleSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))
            }
            Intent.ACTION_VIEW -> {
                val dataUri = intent.data?.toString()
                if (!dataUri.isNullOrBlank()) {
                    viewModel.handleSharedText(dataUri)
                }
            }
        }
    }
}

@Composable
fun PureSnapApp(viewModel: PureSnapViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val urlInput by viewModel.urlInput.collectAsStateWithLifecycle()
    val clipboardUrl by viewModel.clipboardUrl.collectAsStateWithLifecycle()
    val extractionState by viewModel.extractionState.collectAsStateWithLifecycle()
    val privacySettings by viewModel.privacySettings.collectAsStateWithLifecycle()
    val allDownloads by viewModel.allDownloads.collectAsStateWithLifecycle()
    val filteredDownloads by viewModel.filteredDownloads.collectAsStateWithLifecycle()
    val vaultFilter by viewModel.vaultFilter.collectAsStateWithLifecycle()
    val browserState by viewModel.browserState.collectAsStateWithLifecycle()
    val playerTarget by viewModel.playerTarget.collectAsStateWithLifecycle()
    val playerQueue by viewModel.playerQueue.collectAsStateWithLifecycle()
    val playerQueueIndex by viewModel.playerQueueIndex.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val extractingAudioId by viewModel.extractingAudioId.collectAsStateWithLifecycle()
    val allTorrents by viewModel.allTorrents.collectAsStateWithLifecycle()
    val torrentInput by viewModel.torrentInput.collectAsStateWithLifecycle()
    val torrentInspectorState by viewModel.torrentInspectorState.collectAsStateWithLifecycle()
    val speedTestState by viewModel.speedTestState.collectAsStateWithLifecycle()
    val overloadTestState by viewModel.overloadTestState.collectAsStateWithLifecycle()
    val allConversions by viewModel.allConversions.collectAsStateWithLifecycle()
    val stagedConverterVideos by viewModel.stagedConverterVideos.collectAsStateWithLifecycle()
    val showBatchConverterSheet by viewModel.showBatchConverterSheet.collectAsStateWithLifecycle()
    val showBatchUrlSheet by viewModel.showBatchUrlSheet.collectAsStateWithLifecycle()
    val isBatchUrlBusy by viewModel.isBatchUrlBusy.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Background download continues regardless of notification grant */ }
    )
    val openTorrentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.inspectLocalTorrentUri(uri)
            }
        }
    )
    val openBatchVideosLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                viewModel.addPickedLocalVideos(uris)
            }
        }
    )
    val openLocalMediaPlayerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                viewModel.openLocalMediaFilesInPlayer(uris, appendToQueue = false)
            }
        }
    )

    val activeDownloadCount = remember(allDownloads, allConversions) {
        allDownloads.count {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
        } + allConversions.count {
            it.status == ConversionStatus.QUEUED || it.status == ConversionStatus.EXTRACTING
        }
    }
    val activeTorrentCount = remember(allTorrents) {
        allTorrents.count {
            it.status == TorrentStatus.DOWNLOADING ||
                it.status == TorrentStatus.QUEUED ||
                it.status == TorrentStatus.RESOLVING_METADATA
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkClipboard()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(statusBanner) {
        val message = statusBanner ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearStatusBanner()
    }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    if (playerTarget != null && playerQueue.isNotEmpty()) {
        PlayerScreen(
            queue = playerQueue,
            currentIndex = playerQueueIndex,
            onSelectQueueIndex = viewModel::selectPlayerQueueIndex,
            onRemoveQueueIndex = viewModel::removePlayerQueueIndex,
            onAddLocalMedia = { uris -> viewModel.openLocalMediaFilesInPlayer(uris, appendToQueue = true) },
            onAttachSubtitle = viewModel::attachExternalSubtitle,
            onClose = { viewModel.closePlayer() }
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        PureSnapTabs.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(tab.route) },
                                icon = {
                                    val badgeCount = when (tab.route) {
                                        PureSnapRoutes.VAULT -> activeDownloadCount
                                        PureSnapRoutes.TORRENTS -> activeTorrentCount
                                        else -> 0
                                    }
                                    if (badgeCount > 0) {
                                        BadgedBox(badge = { Badge { Text("$badgeCount") } }) {
                                            Icon(
                                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                contentDescription = tab.label
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    }
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("nav_${tab.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        PureSnapTabs.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(tab.route) },
                                icon = {
                                    val badgeCount = when (tab.route) {
                                        PureSnapRoutes.VAULT -> activeDownloadCount
                                        PureSnapRoutes.TORRENTS -> activeTorrentCount
                                        else -> 0
                                    }
                                    if (badgeCount > 0) {
                                        BadgedBox(badge = { Badge { Text("$badgeCount") } }) {
                                            Icon(
                                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                contentDescription = tab.label
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    }
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("rail_${tab.route}")
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = PureSnapRoutes.HOME,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(PureSnapRoutes.HOME) {
                        HomeScreen(
                            urlInput = urlInput,
                            clipboardUrl = clipboardUrl,
                            extractionState = extractionState,
                            privacySettings = privacySettings,
                            recentDownloads = allDownloads,
                            onUrlInputChange = viewModel::updateUrlInput,
                            onPasteClick = {
                                if (!viewModel.pasteFromClipboard()) {
                                    viewModel.showStatus("Clipboard is empty")
                                }
                            },
                            onInspectClick = { viewModel.inspectUrl() },
                            onUseClipboardUrl = viewModel::useClipboardUrlAndInspect,
                            onDismissClipboard = viewModel::dismissClipboardBanner,
                            onOpenPortalInBrowser = { targetUrl ->
                                viewModel.navigateBrowserTo(targetUrl)
                                navigateToTab(PureSnapRoutes.BROWSER)
                            },
                            onOpenShieldTab = { navigateToTab(PureSnapRoutes.SHIELD) },
                            onOpenVaultTab = { navigateToTab(PureSnapRoutes.VAULT) },
                            onPlayDownload = viewModel::openPlayerForDownload,
                            onOpenBatchUrls = viewModel::openBatchUrlSheet,
                            onOpenBatchAudioConverter = viewModel::openBatchConverterSheet,
                            onOpenLocalMediaPlayer = {
                                openLocalMediaPlayerLauncher.launch(
                                    arrayOf("video/*", "audio/*", "application/x-matroska", "application/ogg")
                                )
                            }
                        )
                    }

                    composable(PureSnapRoutes.BROWSER) {
                        BrowserScreen(
                            browserState = browserState,
                            privacySettings = privacySettings,
                            onNavigate = viewModel::navigateBrowserTo,
                            onPageStarted = viewModel::onBrowserPageStarted,
                            onPageFinished = viewModel::onBrowserPageFinished,
                            onTrackerBlocked = viewModel::recordBlockedTracker,
                            onStreamDetected = viewModel::recordBrowserDetectedStream,
                            onTorrentLinkDetected = { torrentUrl ->
                                viewModel.updateTorrentInput(torrentUrl)
                                viewModel.inspectTorrentInput(torrentUrl)
                            },
                            onOpenDetectedStreams = viewModel::openBrowserStreamsSheet,
                            onClearSessionNotice = viewModel::showStatus
                        )
                    }

                    composable(PureSnapRoutes.TORRENTS) {
                        TorrentsScreen(
                            torrents = allTorrents,
                            magnetInput = torrentInput,
                            inspectorState = torrentInspectorState,
                            onMagnetInputChange = viewModel::updateTorrentInput,
                            onPasteMagnet = {
                                if (!viewModel.pasteTorrentFromClipboard()) {
                                    viewModel.showStatus("Clipboard is empty")
                                }
                            },
                            onInspectMagnetOrUrl = { viewModel.inspectTorrentInput() },
                            onPickTorrentFile = {
                                openTorrentLauncher.launch(arrayOf("application/x-bittorrent", "*/*"))
                            },
                            onOpenArchiveTorrentsInBrowser = {
                                viewModel.navigateBrowserTo("https://archive.org/details/movies")
                                navigateToTab(PureSnapRoutes.BROWSER)
                            },
                            onPauseTorrent = viewModel::pauseTorrent,
                            onResumeTorrent = viewModel::resumeTorrent,
                            onToggleSequential = viewModel::toggleTorrentSequential,
                            onDeleteTorrent = viewModel::deleteTorrent,
                            onPlayTorrentFile = viewModel::openPlayerForTorrentFile
                        )
                    }

                    composable(PureSnapRoutes.VAULT) {
                        DownloadsScreen(
                            items = filteredDownloads,
                            conversionItems = allConversions,
                            allCount = allDownloads.size + allConversions.size,
                            selectedFilter = vaultFilter,
                            vaultBytes = viewModel.calculateVaultBytes(allDownloads),
                            extractingAudioId = extractingAudioId,
                            onSelectFilter = viewModel::setVaultFilter,
                            onPlay = viewModel::openPlayerForDownload,
                            onCancel = viewModel::cancelDownload,
                            onRetry = viewModel::retryDownload,
                            onExtractAudio = viewModel::extractAudioFromVideo,
                            onExportToDevice = viewModel::exportToDeviceDownloads,
                            onShare = { entity ->
                                val shareIntent = viewModel.createShareIntent(entity)
                                if (shareIntent != null) {
                                    context.startActivity(Intent.createChooser(shareIntent, "Share ${entity.title}"))
                                }
                            },
                            onDelete = viewModel::deleteDownload,
                            onClearFailed = viewModel::clearFailedDownloads,
                            onGoToExtract = { navigateToTab(PureSnapRoutes.HOME) },
                            onOpenBatchAudioConverter = viewModel::openBatchConverterSheet,
                            onOpenLocalMediaPlayer = {
                                openLocalMediaPlayerLauncher.launch(
                                    arrayOf("video/*", "audio/*", "application/x-matroska", "application/ogg")
                                )
                            },
                            onPlayConversion = viewModel::openPlayerForConversion,
                            onCancelConversion = viewModel::cancelConversion,
                            onRetryConversion = viewModel::retryConversion,
                            onDeleteConversion = viewModel::deleteConversion
                        )
                    }

                    composable(PureSnapRoutes.SHIELD) {
                        ShieldScreen(
                            settings = privacySettings,
                            recentBlockedEvents = browserState.recentBlockedEvents,
                            speedTestState = speedTestState,
                            overloadTestState = overloadTestState,
                            onToggleTrackerBlocker = viewModel::setTrackerBlocker,
                            onToggleUrlSanitizer = viewModel::setUrlSanitizer,
                            onTogglePrivateBrowser = viewModel::setPrivateBrowserMode,
                            onToggleWifiOnly = viewModel::setWifiOnlyDownloads,
                            onToggleDarkTheme = viewModel::setDarkTheme,
                            onResetCounters = viewModel::resetShieldCounters,
                            onRunSpeedTest = viewModel::runSpeedTest,
                            onRunOverloadTest = viewModel::runOverloadTest
                        )
                    }
                }
            }
        }
    }

    // Format & Quality Selection Bottom Sheet
    val successExtraction = extractionState as? ExtractionUiState.Success
    if (successExtraction != null) {
        FormatSelectionSheet(
            result = successExtraction.result,
            onDismiss = viewModel::dismissExtractionSheet,
            onDownloadVariant = { chosenTitle, variant ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                viewModel.startDownload(
                    title = chosenTitle,
                    sourceUrl = successExtraction.result.cleanUrl,
                    variant = variant,
                    thumbnailUrl = successExtraction.result.thumbnailUrl
                )
            },
            onBatchDownloadVariants = { chosenTitle, variants, autoExtractFormat ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                viewModel.startBatchDownloads(
                    title = chosenTitle,
                    sourceUrl = successExtraction.result.cleanUrl,
                    variants = variants,
                    thumbnailUrl = successExtraction.result.thumbnailUrl,
                    autoExtractFormat = autoExtractFormat
                )
                navigateToTab(PureSnapRoutes.VAULT)
            },
            onPreviewVariant = { chosenTitle, variant ->
                viewModel.openPlayerForPreview(chosenTitle, variant)
            }
        )
    }

    if (showBatchUrlSheet) {
        BatchUrlQueueSheet(
            isBusy = isBatchUrlBusy,
            onDismiss = viewModel::dismissBatchUrlSheet,
            onQueueBatchUrls = { urls, mode, bitrateKbps ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                viewModel.queueBatchUrls(urls, mode, bitrateKbps)
                navigateToTab(PureSnapRoutes.VAULT)
            }
        )
    }

    if (showBatchConverterSheet) {
        BatchAudioConverterSheet(
            stagedInputs = stagedConverterVideos,
            vaultVideos = allDownloads,
            onDismiss = viewModel::dismissBatchConverterSheet,
            onPickLocalVideos = {
                openBatchVideosLauncher.launch(arrayOf("video/*"))
            },
            onToggleVaultVideo = viewModel::toggleVaultVideoForConversion,
            onRemoveStaged = viewModel::removeStagedVideo,
            onStartBatchConversion = { format, bitrateKbps ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                viewModel.startBatchAudioConversion(format, bitrateKbps)
                navigateToTab(PureSnapRoutes.VAULT)
            }
        )
    }

    // Torrent File Selector & Swarm Setup Bottom Sheet
    val readyTorrent = torrentInspectorState as? TorrentInspectorState.Ready
    if (readyTorrent != null) {
        TorrentSetupSheet(
            preview = readyTorrent.preview,
            onDismiss = viewModel::dismissTorrentSheet,
            onStartParsedTorrent = { metadata, rawBytes, sourceUri, selectedIndices, sequentialMode ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                viewModel.startParsedTorrent(
                    metadata = metadata,
                    rawTorrentBytes = rawBytes,
                    sourceUri = sourceUri,
                    selectedIndices = selectedIndices,
                    sequentialMode = sequentialMode
                )
                navigateToTab(PureSnapRoutes.TORRENTS)
            },
            onStartMagnetOnly = { magnet, sequentialMode ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                viewModel.startMagnetOnly(magnet, sequentialMode)
                navigateToTab(PureSnapRoutes.TORRENTS)
            }
        )
    }
}
