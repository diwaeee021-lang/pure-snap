package com.example.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AudioConversionEntity
import com.example.data.AudioOutputFormat
import com.example.data.ConversionStatus
import com.example.data.DownloadEntity
import com.example.data.DownloadStatus
import com.example.data.MediaType
import com.example.network.StagedVideoInput

enum class BatchUrlAudioMode(val label: String, val extractFormat: AudioOutputFormat?, val preferAudioStream: Boolean) {
    VIDEO_ONLY("Video Only", null, false),
    VIDEO_PLUS_MP3("Video + MP3", AudioOutputFormat.MP3, false),
    VIDEO_PLUS_AAC("Video + AAC", AudioOutputFormat.AAC, false),
    AUDIO_STREAM_ONLY("Audio Stream", null, true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchAudioConverterSheet(
    stagedInputs: List<StagedVideoInput>,
    vaultVideos: List<DownloadEntity>,
    onDismiss: () -> Unit,
    onPickLocalVideos: () -> Unit,
    onToggleVaultVideo: (DownloadEntity) -> Unit,
    onRemoveStaged: (String) -> Unit,
    onStartBatchConversion: (AudioOutputFormat, Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedFormat by remember { mutableStateOf(AudioOutputFormat.MP3) }
    var selectedBitrate by remember { mutableIntStateOf(192) }
    val stagedUris = remember(stagedInputs) { stagedInputs.map { it.sourceUri }.toSet() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("batch_audio_converter_sheet")
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
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.LibraryMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Batch Video → MP3 / AAC",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Queue local or vault videos to extract audio in background via WorkManager",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close batch converter")
                }
            }

            // Pick from device storage (zero-permission SAF)
            OutlinedButton(
                onClick = onPickLocalVideos,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("pick_local_videos_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.VideoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select Multiple Videos from Device Storage")
            }

            // Format selection: MP3 or AAC
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Output Audio Format",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AudioOutputFormat.entries.forEach { format ->
                        FilterChip(
                            selected = selectedFormat == format,
                            onClick = { selectedFormat = format },
                            label = {
                                Text(
                                    if (format == AudioOutputFormat.MP3) "MP3 (audio/mpeg)" else "AAC (ADTS .aac)"
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("format_chip_${format.name.lowercase()}")
                        )
                    }
                }
            }

            // Bitrate selection
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Target Bitrate",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(128, 192, 320).forEach { kbps ->
                        FilterChip(
                            selected = selectedBitrate == kbps,
                            onClick = { selectedBitrate = kbps },
                            label = { Text("$kbps kbps") },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("bitrate_chip_$kbps")
                        )
                    }
                }
            }

            // Completed Vault videos quick toggle
            val completedVaultVideos = remember(vaultVideos) {
                vaultVideos.filter { it.mediaType == MediaType.VIDEO && it.status == DownloadStatus.COMPLETED }
            }
            if (completedVaultVideos.isNotEmpty()) {
                Text(
                    text = "Or Include Videos From Vault",
                    style = MaterialTheme.typography.titleSmall
                )
                completedVaultVideos.take(6).forEach { video ->
                    val checked = video.localFilePath in stagedUris
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onToggleVaultVideo(video) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggleVaultVideo(video) }
                            )
                            Icon(
                                imageVector = Icons.Filled.VideoFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = video.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Staged queue list
            Text(
                text = "Queued Videos (${stagedInputs.size})",
                style = MaterialTheme.typography.titleSmall
            )

            if (stagedInputs.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No videos queued yet. Tap \"Select Multiple Videos\" above or check videos from your Vault.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                stagedInputs.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VideoFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onRemoveStaged(item.sourceUri) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove video from batch"
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { onStartBatchConversion(selectedFormat, selectedBitrate) },
                enabled = stagedInputs.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("start_batch_convert_button")
            ) {
                Icon(Icons.Filled.AudioFile, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Convert ${stagedInputs.size} Video(s) to ${selectedFormat.label}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchUrlQueueSheet(
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onQueueBatchUrls: (urls: List<String>, mode: BatchUrlAudioMode, bitrateKbps: Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var multiLineText by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf(BatchUrlAudioMode.VIDEO_PLUS_MP3) }
    val parsedUrls = remember(multiLineText) {
        multiLineText.lines()
            .map { it.trim() }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            .distinct()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("batch_url_queue_sheet")
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
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Batch Link Downloader",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Paste multiple media or page URLs and optionally auto-extract MP3 or AAC",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close batch link queue")
                }
            }

            OutlinedTextField(
                value = multiLineText,
                onValueChange = { multiLineText = it },
                label = { Text("Paste URLs (one per line)") },
                placeholder = { Text("https://example.org/video1.mp4\nhttps://example.org/video2.mp4") },
                minLines = 4,
                maxLines = 8,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("batch_urls_input")
            )

            Text(
                text = "Download & Extraction Profile",
                style = MaterialTheme.typography.titleSmall
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BatchUrlAudioMode.entries.chunked(2).forEach { rowModes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowModes.forEach { mode ->
                            FilterChip(
                                selected = selectedMode == mode,
                                onClick = { selectedMode = mode },
                                label = { Text(mode.label) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("batch_mode_${mode.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { onQueueBatchUrls(parsedUrls, selectedMode, 192) },
                enabled = parsedUrls.isNotEmpty() && !isBusy,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("start_batch_url_button")
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Queue ${parsedUrls.size} Link(s) in Background")
            }
        }
    }
}

@Composable
fun AudioConversionCard(
    item: AudioConversionEntity,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("conversion_item_${item.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.AudioFile,
                        contentDescription = "Audio conversion",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.sourceName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "${item.targetFormat.label} • ${item.bitrateKbps} kbps",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = when (item.status) {
                                ConversionStatus.QUEUED -> "Queued (WorkManager)…"
                                ConversionStatus.EXTRACTING -> "Converting ${item.progressPercent}%…"
                                ConversionStatus.COMPLETED -> FormatUtils.formatBytes(item.outputBytes)
                                ConversionStatus.FAILED -> item.errorMessage ?: "Conversion failed"
                                ConversionStatus.CANCELLED -> "Cancelled"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.status == ConversionStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                when (item.status) {
                    ConversionStatus.QUEUED, ConversionStatus.EXTRACTING -> {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel conversion")
                        }
                    }
                    ConversionStatus.FAILED, ConversionStatus.CANCELLED -> {
                        IconButton(
                            onClick = onRetry,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Retry conversion",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Delete conversion"
                            )
                        }
                    }
                    ConversionStatus.COMPLETED -> {
                        FilledTonalButton(
                            onClick = onPlay,
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play")
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Remove conversion record",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            if (item.status == ConversionStatus.QUEUED || item.status == ConversionStatus.EXTRACTING) {
                LinearProgressIndicator(
                    progress = { (item.progressPercent / 100f).coerceIn(0.02f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}
