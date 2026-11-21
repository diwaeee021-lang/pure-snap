package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.privacy.BlockedRequestEvent
import com.example.privacy.PrivacySettings
import com.example.privacy.PrivacyShield
import java.util.Locale

@Composable
fun ShieldScreen(
    settings: PrivacySettings,
    recentBlockedEvents: List<BlockedRequestEvent>,
    speedTestState: SpeedTestUiState,
    overloadTestState: OverloadTestUiState,
    onToggleTrackerBlocker: (Boolean) -> Unit,
    onToggleUrlSanitizer: (Boolean) -> Unit,
    onTogglePrivateBrowser: (Boolean) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onToggleDarkTheme: (Boolean) -> Unit,
    onResetCounters: () -> Unit,
    onRunSpeedTest: () -> Unit,
    onRunOverloadTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Privacy Audit Hero Card
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
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
                                .background(MaterialTheme.colorScheme.secondary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VerifiedUser,
                                contentDescription = "Verified tracker-free",
                                tint = MaterialTheme.colorScheme.onSecondary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "PureSnap Privacy Audit",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "0 Ad SDKs • 0 Analytics • 0 Telemetry",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AuditStatBox(
                            label = "Trackers Blocked",
                            value = settings.totalTrackersBlocked.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        AuditStatBox(
                            label = "Params Stripped",
                            value = settings.totalParamsStripped.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        AuditStatBox(
                            label = "Blocklist Hosts",
                            value = "${PrivacyShield.blockedHosts.size}+",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Protection Controls
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ShieldToggleRow(
                        icon = Icons.Filled.Block,
                        title = "Ad & Tracker Blocker",
                        subtitle = "Blocks ${PrivacyShield.blockedHosts.size}+ ad networks, pixels & telemetry in browser",
                        checked = settings.trackerBlockerEnabled,
                        onCheckedChange = onToggleTrackerBlocker,
                        testTag = "toggle_tracker_blocker"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    ShieldToggleRow(
                        icon = Icons.Filled.LinkOff,
                        title = "URL Tracking Stripper",
                        subtitle = "Removes utm_*, fbclid, gclid, igshid, and referral tokens",
                        checked = settings.urlSanitizerEnabled,
                        onCheckedChange = onToggleUrlSanitizer,
                        testTag = "toggle_url_sanitizer"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    ShieldToggleRow(
                        icon = Icons.Filled.VisibilityOff,
                        title = "Incognito Browser Mode",
                        subtitle = "Blocks third-party cookies & wipes session storage automatically",
                        checked = settings.privateBrowserMode,
                        onCheckedChange = onTogglePrivateBrowser,
                        testTag = "toggle_private_browser"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    ShieldToggleRow(
                        icon = Icons.Filled.Wifi,
                        title = "Wi-Fi Only Downloads",
                        subtitle = "Prevent large video downloads on metered cellular data",
                        checked = settings.wifiOnlyDownloads,
                        onCheckedChange = onToggleWifiOnly,
                        testTag = "toggle_wifi_only"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    ShieldToggleRow(
                        icon = Icons.Filled.DarkMode,
                        title = "Midnight Studio Dark Theme",
                        subtitle = "High-contrast obsidian palette for comfortable viewing",
                        checked = settings.darkTheme,
                        onCheckedChange = onToggleDarkTheme,
                        testTag = "toggle_dark_theme"
                    )
                }
            }
        }

        // Speed Test & Overload Stress Lab
        item {
            DiagnosticsLabCard(
                speedTestState = speedTestState,
                overloadTestState = overloadTestState,
                onRunSpeedTest = onRunSpeedTest,
                onRunOverloadTest = onRunOverloadTest
            )
        }

        // Reset Stats & Blocklist Log
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (recentBlockedEvents.isEmpty()) "Protected Categories" else "Recently Blocked in Session",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                OutlinedButton(
                    onClick = onResetCounters,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("reset_shield_counters")
                ) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Reset Stats")
                }
            }
        }

        if (recentBlockedEvents.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PrivacyTip,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "Zero Third-Party Tracking Architecture",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Text(
                            text = "Unlike typical downloaders, PureSnap bundles no ad networks, no Firebase Analytics, no crash telemetry, and no attribution SDKs. Open any site in the Browser tab to see live blocked ad/tracker requests here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(recentBlockedEvents.take(15)) { event ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            RoundedCornerShape(14.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = event.host,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = event.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditStatBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ShieldToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun DiagnosticsLabCard(
    speedTestState: SpeedTestUiState,
    overloadTestState: OverloadTestUiState,
    onRunSpeedTest: () -> Unit,
    onRunOverloadTest: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("diagnostics_lab_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = "Speed and Overload Lab",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Speed & Overload Test Lab",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Live 4-thread CDN speed test + engine saturation benchmark",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onRunSpeedTest,
                    enabled = speedTestState !is SpeedTestUiState.Running,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("run_speed_test_button")
                ) {
                    if (speedTestState is SpeedTestUiState.Running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.NetworkCheck,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Speed Test")
                }

                FilledTonalButton(
                    onClick = onRunOverloadTest,
                    enabled = overloadTestState !is OverloadTestUiState.Running,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("run_overload_test_button")
                ) {
                    if (overloadTestState is OverloadTestUiState.Running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Overload Test")
                }
            }

            when (speedTestState) {
                is SpeedTestUiState.Running -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = speedTestState.progress.phase,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (speedTestState.progress.currentMbps > 0.0) {
                                    Text(
                                        text = String.format(Locale.US, "%.1f Mbps", speedTestState.progress.currentMbps),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = { speedTestState.progress.progressFraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                is SpeedTestUiState.Completed -> {
                    val res = speedTestState.result
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("speed_test_result_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Speed Test • ${res.endpointLabel}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AuditStatBox(
                                    label = "4-Thread",
                                    value = String.format(Locale.US, "%.1f Mbps", res.multiThreadMbps),
                                    modifier = Modifier.weight(1f)
                                )
                                AuditStatBox(
                                    label = "1-Stream",
                                    value = String.format(Locale.US, "%.1f Mbps", res.singleStreamMbps),
                                    modifier = Modifier.weight(1f)
                                )
                                AuditStatBox(
                                    label = "Ping / Jitter",
                                    value = "${res.pingMs}/${res.jitterMs} ms",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                is SpeedTestUiState.Error -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = speedTestState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                SpeedTestUiState.Idle -> Unit
            }

            when (overloadTestState) {
                is OverloadTestUiState.Running -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = overloadTestState.progress.stage,
                                style = MaterialTheme.typography.labelMedium
                            )
                            LinearProgressIndicator(
                                progress = { overloadTestState.progress.progressFraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                is OverloadTestUiState.Completed -> {
                    val m = overloadTestState.result
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overload_test_result_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Overload Stress Passed (${m.totalElapsedMs} ms • ${m.peakConcurrentWorkers} workers)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AuditStatBox(
                                    label = "SHA-1 Verify",
                                    value = String.format(Locale.US, "%.0f MB/s", m.sha1ThroughputMbPerSec),
                                    modifier = Modifier.weight(1f)
                                )
                                AuditStatBox(
                                    label = "Piece Disk I/O",
                                    value = String.format(Locale.US, "%.0f MB/s", m.diskWriteMbPerSec),
                                    modifier = Modifier.weight(1f)
                                )
                                AuditStatBox(
                                    label = "Bencode/s",
                                    value = "${m.bencodeOpsPerSec}",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AuditStatBox(
                                    label = "Shield Filter/s",
                                    value = "${m.filterUrlsPerSec}",
                                    modifier = Modifier.weight(1f)
                                )
                                AuditStatBox(
                                    label = "Room DB Ops/s",
                                    value = "${m.dbConcurrencyOpsPerSec}",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                is OverloadTestUiState.Error -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = overloadTestState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                OverloadTestUiState.Idle -> Unit
            }
        }
    }
}
