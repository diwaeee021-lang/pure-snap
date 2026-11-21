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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.AudioOutputFormat
import com.example.data.DownloadEntity
import com.example.data.DownloadStatus
import com.example.data.MediaType
import com.example.network.ExtractionResult
import com.example.network.MediaStreamVariant

@Composable
fun PrivacyShieldPill(
    trackersBlocked: Long,
    shieldActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("privacy_shield_pill"),
        color = if (shieldActive) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = "Privacy shield status",
                tint = if (shieldActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (shieldActive) "0 Trackers • $trackersBlocked Blocked" else "Shield Paused",
                style = MaterialTheme.typography.labelMedium,
                color = if (shieldActive) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSelectionSheet(
    result: ExtractionResult,
    onDismiss: () -> Unit,
    onDownloadVariant: (title: String, variant: MediaStreamVariant) -> Unit,
    onBatchDownloadVariants: (title: String, variants: List<MediaStreamVariant>, autoExtractFormat: AudioOutputFormat?) -> Unit,
    onPreviewVariant: (title: String, variant: MediaStreamVariant) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editableTitle by remember(result.title) { mutableStateOf(result.title) }
    var selectedVariantIds by remember(result.variants) {
        mutableStateOf(
            result.variants.firstOrNull()?.let { setOf(it.id) } ?: emptySet()
        )
    }
    var autoExtractFormat by remember { mutableStateOf<AudioOutputFormat?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("format_selection_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!result.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = result.thumbnailUrl,
                        contentDescription = "Media thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 88.dp, height = 56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VideoFile,
                            contentDescription = "Media preview",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Detected Streams (${result.variants.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = result.cleanUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close sheet")
                }
            }

            if (result.strippedParams.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = "Sanitized URL",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Stripped ${result.strippedParams.size} tracker param(s): ${result.strippedParams.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            OutlinedTextField(
                value = editableTitle,
                onValueChange = { editableTitle = it },
                label = { Text("Save as filename") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("filename_input"),
                shape = RoundedCornerShape(14.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Choose Format & Batch Select",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (result.variants.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { selectedVariantIds = result.variants.map { it.id }.toSet() },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Select All")
                        }
                        if (selectedVariantIds.isNotEmpty()) {
                            TextButton(
                                onClick = { selectedVariantIds = emptySet() },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }

            // Optional auto-extract audio to MP3 / AAC after video download completes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Auto-Extract:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilterChip(
                    selected = autoExtractFormat == null,
                    onClick = { autoExtractFormat = null },
                    label = { Text("None") },
                    modifier = Modifier.height(48.dp)
                )
                FilterChip(
                    selected = autoExtractFormat == AudioOutputFormat.MP3,
                    onClick = { autoExtractFormat = AudioOutputFormat.MP3 },
                    label = { Text("+ MP3") },
                    modifier = Modifier.height(48.dp)
                )
                FilterChip(
                    selected = autoExtractFormat == AudioOutputFormat.AAC,
                    onClick = { autoExtractFormat = AudioOutputFormat.AAC },
                    label = { Text("+ AAC") },
                    modifier = Modifier.height(48.dp)
                )
            }

            result.variants.forEachIndexed { index, variant ->
                val isAudio = variant.mediaType == MediaType.AUDIO
                val isChecked = variant.id in selectedVariantIds
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = if (isChecked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(16.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                selectedVariantIds = if (checked) {
                                    selectedVariantIds + variant.id
                                } else {
                                    selectedVariantIds - variant.id
                                }
                            },
                            modifier = Modifier.testTag("select_variant_$index")
                        )
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isAudio) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.primaryContainer
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isAudio) Icons.Filled.AudioFile else Icons.Filled.VideoFile,
                                contentDescription = variant.mediaType.name,
                                tint = if (isAudio) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = variant.formatLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isAudio) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = variant.qualityLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = if (variant.estimatedBytes > 0L) {
                                    FormatUtils.formatBytes(variant.estimatedBytes)
                                } else if (variant.isHls) {
                                    "HLS Adaptive Segments"
                                } else {
                                    variant.mimeType
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        FilledTonalIconButton(
                            onClick = {
                                onPreviewVariant(editableTitle.ifBlank { result.title }, variant)
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("preview_variant_$index")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Stream preview"
                            )
                        }

                        Button(
                            onClick = {
                                onDownloadVariant(editableTitle.ifBlank { result.title }, variant)
                            },
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("download_variant_$index")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save")
                        }
                    }
                }
            }

            if (selectedVariantIds.isNotEmpty()) {
                val chosenVariants = result.variants.filter { it.id in selectedVariantIds }
                Button(
                    onClick = {
                        onBatchDownloadVariants(
                            editableTitle.ifBlank { result.title },
                            chosenVariants,
                            autoExtractFormat
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("batch_download_selected_button")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    val suffix = autoExtractFormat?.let { " + ${it.label}" } ?: ""
                    Text("Batch Queue ${chosenVariants.size} Stream(s)$suffix")
                }
            }
        }
    }
}

@Composable
fun MediaVaultCard(
    item: DownloadEntity,
    isExtractingAudio: Boolean,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onExtractAudio: () -> Unit,
    onExportToDevice: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAudio = item.mediaType == MediaType.AUDIO

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("vault_item_${item.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
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
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isAudio) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        )
                        .clickable(enabled = item.status == DownloadStatus.COMPLETED, onClick = onPlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item.status == DownloadStatus.COMPLETED) {
                            Icons.Filled.PlayArrow
                        } else if (isAudio) {
                            Icons.Filled.MusicNote
                        } else {
                            Icons.Filled.VideoFile
                        },
                        contentDescription = "Media item icon",
                        tint = if (isAudio) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = item.formatLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isAudio) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = when (item.status) {
                                DownloadStatus.COMPLETED -> FormatUtils.formatBytes(item.downloadedBytes)
                                DownloadStatus.DOWNLOADING -> {
                                    val total = if (item.totalBytes > 0L) " / ${FormatUtils.formatBytes(item.totalBytes)}" else ""
                                    val speed = FormatUtils.formatSpeed(item.speedBytesPerSec)
                                    val threads = if (item.threadCount > 1) " • ${item.threadCount}x threads" else ""
                                    "${FormatUtils.formatBytes(item.downloadedBytes)}$total$threads${if (speed.isNotBlank()) " • $speed" else ""}"
                                }
                                DownloadStatus.QUEUED -> if (item.downloadedBytes > 0L) {
                                    "Waiting to resume (${FormatUtils.formatBytes(item.downloadedBytes)})…"
                                } else {
                                    "Queued (Background Worker)…"
                                }
                                DownloadStatus.FAILED -> item.errorMessage ?: "Download failed"
                                DownloadStatus.CANCELLED -> "Cancelled"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.status == DownloadStatus.FAILED) {
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
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("cancel_download_${item.id}")
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel download")
                        }
                    }
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                        IconButton(
                            onClick = onRetry,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("retry_download_${item.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Retry download",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("delete_download_${item.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Delete item"
                            )
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        FilledTonalButton(
                            onClick = onPlay,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("play_download_${item.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play")
                        }
                    }
                }
            }

            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED) {
                if (item.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { item.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            if (item.status == DownloadStatus.COMPLETED) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.mediaType == MediaType.VIDEO && !item.formatLabel.equals("TS", true)) {
                        AssistChip(
                            onClick = onExtractAudio,
                            enabled = !isExtractingAudio,
                            label = {
                                Text(
                                    text = if (isExtractingAudio) "Extracting…" else "To M4A Audio",
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                if (isExtractingAudio) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.MusicNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("extract_audio_${item.id}")
                        )
                    }

                    AssistChip(
                        onClick = onExportToDevice,
                        label = {
                            Text(if (item.exportedToDevice) "In Downloads" else "Save Device")
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (item.exportedToDevice) Icons.Filled.CheckCircle else Icons.Filled.FolderCopy,
                                contentDescription = null,
                                tint = if (item.exportedToDevice) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("export_device_${item.id}")
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(
                        onClick = onShare,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("share_download_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Share media"
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("delete_completed_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "Delete media",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
