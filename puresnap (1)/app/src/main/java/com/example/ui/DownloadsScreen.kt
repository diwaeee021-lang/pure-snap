package com.example.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AudioConversionEntity
import com.example.data.DownloadEntity
import com.example.data.DownloadStatus

@Composable
fun DownloadsScreen(
    items: List<DownloadEntity>,
    conversionItems: List<AudioConversionEntity>,
    allCount: Int,
    selectedFilter: VaultFilter,
    vaultBytes: Long,
    extractingAudioId: Long?,
    onSelectFilter: (VaultFilter) -> Unit,
    onPlay: (DownloadEntity) -> Unit,
    onCancel: (Long) -> Unit,
    onRetry: (DownloadEntity) -> Unit,
    onExtractAudio: (DownloadEntity) -> Unit,
    onExportToDevice: (DownloadEntity) -> Unit,
    onShare: (DownloadEntity) -> Unit,
    onDelete: (DownloadEntity) -> Unit,
    onClearFailed: () -> Unit,
    onGoToExtract: () -> Unit,
    onOpenBatchAudioConverter: () -> Unit,
    onOpenLocalMediaPlayer: () -> Unit,
    onPlayConversion: (AudioConversionEntity) -> Unit,
    onCancelConversion: (Long) -> Unit,
    onRetryConversion: (AudioConversionEntity) -> Unit,
    onDeleteConversion: (AudioConversionEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val hasFailedOrCancelled = items.any {
        it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Vault Storage Header + Filter Chips
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Offline Media Vault",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "$allCount item(s) • ${FormatUtils.formatBytes(vaultBytes)} stored locally",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(
                            onClick = onOpenLocalMediaPlayer,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("vault_local_player_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("Play File")
                        }

                        FilledTonalButton(
                            onClick = onOpenBatchAudioConverter,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("vault_batch_convert_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AudioFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("MP3/AAC")
                        }

                        if (hasFailedOrCancelled) {
                            TextButton(
                                onClick = onClearFailed,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("clear_failed_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CleaningServices,
                                    contentDescription = "Clear failed downloads",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(VaultFilter.entries) { filter ->
                        val selected = filter == selectedFilter
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectFilter(filter) },
                            label = { Text(filter.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("filter_${filter.name.lowercase()}")
                        )
                    }
                }
            }
        }

        val showConversions = selectedFilter == VaultFilter.ALL ||
            selectedFilter == VaultFilter.ACTIVE ||
            selectedFilter == VaultFilter.CONVERSIONS
        val visibleConversions = if (selectedFilter == VaultFilter.ACTIVE) {
            conversionItems.filter {
                it.status == com.example.data.ConversionStatus.QUEUED ||
                    it.status == com.example.data.ConversionStatus.EXTRACTING
            }
        } else if (showConversions) {
            conversionItems
        } else {
            emptyList()
        }
        val visibleDownloads = if (selectedFilter == VaultFilter.CONVERSIONS) emptyList() else items

        if (visibleDownloads.isEmpty() && visibleConversions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_empty_vault),
                        contentDescription = "Empty media vault",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 240.dp, height = 180.dp)
                            .clip(RoundedCornerShape(24.dp))
                    )
                    Text(
                        text = if (selectedFilter == VaultFilter.ALL) {
                            "Your offline vault is empty"
                        } else {
                            "No ${selectedFilter.label.lowercase()} in vault"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Paste a direct link, queue batch URLs, or pick multiple local videos to convert to MP3/AAC.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onGoToExtract,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("empty_vault_extract_cta"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Extract Link")
                        }
                        FilledTonalButton(
                            onClick = onOpenBatchAudioConverter,
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Convert Videos")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (visibleConversions.isNotEmpty()) {
                    item {
                        Text(
                            text = "Background MP3 / AAC Queue (${visibleConversions.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    items(visibleConversions, key = { "conv_${it.id}" }) { conv ->
                        AudioConversionCard(
                            item = conv,
                            onPlay = { onPlayConversion(conv) },
                            onCancel = { onCancelConversion(conv.id) },
                            onRetry = { onRetryConversion(conv) },
                            onDelete = { onDeleteConversion(conv) }
                        )
                    }
                }
                items(visibleDownloads, key = { it.id }) { entity ->
                    MediaVaultCard(
                        item = entity,
                        isExtractingAudio = extractingAudioId == entity.id,
                        onPlay = { onPlay(entity) },
                        onCancel = { onCancel(entity.id) },
                        onRetry = { onRetry(entity) },
                        onExtractAudio = { onExtractAudio(entity) },
                        onExportToDevice = { onExportToDevice(entity) },
                        onShare = { onShare(entity) },
                        onDelete = { onDelete(entity) }
                    )
                }
            }
        }
    }
}
