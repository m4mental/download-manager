package com.example.speeddown.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speeddown.DownloadViewModel
import com.example.speeddown.data.DownloadItem
import com.example.speeddown.data.DownloadStatus
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.pow

// ─── Colors ──────────────────────────────────────────────────────────────────
private val Purple = Color(0xFF7C3AED)
private val Blue   = Color(0xFF2563EB)
private val Green  = Color(0xFF16A34A)
private val Red    = Color(0xFFDC2626)
private val Amber  = Color(0xFFF59E0B)
private val Gray   = Color(0xFF6B7280)

// ─── Tabs ────────────────────────────────────────────────────────────────────
enum class DownloadTab(val title: String) {
    ALL("All"),
    QUEUED("Queued"),
    DOWNLOADING("Downloading"),
    SAVED("Saved")
}

// ─── Main Screen ─────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(viewModel: DownloadViewModel) {
    val downloads by viewModel.downloads.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val tabs = remember { DownloadTab.values() }
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { tabs.size }
    )

    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var errorDetailItem by remember { mutableStateOf<DownloadItem?>(null) }
    var deleteTargetItem by remember { mutableStateOf<DownloadItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Purple, Blue))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Download, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("SpeedDown", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    }
                },
                actions = {
                    val active = downloads.count { it.status == DownloadStatus.DOWNLOADING }
                    if (active > 0) {
                        Badge(containerColor = Purple) { Text("$active") }
                        Spacer(Modifier.width(6.dp))
                    }
                    IconButton(onClick = { viewModel.clearCompleted() }) {
                        Icon(Icons.Filled.CleaningServices, "Clear completed")
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Filled.Settings, "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = Purple,
                contentColor = Color.White,
                icon = { Icon(Icons.Filled.Add, "Add") },
                text = { Text("New Download", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            DownloadStatsBar(downloads)

            // ─── Filter Tabs: All, Queued, Downloading, Saved (Synced with Swipe Pager) ─
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = Purple,
                divider = {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.25f))
                }
            ) {
                tabs.forEachIndexed { index, tab ->
                    val count = when (tab) {
                        DownloadTab.ALL -> downloads.size
                        DownloadTab.QUEUED -> downloads.count { it.status == DownloadStatus.QUEUED }
                        DownloadTab.DOWNLOADING -> downloads.count {
                            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PAUSED
                        }
                        DownloadTab.SAVED -> downloads.count { it.status == DownloadStatus.COMPLETED }
                    }
                    val isSelected = pagerState.currentPage == index
                    Tab(
                        selected = isSelected,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    tab.title,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = if (isSelected) Purple else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (count > 0) {
                                    Spacer(Modifier.width(4.dp))
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isSelected) Purple else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            " $count ",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // ─── Horizontal Pager for Left/Right Swiping between tabs ─────────
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                val tab = tabs[page]
                val tabDownloads = when (tab) {
                    DownloadTab.ALL -> downloads
                    DownloadTab.QUEUED -> downloads.filter { it.status == DownloadStatus.QUEUED }
                    DownloadTab.DOWNLOADING -> downloads.filter {
                        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PAUSED
                    }
                    DownloadTab.SAVED -> downloads.filter { it.status == DownloadStatus.COMPLETED }
                }

                if (tabDownloads.isEmpty()) {
                    TabEmptyState(tab)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(tabDownloads, key = { it.id }) { item ->
                            DownloadCard(
                                item = item,
                                onPause = { viewModel.pause(item) },
                                onResume = { viewModel.resume(item) },
                                onCancel = { viewModel.cancel(item) },
                                onDelete = { deleteTargetItem = item },
                                onOpen = { viewModel.open(item) },
                                onShowError = { errorDetailItem = item }
                            )
                        }
                        item { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showAddDialog) {
        AddDownloadDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { url, name, threads ->
                viewModel.addDownload(url, name, threads)
                showAddDialog = false
            }
        )
    }
    if (showSettingsDialog) SettingsDialog(onDismiss = { showSettingsDialog = false })
    errorDetailItem?.let { item ->
        ErrorDetailDialog(item = item, onDismiss = { errorDetailItem = null })
    }
    deleteTargetItem?.let { item ->
        DeleteConfirmationDialog(
            item = item,
            onDismiss = { deleteTargetItem = null },
            onDeleteAppOnly = {
                viewModel.delete(item, deleteFile = false)
                deleteTargetItem = null
            },
            onDeleteWithFile = {
                viewModel.delete(item, deleteFile = true)
                deleteTargetItem = null
            }
        )
    }
}

// ─── Stats Bar ───────────────────────────────────────────────────────────────
@Composable
private fun DownloadStatsBar(downloads: List<DownloadItem>) {
    val total       = downloads.size
    val completed   = downloads.count { it.status == DownloadStatus.COMPLETED }
    val downloading = downloads.count { it.status == DownloadStatus.DOWNLOADING }
    val failed      = downloads.count { it.status == DownloadStatus.FAILED }
    val totalSpeed  = downloads.filter { it.status == DownloadStatus.DOWNLOADING }.sumOf { it.speed }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        StatChip("Total",    "$total",      MaterialTheme.colorScheme.primary)
        StatChip("Active",   "$downloading", Blue)
        StatChip("Done",     "$completed",   Green)
        StatChip("Failed",   "$failed",      Red)
        if (totalSpeed > 0) StatChip("Speed", formatSpeed(totalSpeed), Purple)
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, color = color, fontSize = 15.sp)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    }
}

// ─── Download Card ────────────────────────────────────────────────────────────
@Composable
fun DownloadCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShowError: () -> Unit
) {
    val statusColor = statusColor(item.status)
    val isActive = item.status == DownloadStatus.DOWNLOADING
    val isPaused = item.status == DownloadStatus.PAUSED

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Header ──────────────────────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(statusColor.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(fileIcon(item.fileName), null, tint = statusColor, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusBadge(item.status, statusColor)
                        if (item.threads > 1 && (isActive || isPaused)) {
                            Surface(color = Purple.copy(0.13f), shape = RoundedCornerShape(4.dp)) {
                                Text("⚡${item.threads}T", color = Purple, fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Progress Section ─────────────────────────────────────────────
            if (isActive || isPaused) {
                // Progress bar
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(5.dp)),
                    color = statusColor,
                    trackColor = statusColor.copy(alpha = 0.15f),
                    strokeCap = StrokeCap.Round
                )
                Spacer(Modifier.height(10.dp))

                // ─ Speed + ETA banner (the important new section) ──────────
                if (isActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Purple.copy(0.10f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Speed
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Speed, null, tint = Purple, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text("Speed", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (item.speed > 0) formatSpeed(item.speed) else "Calculating…",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp,
                                    color = Purple
                                )
                            }
                        }
                        VerticalDivider(modifier = Modifier.height(32.dp), color = Purple.copy(0.3f))
                        // ETA
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Timer, null, tint = Blue, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Time Left", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (item.etaSeconds > 0) formatEta(item.etaSeconds) else "--",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp,
                                    color = Blue
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Size row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "${formatSize(item.downloadedSize)} / ${if (item.totalSize > 0) formatSize(item.totalSize) else "?"}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "${item.progressPercent}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }

            // ── Completed ────────────────────────────────────────────────────
            if (item.status == DownloadStatus.COMPLETED) {
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(5.dp)),
                    color = Green, strokeCap = StrokeCap.Round
                )
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatSize(item.downloadedSize), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("100% ✓", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Green)
                }
            }

            // ── Failed Error Box ─────────────────────────────────────────────
            if (item.status == DownloadStatus.FAILED) {
                val errorReason = item.errorMessage?.ifBlank { null } ?: "Download failed: Server returned an error or URL is unreachable"
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = Red.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ErrorOutline, null, tint = Red, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download Failed (Reason)", color = Red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            errorReason,
                            color = Red.copy(0.9f),
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap 'Details' to view complete diagnostic information",
                            color = Red.copy(0.65f),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // ── Action Buttons ───────────────────────────────────────────────
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f))
            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                when (item.status) {
                    DownloadStatus.DOWNLOADING -> {
                        ActionBtn("Pause",  Icons.Filled.Pause,  Amber, onPause)
                        Spacer(Modifier.width(8.dp))
                        ActionBtn("Cancel", Icons.Filled.Close,  Red,   onCancel)
                    }
                    DownloadStatus.PAUSED -> {
                        ActionBtn("Resume", Icons.Filled.PlayArrow, Blue, onResume)
                        Spacer(Modifier.width(8.dp))
                        ActionBtn("Cancel", Icons.Filled.Close,     Red,  onCancel)
                    }
                    DownloadStatus.COMPLETED -> {
                        ActionBtn("Open",   Icons.AutoMirrored.Filled.OpenInNew, Green, onOpen)
                        Spacer(Modifier.width(8.dp))
                        ActionBtn("Delete", Icons.Filled.Delete,                 Red,   onDelete)
                    }
                    DownloadStatus.FAILED -> {
                        ActionBtn("Details", Icons.Filled.Info,    Gray,  onShowError)
                        Spacer(Modifier.width(8.dp))
                        ActionBtn("Retry",   Icons.Filled.Refresh,  Purple, onResume)
                        Spacer(Modifier.width(8.dp))
                        ActionBtn("Delete",  Icons.Filled.Delete,   Red,   onDelete)
                    }
                    DownloadStatus.CANCELLED -> {
                        ActionBtn("Retry",  Icons.Filled.Refresh, Purple, onResume)
                        Spacer(Modifier.width(8.dp))
                        ActionBtn("Delete", Icons.Filled.Delete,  Red,   onDelete)
                    }
                    DownloadStatus.QUEUED -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp, color = Purple)
                            Spacer(Modifier.width(6.dp))
                            Text("Queued...", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.weight(1f))
                        ActionBtn("Cancel", Icons.Filled.Close, Red, onCancel)
                        Spacer(Modifier.width(8.dp))
                        ActionBtn("Delete", Icons.Filled.Delete, Red, onDelete)
                    }
                }
            }
        }
    }
}

