package com.example.speeddown.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speeddown.DownloadViewModel
import com.example.speeddown.data.DownloadSettings

private val Purple = Color(0xFF7C3AED)
private val Blue = Color(0xFF2563EB)
private val Green = Color(0xFF16A34A)
private val Amber = Color(0xFFF59E0B)
private val Red = Color(0xFFDC2626)
private val Gray = Color(0xFF6B7280)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: DownloadViewModel,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val isNothingInstalled = remember { viewModel.isNothingPlayerInstalled() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Downloads"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Settings",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "SpeedDown Engine & Preferences",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ─── Hero Banner ────────────────────────────────────────────────
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            brush = Brush.horizontalGradient(listOf(Purple.copy(0.6f), Blue.copy(0.6f))),
                            shape = RoundedCornerShape(16.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Purple, Blue))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Bolt, null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                "SpeedDown Engine 2.0",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Multi-thread chunk acceleration, zero-latency resume & direct player pipeline",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            // ─── Section: Nothing Player Integration ─────────────────────────
            item {
                SettingsSectionCard(
                    title = "Nothing Player Integration",
                    icon = Icons.Filled.PlayCircle,
                    iconTint = if (isNothingInstalled) Green else Amber
                ) {
                    // Status Badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Nothing Player Status",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                if (isNothingInstalled) "Package com.nothing.player detected"
                                else "Not installed on this device",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isNothingInstalled) Green.copy(alpha = 0.15f) else Amber.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isNothingInstalled) Green.copy(alpha = 0.5f) else Amber.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isNothingInstalled) Green else Amber)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (isNothingInstalled) "Connected" else "Missing",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isNothingInstalled) Green else Amber
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(10.dp))

                    // Toggle: Prefer Nothing Player
                    SettingsSwitchRow(
                        title = "Auto-Play with Nothing Player",
                        subtitle = "Directly route completed Video and Music downloads to Nothing Player with dual VLC/ExoPlayer HW decoding, PiP & audio boost",
                        icon = Icons.Filled.PlayArrow,
                        checked = settings.preferNothingPlayer,
                        enabled = isNothingInstalled,
                        onCheckedChange = { viewModel.updateSettings(settings.copy(preferNothingPlayer = it)) }
                    )

                    // Test launch action
                    if (isNothingInstalled) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                val ok = viewModel.launchNothingPlayer()
                                if (!ok) {
                                    Toast.makeText(context, "Could not launch Nothing Player", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Test Launch Nothing Player App")
                        }
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "💡 Tip: Install Nothing Player (com.nothing.player) on your device to enjoy seamless 1-tap playback, PiP floating mode, and equalizer controls.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ─── Section: Download Engine & Speed Limiter ────────────────────
            item {
                SettingsSectionCard(
                    title = "Download Engine & Speed",
                    icon = Icons.Filled.Speed,
                    iconTint = Purple
                ) {
                    // Max Concurrent Downloads
                    Text(
                        "Max Concurrent Downloads",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        "Number of downloads downloading at the same time. Excess files stay queued and start automatically.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 2, 3, 5, 0).forEach { limit ->
                            val label = if (limit == 0) "Unlimited" else "$limit"
                            val isSel = settings.maxConcurrent == limit
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSel) Purple else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clickable { viewModel.updateSettings(settings.copy(maxConcurrent = limit)) }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(14.dp))

                    // Live Speed Limiter
                    val currentLimit = settings.speedLimitKbps
                    val limitLabel = when {
                        currentLimit <= 0L -> "Unlimited"
                        currentLimit < 1024L -> "$currentLimit KB/s"
                        else -> "${currentLimit / 1024L} MB/s"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                "Live Speed Limiter",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                "Cap total bandwidth across all downloading threads",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Purple.copy(alpha = 0.15f)
                        ) {
                            Text(
                                limitLabel,
                                color = Purple,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Preset Chips
                    val speedPresets = listOf(
                        0L to "Unlimited",
                        512L to "512K",
                        1024L to "1M",
                        2048L to "2M",
                        5120L to "5M",
                        10240L to "10M"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        speedPresets.forEach { (kbps, name) ->
                            val isSel = settings.speedLimitKbps == kbps
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Purple else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clickable { viewModel.updateSettings(settings.copy(speedLimitKbps = kbps)) }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        name,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(14.dp))

                    // Default Connection Threads
                    Text(
                        "Default Threads per Download",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        "Simultaneous HTTP chunk streams for accelerated download speeds",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    val threadOptions = listOf(1, 2, 4, 8, 16, 32)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        threadOptions.forEach { threads ->
                            val isSel = settings.defaultThreads == threads
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Purple else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clickable { viewModel.updateSettings(settings.copy(defaultThreads = threads)) }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "${threads}T",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ─── Section: Network & Storage ──────────────────────────────────
            item {
                SettingsSectionCard(
                    title = "Network & Storage",
                    icon = Icons.Filled.Wifi,
                    iconTint = Blue
                ) {
                    // Wi-Fi Only Switch
                    SettingsSwitchRow(
                        title = "Download over Wi-Fi Only",
                        subtitle = "Automatically pause or queue downloads on mobile cellular data",
                        icon = Icons.Filled.Wifi,
                        checked = settings.wifiOnly,
                        onCheckedChange = { viewModel.updateSettings(settings.copy(wifiOnly = it)) }
                    )

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(10.dp))

                    // Auto Categorize Switch
                    SettingsSwitchRow(
                        title = "Auto-Categorize Folders",
                        subtitle = "Sort files cleanly into SpeedDown/Videos, Music, Archives, Documents",
                        icon = Icons.Filled.Folder,
                        checked = settings.autoCategorize,
                        onCheckedChange = { viewModel.updateSettings(settings.copy(autoCategorize = it)) }
                    )

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(10.dp))

                    // Storage Path Display
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Storage,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Download Location",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text(
                                "/storage/emulated/0/Download/SpeedDown/",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ─── Section: Notifications & Alerts ─────────────────────────────
            item {
                SettingsSectionCard(
                    title = "Notifications & Alerts",
                    icon = Icons.Filled.Notifications,
                    iconTint = Amber
                ) {
                    SettingsSwitchRow(
                        title = "Vibrate on Complete",
                        subtitle = "Deliver a crisp haptic feedback pulse when any download completes",
                        icon = Icons.Filled.Vibration,
                        checked = settings.vibrateOnComplete,
                        onCheckedChange = { viewModel.updateSettings(settings.copy(vibrateOnComplete = it)) }
                    )

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(10.dp))

                    SettingsSwitchRow(
                        title = "Sound Notification",
                        subtitle = "Play a high-priority system chime on download finish",
                        icon = Icons.Filled.Notifications,
                        checked = settings.soundOnComplete,
                        onCheckedChange = { viewModel.updateSettings(settings.copy(soundOnComplete = it)) }
                    )
                }
            }

            // ─── Section: About & System ─────────────────────────────────────
            item {
                SettingsSectionCard(
                    title = "About SpeedDown",
                    icon = Icons.Filled.Info,
                    iconTint = Gray
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Application Version", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("v1.0.0 (Release)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Engine Pipeline", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("OkHttp Multi-Thread + Part File Assembly", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Media Player Ecosystem", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (isNothingInstalled) "Nothing Player (Active)" else "System Default Intent",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isNothingInstalled) Green else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ─── Helper UI Components ───────────────────────────────────────────────────

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                icon,
                null,
                tint = if (enabled) Purple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier
                    .size(18.dp)
                    .padding(top = 2.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    lineHeight = 15.sp
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}
