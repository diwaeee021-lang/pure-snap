package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.TorrentEntity
import com.example.data.TorrentStatus
import com.example.torrent.MagnetLink
import com.example.torrent.TorrentFileEntry
import com.example.torrent.TorrentMetadata
import com.example.torrent.TorrentPreviewResult

sealed interface TorrentInspectorState {
    data object Idle : TorrentInspectorState
    data object Loading : TorrentInspectorState
    data class Ready(val preview: TorrentPreviewResult) : TorrentInspectorState
    data class Error(val message: String) : TorrentInspectorState
}

@Composable
fun TorrentsScreen(
    torrents: List<TorrentEntity>,
    magnetInput: String,
    inspectorState: TorrentInspectorState,
    onMagnetInputChange: (String) -> Unit,
    onPasteMagnet: () -> Unit,
    onInspectMagnetOrUrl: () -> Unit,
    onPickTorrentFile: () -> Unit,
    onOpenArchiveTorrentsInBrowser: () -> Unit,
    onPauseTorrent: (Long) -> Unit,
    onResumeTorrent: (TorrentEntity) -> Unit,
    onToggleSequential: (TorrentEntity) -> Unit,
    onDeleteTorrent: (TorrentEntity) -> Unit,
    onPlayTorrentFile: (TorrentEntity, TorrentFileEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header & Add Torrent Card
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Hub,
                                contentDescription = "BitTorrent Engine",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "BitTorrent Swarm Engine",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "BEP-3/9/15/19 • Sequential streaming • WorkManager resume",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedTextField(
                        value = magnetInput,
                        onValueChange = onMagnetInputChange,
                        placeholder = { Text("Paste magnet:?xt=urn:btih:… or .torrent URL") },
                        leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                        trailingIcon = {
                            if (magnetInput.isNotEmpty()) {
                                IconButton(
                                    onClick = { onMagnetInputChange("") },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear torrent input")
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                keyboardController?.hide()
                                onInspectMagnetOrUrl()
                            }
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("torrent_input_field")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPasteMagnet,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("torrent_paste_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Paste")
                        }

                        OutlinedButton(
                            onClick = onPickTorrentFile,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("torrent_open_file_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FileOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(".torrent")
                        }

                        Button(
                            onClick = {
                                keyboardController?.hide()
                                onInspectMagnetOrUrl()
                            },
                            enabled = inspectorState !is TorrentInspectorState.Loading,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("torrent_inspect_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (inspectorState is TorrentInspectorState.Loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Add")
                            }
                        }
                    }

                    if (inspectorState is TorrentInspectorState.Error) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = inspectorState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }

        if (torrents.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Hub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "No active torrents",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Open a .torrent file, paste a magnet link, or tap any magnet/.torrent link in the Privacy Browser to start downloading in the background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(
                            onClick = onOpenArchiveTorrentsInBrowser,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("browse_archive_torrents_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Public,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Browse Open Archive Torrents")
                        }
                    }
                }
            }
        } else {
            items(torrents, key = { it.id }) { torrent ->
                TorrentCard(
                    torrent = torrent,
                    onPause = { onPauseTorrent(torrent.id) },
                    onResume = { onResumeTorrent(torrent) },
                    onToggleSequential = { onToggleSequential(torrent) },
                    onDelete = { onDeleteTorrent(torrent) },
                    onPlayFile = { file -> onPlayTorrentFile(torrent, file) }
                )
            }
        }
    }
}