// ─── Error Detail Dialog ──────────────────────────────────────────────────────
@Composable
fun ErrorDetailDialog(item: DownloadItem, onDismiss: () -> Unit) {
    val errorReason = item.errorMessage?.ifBlank { null } ?: "Server returned an error or URL cannot be downloaded directly"
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.ErrorOutline, null, tint = Red, modifier = Modifier.size(28.dp)) },
        title = { Text("Download Failure Reason", fontWeight = FontWeight.Bold, color = Red) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoRow("File", item.fileName)
                        InfoRow("URL",  item.url)
                        InfoRow("Size", if (item.totalSize > 0) formatSize(item.totalSize) else "Unknown")
                    }
                }
                Surface(color = Red.copy(0.08f), shape = RoundedCornerShape(8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Why it failed:", fontWeight = FontWeight.Bold, color = Red, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            errorReason,
                            color = Red.copy(0.9f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    "💡 Direct link required: Some websites (Google Drive preview, Mediafire web page, mega.nz) require opening in browser to obtain the direct raw download link.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text("$label: ", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text(value, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─── Delete Confirmation Dialog ──────────────────────────────────────────────
@Composable
fun DeleteConfirmationDialog(
    item: DownloadItem,
    onDismiss: () -> Unit,
    onDeleteAppOnly: () -> Unit,
    onDeleteWithFile: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DeleteForever, null, tint = Red, modifier = Modifier.size(32.dp)) },
        title = {
            Text("Delete Download?", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            item.fileName,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusBadge(item.status, statusColor(item.status))
                            Text(
                                if (item.totalSize > 0) formatSize(item.totalSize) else formatSize(item.downloadedSize),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    "Kya aap downloaded local file ko bhi storage se delete karna chahte hain ya sirf app ki history se hatana chahte hain?",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Option 1: Delete both file and log
                Button(
                    onClick = onDeleteWithFile,
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.DeleteForever, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete File & from App", fontWeight = FontWeight.Bold)
                }

                // Option 2: Delete from app only
                OutlinedButton(
                    onClick = onDeleteAppOnly,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Filled.PlaylistRemove, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remove from App Only", fontWeight = FontWeight.SemiBold)
                }

                // Cancel
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    )
}

// ─── Add Download Dialog ──────────────────────────────────────────────────────
@Composable
fun AddDownloadDialog(onDismiss: () -> Unit, onAdd: (url: String, name: String, threads: Int) -> Unit) {
    var url by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var threads by remember { mutableStateOf(16) }
    var urlError by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Download, null, tint = Purple)
                Spacer(Modifier.width(8.dp))
                Text("New Download", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { newUrl ->
                        url = newUrl
                        urlError = ""
                        if (fileName.isBlank()) {
                            try {
                                val clean = newUrl.trim()
                                val candidate = clean.substringAfterLast("/").substringBefore("?").substringBefore("#")
                                if (candidate.isNotBlank() && candidate.contains(".")) {
                                    fileName = java.net.URLDecoder.decode(candidate, "UTF-8")
                                }
                            } catch (_: Exception) {}
                        }
                    },
                    label = { Text("Download URL *") },
                    placeholder = { Text("https://example.com/file.zip") },
                    isError = urlError.isNotEmpty(),
                    supportingText = if (urlError.isNotEmpty()) {{ Text(urlError, color = Red) }} else null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    leadingIcon = { Icon(Icons.Filled.Link, null) },
                    trailingIcon = {
                        IconButton(onClick = {
                            val clipText = clipboardManager.getText()?.text?.trim() ?: ""
                            if (clipText.isNotBlank()) {
                                url = clipText
                                urlError = ""
                                if (fileName.isBlank()) {
                                    try {
                                        val candidate = clipText.substringAfterLast("/").substringBefore("?").substringBefore("#")
                                        if (candidate.isNotBlank() && candidate.contains(".")) {
                                            fileName = java.net.URLDecoder.decode(candidate, "UTF-8")
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }) {
                            Icon(Icons.Filled.ContentPaste, "Paste URL", tint = Purple)
                        }
                    }
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("File Name (optional)") },
                    placeholder = { Text("Auto-detected from URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) }
                )
                Column {
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Speed, null, tint = Purple, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download Threads", fontWeight = FontWeight.Medium)
                        }
                        Surface(color = Purple.copy(0.15f), shape = RoundedCornerShape(8.dp)) {
                            Text("  $threads  ", fontWeight = FontWeight.ExtraBold, color = Purple,
                                fontSize = 18.sp, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                    Slider(
                        value = threads.toFloat(),
                        onValueChange = { threads = it.toInt() },
                        valueRange = 1f..100f,
                        steps = 98,
                        colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1 (Safe)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("32 (Fast)", fontSize = 10.sp, color = Purple)
                        Text("64 (Ultra)", fontSize = 10.sp, color = Purple)
                        Text("100 (Hyper 🚀)", fontSize = 10.sp, color = Purple, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(8, 16, 32, 64, 100).forEach { preset ->
                            val isSel = threads == preset
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Purple else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(30.dp)
                                    .clickable { threads = preset }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "${preset}T",
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
        },
        confirmButton = {
            Button(
                onClick = {
                    var cleaned = url.trim()
                    val httpsIdx = cleaned.indexOf("https://")
                    val httpIdx = cleaned.indexOf("http://")
                    if (httpsIdx >= 0) {
                        cleaned = cleaned.substring(httpsIdx)
                    } else if (httpIdx >= 0) {
                        cleaned = cleaned.substring(httpIdx)
                    }

                    if (cleaned.isBlank()) { urlError = "URL cannot be empty"; return@Button }
                    if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
                        urlError = "URL must start with http:// or https://"; return@Button
                    }
                    val name = fileName.ifBlank {
                        cleaned.substringAfterLast("/").substringBefore("?").substringBefore("#")
                            .ifBlank { "download_${System.currentTimeMillis()}" }
                    }
                    onAdd(cleaned, name, threads)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Purple)
            ) {
                Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Start Download", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ─── Settings Dialog ──────────────────────────────────────────────────────────
@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "📂 Downloads saved to: /Downloads/",
                    "⚡ Max threads: Up to 100 per download (Hyper Speed 🚀)",
                    "📡 Uses HTTP Range requests for multi-threading",
                    "🔁 Auto-retry: Retry button on failed downloads",
                    "⏸️ Resume: Downloads continue from where they stopped",
                    "🔒 Requires direct download links (not web pages)"
                ).forEach { Text(it, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

// ─── Contextual Tab Empty State ───────────────────────────────────────────────
@Composable
private fun TabEmptyState(tab: DownloadTab) {
    val (title, msg, icon) = when (tab) {
        DownloadTab.ALL -> Triple(
            "No Downloads Yet",
            "Tap + to paste any direct download link.\nWatch it fly with multi-threaded speed! ⚡",
            Icons.Filled.Download
        )
        DownloadTab.QUEUED -> Triple(
            "No Queued Downloads",
            "There are currently no downloads in the queue.",
            Icons.Filled.HourglassEmpty
        )
        DownloadTab.DOWNLOADING -> Triple(
            "No Active Downloads",
            "No files are currently downloading or paused.",
            Icons.Filled.Downloading
        )
        DownloadTab.SAVED -> Triple(
            "No Saved Downloads Yet",
            "Completed downloads will appear here ready to open. ✓",
            Icons.Filled.CheckCircle
        )
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(
                modifier = Modifier.size(84.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Purple.copy(0.18f), Blue.copy(0.18f)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = Purple, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                msg,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontSize = 13.sp
            )
        }
    }
}

// ─── Small helpers ────────────────────────────────────────────────────────────
@Composable
private fun StatusBadge(status: DownloadStatus, color: Color) {
    Surface(color = color.copy(0.13f), shape = RoundedCornerShape(4.dp)) {
        Text(
            status.name,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ActionBtn(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        border = BorderStroke(1.dp, color.copy(0.5f)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun statusColor(status: DownloadStatus) = when (status) {
    DownloadStatus.DOWNLOADING -> Blue
    DownloadStatus.COMPLETED   -> Green
    DownloadStatus.FAILED      -> Red
    DownloadStatus.PAUSED      -> Amber
    DownloadStatus.CANCELLED   -> Gray
    DownloadStatus.QUEUED      -> Purple
}

// ─── Formatters ──────────────────────────────────────────────────────────────
fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, 4)
    return "%.1f %s".format(bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
}

fun formatSpeed(bps: Long): String {
    if (bps <= 0) return "0 KB/s"
    val kb = bps / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB/s".format(gb)
        mb >= 1.0 -> "%.1f MB/s".format(mb)
        else -> "%.0f KB/s".format(kb)
    }
}

fun formatEta(seconds: Long): String = when {
    seconds < 0   -> "--"
    seconds == 0L -> "< 1s"
    seconds < 60  -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else           -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

fun fileIcon(name: String): ImageVector = when {
    name.endsWith(".mp4",true)||name.endsWith(".mkv",true)||name.endsWith(".avi",true) -> Icons.Filled.VideoFile
    name.endsWith(".mp3",true)||name.endsWith(".wav",true)||name.endsWith(".flac",true) -> Icons.Filled.AudioFile
    name.endsWith(".jpg",true)||name.endsWith(".png",true)||name.endsWith(".gif",true) -> Icons.Filled.Image
    name.endsWith(".pdf",true) -> Icons.Filled.PictureAsPdf
    name.endsWith(".apk",true) -> Icons.Filled.Android
    name.endsWith(".zip",true)||name.endsWith(".rar",true)||name.endsWith(".7z",true) -> Icons.Filled.FolderZip
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}
