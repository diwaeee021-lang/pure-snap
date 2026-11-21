package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.player.AbLoopState
import com.example.player.AudioFxState
import com.example.player.EqPreset
import com.example.player.MediaTechnicalInfo
import com.example.player.MediaTrackOption
import java.util.Locale
import kotlin.math.roundToInt

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
    fxState: AudioFxState,
    onDismiss: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectPreset: (EqPreset) -> Unit,
    onBandLevelChange: (Int, Int) -> Unit,
    onBassChange: (Int) -> Unit,
    onLoudnessGainChange: (Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("equalizer_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Equalizer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Column {
                        Text(
                            text = "Audio DSP & Equalizer",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = if (fxState.hardwareAttached) "Hardware AudioFX active" else "Software DSP profile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = fxState.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("eq_master_switch")
                )
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(EqPreset.entries) { preset ->
                    FilterChip(
                        selected = fxState.preset == preset,
                        onClick = { onSelectPreset(preset) },
                        label = { Text(preset.label) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("eq_preset_${preset.name.lowercase()}")
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = "Frequency Bands",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary
            )

            fxState.bandCenterHz.forEachIndexed { idx, freqHz ->
                val levelMb = fxState.bandLevelsMb.getOrElse(idx) { 0 }
                val dbText = String.format(Locale.US, "%+.1f dB", levelMb / 100f)
                val freqLabel = if (freqHz >= 1000) {
                    String.format(Locale.US, "%.1f kHz", freqHz / 1000f)
                } else {
                    "$freqHz Hz"
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(freqLabel, style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = dbText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = levelMb.toFloat(),
                        onValueChange = { onBandLevelChange(idx, it.roundToInt()) },
                        valueRange = fxState.minLevelMb.toFloat()..fxState.maxLevelMb.toFloat(),
                        enabled = fxState.enabled
                    )
                }
            }

            HorizontalDivider()

            // Bass Boost
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Bass Boost", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${fxState.bassStrength / 10}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Slider(
                    value = fxState.bassStrength.toFloat(),
                    onValueChange = { onBassChange(it.roundToInt()) },
                    valueRange = 0f..1000f,
                    enabled = fxState.enabled,
                    modifier = Modifier.testTag("bass_boost_slider")
                )
            }

            // Loudness Amplifier (Gain Boost up to +15dB)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Volume Gain Amplifier", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = String.format(Locale.US, "+%.1f dB", fxState.loudnessGainMb / 100f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = fxState.loudnessGainMb.toFloat(),
                    onValueChange = { onLoudnessGainChange(it.roundToInt()) },
                    valueRange = 0f..1500f,
                    enabled = fxState.enabled,
                    modifier = Modifier.testTag("loudness_gain_slider")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    onClick = { onSelectPreset(EqPreset.FLAT) },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Reset Flat")
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksAndSubtitlesSheet(
    audioTracks: List<MediaTrackOption>,
    subtitleTracks: List<MediaTrackOption>,
    subtitlesDisabled: Boolean,
    onSelectAudioTrack: (MediaTrackOption) -> Unit,
    onSelectSubtitleTrack: (MediaTrackOption) -> Unit,
    onDisableSubtitles: () -> Unit,
    onPickExternalSubtitle: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("tracks_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Audio & Subtitle Tracks", style = MaterialTheme.typography.titleLarge)

            Text(
                text = "Audio Streams",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            if (audioTracks.isEmpty()) {
                Text(
                    text = "Using default container audio stream.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                audioTracks.forEach { track ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (track.isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (track.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = track.isSupported) { onSelectAudioTrack(track) }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AudioFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.label, style = MaterialTheme.typography.titleSmall)
                                if (!track.isSupported) {
                                    Text(
                                        text = "Codec unsupported by device decoder",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            if (track.isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Selected audio track",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Subtitles / Captions (CC)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                FilledTonalButton(
                    onClick = onPickExternalSubtitle,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("load_external_subtitle_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Load .SRT / .VTT")
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (subtitlesDisabled) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onDisableSubtitles)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Filled.SubtitlesOff, contentDescription = null)
                    Text("Subtitles Off", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (subtitlesDisabled) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Subtitles off",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            subtitleTracks.forEach { sub ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (sub.isSelected && !subtitlesDisabled) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectSubtitleTrack(sub) }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Filled.ClosedCaption, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Text(sub.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (sub.isSelected && !subtitlesDisabled) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Selected subtitle",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistQueueSheet(
    queue: List<PlayerTarget>,
    currentIndex: Int,
    onSelectIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onAddMoreLocalMedia: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("playlist_queue_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Playback Queue (${queue.size})", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "Tap any video or audio track to switch immediately",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onAddMoreLocalMedia,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("queue_add_local_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Files")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(queue) { index, item ->
                    val isActive = index == currentIndex
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isActive) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                onSelectIndex(index)
                                onDismiss()
                            }
                            .testTag("queue_item_$index")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (isActive) Icons.Filled.GraphicEq
                                else if (item.isAudioOnly) Icons.Filled.MusicNote else Icons.Filled.VideoFile,
                                contentDescription = null,
                                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (queue.size > 1) {
                                IconButton(
                                    onClick = { onRemoveIndex(index) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteOutline,
                                        contentDescription = "Remove from queue",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerToolsAndInfoSheet(
    playbackSpeed: Float,
    abLoopState: AbLoopState,
    currentPositionMs: Long,
    sleepTimerRemainingMs: Long?,
    technicalInfo: MediaTechnicalInfo,
    isAudioOnlyMode: Boolean,
    onSpeedChange: (Float) -> Unit,
    onSetLoopPointA: () -> Unit,
    onSetLoopPointB: () -> Unit,
    onClearAbLoop: () -> Unit,
    onSelectSleepMinutes: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("player_tools_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Playback Engine & Media Info", style = MaterialTheme.typography.titleLarge)

            // Playback speed slider + quick presets
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Filled.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Playback Speed", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        text = String.format(Locale.US, "%.2fx", playbackSpeed),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Slider(
                    value = playbackSpeed,
                    onValueChange = { raw ->
                        val snapped = (raw * 20f).roundToInt() / 20f
                        onSpeedChange(snapped.coerceIn(0.25f, 3.0f))
                    },
                    valueRange = 0.25f..3.0f,
                    modifier = Modifier.testTag("speed_slider")
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f).forEach { spd ->
                        FilterChip(
                            selected = kotlin.math.abs(playbackSpeed - spd) < 0.03f,
                            onClick = { onSpeedChange(spd) },
                            label = { Text("${spd}x") },
                            modifier = Modifier.height(48.dp)
                        )
                    }
                }
            }

            HorizontalDivider()

            // A-B Loop
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Filled.Repeat, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Text("A-B Segment Loop", style = MaterialTheme.typography.titleSmall)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onSetLoopPointA,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("ab_set_a_button")
                    ) {
                        Text(
                            abLoopState.pointA?.let { "A: ${FormatUtils.formatDuration(it)}" }
                                ?: "Set A (${FormatUtils.formatDuration(currentPositionMs)})"
                        )
                    }
                    FilledTonalButton(
                        onClick = onSetLoopPointB,
                        enabled = abLoopState.pointA != null,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("ab_set_b_button")
                    ) {
                        Text(
                            abLoopState.pointB?.let { "B: ${FormatUtils.formatDuration(it)}" }
                                ?: "Set B"
                        )
                    }
                    OutlinedButton(
                        onClick = onClearAbLoop,
                        enabled = abLoopState.pointA != null,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("ab_clear_button")
                    ) {
                        Text("Clear")
                    }
                }
            }

            HorizontalDivider()

            // Sleep Timer
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Filled.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Sleep Timer", style = MaterialTheme.typography.titleSmall)
                    }
                    if (sleepTimerRemainingMs != null) {
                        Text(
                            text = "Stops in ${FormatUtils.formatDuration(sleepTimerRemainingMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = sleepTimerRemainingMs == null,
                        onClick = { onSelectSleepMinutes(null) },
                        label = { Text("Off") },
                        modifier = Modifier.height(48.dp)
                    )
                    listOf(15, 30, 45, 60).forEach { mins ->
                        FilterChip(
                            selected = false,
                            onClick = { onSelectSleepMinutes(mins) },
                            label = { Text("${mins}m") },
                            modifier = Modifier.height(48.dp)
                        )
                    }
                }
            }

            HorizontalDivider()

            // Technical Media Inspector Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                        Text("Media Stream Telemetry", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    TechInfoRow("Container / Protocol", technicalInfo.formatLabel)
                    if (!isAudioOnlyMode) {
                        TechInfoRow("Video Codec", technicalInfo.videoCodec)
                        TechInfoRow("Resolution & FPS", "${technicalInfo.resolution} • ${technicalInfo.frameRate}")
                    }
                    TechInfoRow("Audio Codec", technicalInfo.audioCodec)
                    TechInfoRow("Audio Output", technicalInfo.audioSpec)
                    TechInfoRow("Stream Bitrate", technicalInfo.totalBitrate)
                }
            }
        }
    }
}

@Composable
private fun TechInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