@Composable
private fun TorrentCard(
    torrent: TorrentEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onToggleSequential: () -> Unit,
    onDelete: () -> Unit,
    onPlayFile: (TorrentFileEntry) -> Unit
) {
    var expandedFiles by remember { mutableStateOf(false) }
    val files = remember(torrent.serializedFiles) { torrent.parsedFiles() }
    val selectedSet = remember(torrent.selectedFileIndicesCsv) { torrent.selectedIndicesSet() }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("torrent_card_${torrent.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = torrent.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(
                                when (torrent.status) {
                                    TorrentStatus.RESOLVING_METADATA -> "Fetching magnet metadata…"
                                    TorrentStatus.QUEUED -> "Queued (Swarm Worker)…"
                                    TorrentStatus.DOWNLOADING -> {
                                        val spd = FormatUtils.formatSpeed(torrent.downloadSpeedBytesPerSec)
                                        "${FormatUtils.formatBytes(torrent.downloadedBytes)} / ${FormatUtils.formatBytes(torrent.totalBytes)}${if (spd.isNotBlank()) " • $spd" else ""}"
                                    }
                                    TorrentStatus.PAUSED -> "Paused • ${FormatUtils.formatBytes(torrent.downloadedBytes)} saved"
                                    TorrentStatus.COMPLETED -> "Completed • ${FormatUtils.formatBytes(torrent.totalBytes)}"
                                    TorrentStatus.FAILED -> torrent.errorMessage ?: "Failed"
                                    TorrentStatus.CANCELLED -> "Cancelled"
                                }
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (torrent.status == TorrentStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (torrent.status == TorrentStatus.DOWNLOADING ||
                    torrent.status == TorrentStatus.QUEUED ||
                    torrent.status == TorrentStatus.RESOLVING_METADATA
                ) {
                    IconButton(
                        onClick = onPause,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("pause_torrent_${torrent.id}")
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = "Pause torrent")
                    }
                } else if (torrent.status == TorrentStatus.PAUSED || torrent.status == TorrentStatus.FAILED) {
                    IconButton(
                        onClick = onResume,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("resume_torrent_${torrent.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Resume torrent",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("delete_torrent_${torrent.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Delete torrent",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (torrent.status != TorrentStatus.COMPLETED) {
                if (torrent.totalPieces > 0) {
                    LinearProgressIndicator(
                        progress = { torrent.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                } else if (torrent.status == TorrentStatus.RESOLVING_METADATA ||
                    torrent.status == TorrentStatus.DOWNLOADING
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            // Swarm Telemetry Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (torrent.totalPieces > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${torrent.completedPieces}/${torrent.totalPieces} pieces",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "${torrent.connectedPeers} peers • ${torrent.seeders}S/${torrent.leechers}L",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                AssistChip(
                    onClick = onToggleSequential,
                    label = { Text(if (torrent.sequentialMode) "Sequential" else "Swarm") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.height(48.dp)
                )
            }

            if (files.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { expandedFiles = !expandedFiles }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val activeCount = if (selectedSet.isEmpty()) files.size else selectedSet.size
                    Text(
                        text = "Files ($activeCount/${files.size} selected)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        imageVector = if (expandedFiles) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = "Toggle torrent file list"
                    )
                }

                AnimatedVisibility(visible = expandedFiles) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        files.filter { selectedSet.isEmpty() || it.index in selectedSet }.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (entry.isMediaFile) Icons.Filled.VideoFile else Icons.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (entry.isMediaFile) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.relativePath,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = FormatUtils.formatBytes(entry.length),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (entry.isMediaFile) {
                                    FilledTonalButton(
                                        onClick = { onPlayFile(entry) },
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = "Play torrent file",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Play")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentSetupSheet(
    preview: TorrentPreviewResult,
    onDismiss: () -> Unit,
    onStartParsedTorrent: (TorrentMetadata, ByteArray?, String, Set<Int>, Boolean) -> Unit,
    onStartMagnetOnly: (MagnetLink, Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val metadata = when (preview) {
        is TorrentPreviewResult.ParsedTorrent -> preview.metadata
        is TorrentPreviewResult.ParsedMagnet -> preview.resolvedMetadata
    }

    var sequentialMode by remember { mutableStateOf(true) }
    var selectedIndices by remember(metadata) {
        mutableStateOf(
            metadata?.files?.map { it.index }?.toSet() ?: emptySet()
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("torrent_setup_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = metadata?.name
                            ?: (preview as TorrentPreviewResult.ParsedMagnet).magnet.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val hash = metadata?.infoHashHex
                        ?: (preview as TorrentPreviewResult.ParsedMagnet).magnet.infoHashHex
                    Text(
                        text = "SHA-1: ${hash.take(16)}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close torrent setup")
                }
            }

            // Sequential Streaming Switch
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sequential Mode (Stream Ready)",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Downloads pieces in order so video/audio plays early",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = sequentialMode,
                        onCheckedChange = { sequentialMode = it },
                        modifier = Modifier.testTag("torrent_sequential_switch")
                    )
                }
            }

            if (metadata != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Files (${selectedIndices.size}/${metadata.files.size}) • ${metadata.trackers.size} trackers",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                val mediaOnly = metadata.files.filter { it.isMediaFile }.map { it.index }.toSet()
                                if (mediaOnly.isNotEmpty()) selectedIndices = mediaOnly
                            },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Media Only")
                        }
                        TextButton(
                            onClick = { selectedIndices = metadata.files.map { it.index }.toSet() },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("All")
                        }
                    }
                }

                metadata.files.forEach { entry ->
                    val checked = entry.index in selectedIndices
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                selectedIndices = if (checked) {
                                    selectedIndices - entry.index
                                } else {
                                    selectedIndices + entry.index
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedIndices = if (isChecked) {
                                        selectedIndices + entry.index
                                    } else {
                                        selectedIndices - entry.index
                                    }
                                }
                            )
                            Icon(
                                imageVector = when {
                                    entry.relativePath.endsWith(".mp3", true) ||
                                        entry.relativePath.endsWith(".m4a", true) -> Icons.Filled.AudioFile
                                    entry.isMediaFile -> Icons.Filled.VideoFile
                                    else -> Icons.Filled.InsertDriveFile
                                },
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (entry.isMediaFile) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.relativePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = FormatUtils.formatBytes(entry.length),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                val selectedBytes = metadata.files
                    .filter { it.index in selectedIndices }
                    .sumOf { it.length }

                Button(
                    onClick = {
                        val rawBytes = (preview as? TorrentPreviewResult.ParsedTorrent)?.rawTorrentBytes
                        val srcUri = when (preview) {
                            is TorrentPreviewResult.ParsedTorrent -> preview.sourceUri
                            is TorrentPreviewResult.ParsedMagnet -> preview.magnet.rawUri
                        }
                        onStartParsedTorrent(
                            metadata,
                            rawBytes,
                            srcUri,
                            selectedIndices,
                            sequentialMode
                        )
                    },
                    enabled = selectedIndices.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("start_torrent_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Download Selected (${FormatUtils.formatBytes(selectedBytes)})")
                }
            } else {
                val magnet = (preview as TorrentPreviewResult.ParsedMagnet).magnet
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Swarm metadata is not cached yet. Start in the background and WorkManager will resolve the file tree via BEP-9 ut_metadata from ${magnet.trackers.size} trackers.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Button(
                    onClick = { onStartMagnetOnly(magnet, sequentialMode) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("start_magnet_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Magnet in Background")
                }
            }
        }
    }
}
