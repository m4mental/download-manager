package com.example.speeddown.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speeddown.DownloadViewModel
import com.example.speeddown.data.DownloadItem
import com.example.speeddown.data.DownloadSettings
import com.example.speeddown.data.DownloadStatus
import com.example.speeddown.ui.browser.BrowserScreen
import com.example.speeddown.ui.settings.SettingsScreen
import com.example.speeddown.ui.storage.StorageOrganizerDialog
import com.example.speeddown.ui.dialogs.RefreshUrlDialog
import com.example.speeddown.ui.dialogs.DuplicateWarningDialog
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
    val context = androidx.compose.ui.platform.LocalContext.current
    var showBrowser by remember { mutableStateOf(false) }
    var navigatedFromBrowser by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }

    // When user jumped from browser to downloads screen, back gesture returns smoothly back to browser!
    BackHandler(enabled = !showBrowser && !showSettingsScreen && navigatedFromBrowser) {
        navigatedFromBrowser = false
        showBrowser = true
    }

    if (showBrowser) {
        BrowserScreen(
            initialUrl = "speeddown://home",
            onClose = {
                showBrowser = false
                navigatedFromBrowser = false
            },
            onNavigateToDownloads = {
                showBrowser = false
                navigatedFromBrowser = true
            },
            onStartDownload = { url, name, threads ->
                viewModel.addDownload(url, name, threads)
                android.widget.Toast.makeText(context, "Added to SpeedDown: $name", android.widget.Toast.LENGTH_SHORT).show()
            }
        )
        return
    }

    if (showSettingsScreen) {
        SettingsScreen(
            viewModel = viewModel,
            onBack = { showSettingsScreen = false }
        )
        return
    }

    val downloads by viewModel.downloads.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val incomingShareUrl by viewModel.incomingShareUrl.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val tabs = remember { DownloadTab.values() }
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { tabs.size }
    )

    var showAddDialog by remember { mutableStateOf(false) }
    var initialUrlForDialog by remember { mutableStateOf<String?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var errorDetailItem by remember { mutableStateOf<DownloadItem?>(null) }
    var deleteTargetItem by remember { mutableStateOf<DownloadItem?>(null) }
    var checksumTargetItem by remember { mutableStateOf<DownloadItem?>(null) }
    var refreshTargetItem by remember { mutableStateOf<DownloadItem?>(null) }
    var showStorageDialog by remember { mutableStateOf(false) }
    var duplicateWarningFile by remember { mutableStateOf<java.io.File?>(null) }
    var pendingDownloadData by remember { mutableStateOf<Triple<String, String, Int>?>(null) }

    // Multi-Select and Batch Actions State
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showBatchImportDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }

    val clipboardManager = LocalClipboardManager.current
    var clipboardUrl by remember { mutableStateOf<String?>(null) }

    // Auto-open AddDownloadDialog when a link is shared to SpeedDown
    LaunchedEffect(incomingShareUrl) {
        incomingShareUrl?.let { url ->
            initialUrlForDialog = url
            showAddDialog = true
            viewModel.onShareUrlHandled()
        }
    }

    // Sniff clipboard for fresh downloadable links (HTTP, HTTPS, and MAGNET)
    LaunchedEffect(Unit) {
        val clip = clipboardManager.getText()?.text?.trim() ?: ""
        if ((clip.startsWith("http://") || clip.startsWith("https://") || clip.startsWith("magnet:?xt=urn:btih:")) &&
            downloads.none { it.url == clip }
        ) {
            clipboardUrl = clip
        }
    }

    Scaffold(
        topBar = {
            if (selectedIds.isNotEmpty()) {
                TopAppBar(
                    title = {
                        Text(
                            "${selectedIds.size} Selected",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Filled.Close, "Clear Selection")
                        }
                    },
                    actions = {
                        // Select All / Deselect All
                        IconButton(onClick = {
                            selectedIds = if (selectedIds.size == downloads.size) emptySet() else downloads.map { it.id }.toSet()
                        }) {
                            Icon(Icons.Filled.SelectAll, "Select All")
                        }
                        // Pause Selected
                        IconButton(onClick = {
                            selectedIds.forEach { id ->
                                val itm = downloads.find { it.id == id }
                                if (itm != null && (itm.status == DownloadStatus.DOWNLOADING || itm.status == DownloadStatus.QUEUED)) {
                                    viewModel.pause(itm)
                                }
                            }
                        }) {
                            Icon(Icons.Filled.Pause, "Pause Selected")
                        }
                        // Resume Selected
                        IconButton(onClick = {
                            selectedIds.forEach { id ->
                                val itm = downloads.find { it.id == id }
                                if (itm != null && (itm.status == DownloadStatus.PAUSED || itm.status == DownloadStatus.FAILED)) {
                                    viewModel.resume(itm)
                                }
                            }
                        }) {
                            Icon(Icons.Filled.PlayArrow, "Resume Selected")
                        }
                        // Share Selected URLs
                        IconButton(onClick = {
                            val urls = downloads.filter { it.id in selectedIds }.joinToString("\n") { it.url }
                            clipboardManager.setText(AnnotatedString(urls))
                            Toast.makeText(context, "Copied ${selectedIds.size} links to clipboard", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Filled.Share, "Share Selected")
                        }
                        // Delete Selected
                        IconButton(onClick = { showBatchDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, "Delete Selected", tint = Red)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Purple.copy(alpha = 0.12f)
                    )
                )
            } else {
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
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "SpeedDown",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                maxLines = 1
                            )
                        }
                    },
                    actions = {
                        val active = downloads.count { it.status == DownloadStatus.DOWNLOADING }
                        if (active > 0) {
                            Badge(containerColor = Purple) { Text("$active") }
                            Spacer(Modifier.width(6.dp))
                        }
                        IconButton(onClick = { showBrowser = true }) {
                            Icon(Icons.Filled.Language, "Built-in Browser", tint = Purple)
                        }
                        IconButton(onClick = { showStorageDialog = true }) {
                            Icon(Icons.Filled.PieChart, "Storage Organizer", tint = Blue)
                        }
                        IconButton(onClick = { viewModel.clearCompleted() }) {
                            Icon(Icons.Filled.CleaningServices, "Clear completed")
                        }
                        IconButton(onClick = { showSettingsScreen = true }) {
                            Icon(Icons.Filled.Settings, "Settings")
                        }

                        // More Options (Batch Import / Export / Select All)
                        Box {
                            IconButton(onClick = { showTopMenu = true }) {
                                Icon(Icons.Filled.MoreVert, "More Options")
                            }
                            DropdownMenu(
                                expanded = showTopMenu,
                                onDismissRequest = { showTopMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Batch Import Links") },
                                    leadingIcon = { Icon(Icons.Filled.PlaylistAdd, null, tint = Purple) },
                                    onClick = {
                                        showTopMenu = false
                                        showBatchImportDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export All Links") },
                                    leadingIcon = { Icon(Icons.Filled.FileDownload, null, tint = Blue) },
                                    onClick = {
                                        showTopMenu = false
                                        val links = downloads.joinToString("\n") { it.url }
                                        clipboardManager.setText(AnnotatedString(links))
                                        Toast.makeText(context, "Copied ${downloads.size} links to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Select Multiple") },
                                    leadingIcon = { Icon(Icons.Filled.Checklist, null) },
                                    onClick = {
                                        showTopMenu = false
                                        if (downloads.isNotEmpty()) {
                                            selectedIds = setOf(downloads.first().id)
                                        }
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    initialUrlForDialog = null
                    showAddDialog = true
                },
                containerColor = Purple,
                contentColor = Color.White,
                icon = { Icon(Icons.Filled.Add, "Add") },
                text = { Text("New Download", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            DownloadStatsBar(downloads)

            // ─── Clipboard Link Auto-Sniffer Banner ───────────────────────────
            clipboardUrl?.let { clipUrl ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Purple.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Purple.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.ContentPaste, null, tint = Purple, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Link in clipboard", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Purple)
                            Text(
                                clipUrl,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                initialUrlForDialog = clipUrl
                                showAddDialog = true
                                clipboardUrl = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Purple),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Download", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = { clipboardUrl = null },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Filled.Close, "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

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
                            val isSelected = item.id in selectedIds
                            val isSelectionMode = selectedIds.isNotEmpty()

                            DownloadCard(
                                item = item,
                                isSelected = isSelected,
                                isSelectionMode = isSelectionMode,
                                onToggleSelect = {
                                    selectedIds = if (isSelected) selectedIds - item.id else selectedIds + item.id
                                },
                                onLongClick = {
                                    selectedIds = if (isSelected) selectedIds - item.id else selectedIds + item.id
                                },
                                onPause = { viewModel.pause(item) },
                                onResume = { viewModel.resume(item) },
                                onCancel = { viewModel.cancel(item) },
                                onDelete = { deleteTargetItem = item },
                                onOpen = { viewModel.open(item) },
                                onShowError = { errorDetailItem = item },
                                onShowChecksum = { checksumTargetItem = item },
                                isNothingPlayerInstalled = viewModel.isNothingPlayerInstalled(),
                                onOpenWithNothingPlayer = { viewModel.openInNothingPlayer(item) },
                                onStreamWithNothingPlayer = { viewModel.streamInNothingPlayer(item) },
                                onRefreshUrl = { refreshTargetItem = item }
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
            initialUrl = initialUrlForDialog,
            onDismiss = {
                showAddDialog = false
                initialUrlForDialog = null
            },
            onAdd = { url, name, threads ->
                val dup = viewModel.checkDuplicate(name)
                if (dup != null) {
                    duplicateWarningFile = dup
                    pendingDownloadData = Triple(url, name, threads)
                } else {
                    viewModel.addDownload(url, name, threads)
                }
                showAddDialog = false
                initialUrlForDialog = null
            },
            onAddBatch = { urls, threads ->
                viewModel.addBatchDownloads(urls, threads)
                showAddDialog = false
                initialUrlForDialog = null
            }
        )
    }
    if (showStorageDialog) {
        StorageOrganizerDialog(
            viewModel = viewModel,
            onDismiss = { showStorageDialog = false }
        )
    }
    refreshTargetItem?.let { item ->
        RefreshUrlDialog(
            item = item,
            onDismiss = { refreshTargetItem = null },
            onConfirm = { newUrl ->
                viewModel.refreshUrl(item.id, newUrl)
                refreshTargetItem = null
            }
        )
    }
    duplicateWarningFile?.let { dupFile ->
        DuplicateWarningDialog(
            existingFile = dupFile,
            onDismiss = {
                duplicateWarningFile = null
                pendingDownloadData = null
            },
            onOpenExisting = {
                val dummyItem = DownloadItem(
                    url = pendingDownloadData?.first ?: "",
                    fileName = dupFile.name,
                    filePath = dupFile.absolutePath,
                    category = "Videos"
                )
                viewModel.open(dummyItem)
                duplicateWarningFile = null
                pendingDownloadData = null
            },
            onOverwrite = {
                pendingDownloadData?.let { (u, n, t) ->
                    viewModel.addDownload(u, n, t)
                }
                duplicateWarningFile = null
                pendingDownloadData = null
            },
            onRenameAndDownload = { newName ->
                pendingDownloadData?.let { (u, _, t) ->
                    viewModel.addDownload(u, newName, t)
                }
                duplicateWarningFile = null
                pendingDownloadData = null
            }
        )
    }
    errorDetailItem?.let { item ->
        ErrorDetailDialog(item = item, onDismiss = { errorDetailItem = null })
    }
    checksumTargetItem?.let { item ->
        ChecksumDialog(
            item = item,
            onCalculateChecksums = { viewModel.calculateChecksums(it) },
            onDismiss = { checksumTargetItem = null }
        )
    }

    // Batch Import Links Dialog
    if (showBatchImportDialog) {
        var batchText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBatchImportDialog = false },
            icon = { Icon(Icons.Filled.PlaylistAdd, null, tint = Purple, modifier = Modifier.size(32.dp)) },
            title = { Text("Batch Import Links", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Paste multiple URLs (one per line) or magnet links to queue all downloads:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = batchText,
                        onValueChange = { batchText = it },
                        placeholder = { Text("https://example.com/file1.zip\nhttps://example.com/movie.mp4\nmagnet:?xt=...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            val clip = clipboardManager.getText()?.text ?: ""
                            if (clip.isNotBlank()) batchText = if (batchText.isBlank()) clip else "$batchText\n$clip"
                        }) {
                            Icon(Icons.Filled.ContentPaste, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Paste Clipboard", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val lines = batchText.lines()
                            .map { it.trim() }
                            .filter { it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://") || it.startsWith("magnet:")) }
                        if (lines.isNotEmpty()) {
                            viewModel.addBatchDownloads(lines, 8)
                            Toast.makeText(context, "Added ${lines.size} downloads to queue", Toast.LENGTH_SHORT).show()
                        }
                        showBatchImportDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Text("Start Batch")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Batch Delete Confirmation Dialog
    if (showBatchDeleteDialog) {
        var deleteFromDisk by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            icon = { Icon(Icons.Filled.Delete, null, tint = Red, modifier = Modifier.size(32.dp)) },
            title = { Text("Delete ${selectedIds.size} Downloads?", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Are you sure you want to remove the selected downloads from SpeedDown?")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deleteFromDisk, onCheckedChange = { deleteFromDisk = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Also delete downloaded files from storage", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedIds.forEach { id ->
                            val itm = downloads.find { it.id == id }
                            if (itm != null) {
                                viewModel.delete(itm, deleteFromDisk)
                            }
                        }
                        selectedIds = emptySet()
                        showBatchDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Red)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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

// ─── IDM-Style Segmented Progress Visualizer ──────────────────────────────────
@Composable
fun MultiThreadSegmentVisualizer(
    threads: Int,
    partProgress: List<Float>,
    statusColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Layers, null, tint = Purple, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "Multi-Thread Streams ($threads Threads)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val finished = partProgress.count { it >= 1.0f }
            Text(
                "$finished/$threads finished",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (finished == threads) Green else Purple
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp)
        ) {
            val displayCount = if (partProgress.isNotEmpty()) partProgress.size else threads
            for (i in 0 until displayCount) {
                val p = partProgress.getOrNull(i) ?: 0f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            when {
                                p >= 1.0f -> Green
                                p > 0f -> statusColor.copy(alpha = 0.35f + (p * 0.65f))
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            }
                        )
                )
            }
        }
    }
}

// ─── Download Card ────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadCard(
    item: DownloadItem,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShowError: () -> Unit,
    onShowChecksum: () -> Unit,
    isNothingPlayerInstalled: Boolean = false,
    onOpenWithNothingPlayer: (() -> Unit)? = null,
    onStreamWithNothingPlayer: (() -> Unit)? = null,
    onRefreshUrl: (() -> Unit)? = null
) {
    val statusColor = statusColor(item.status)
    val isActive = item.status == DownloadStatus.DOWNLOADING
    val isPaused = item.status == DownloadStatus.PAUSED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) onToggleSelect()
                },
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        border = if (isSelected) BorderStroke(2.dp, Purple) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 6.dp else 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Header ──────────────────────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                }
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
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("📁 ${item.category}", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                        if (item.isTorrent) {
                            Surface(color = Blue.copy(0.15f), shape = RoundedCornerShape(4.dp)) {
                                Text("🧲 S:${item.torrentSeeds} P:${item.torrentPeers}", color = Blue, fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                            }
                        }
                        if (item.isHls) {
                            Surface(color = Purple.copy(0.15f), shape = RoundedCornerShape(4.dp)) {
                                Text("📺 HLS", color = Purple, fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                            }
                        }
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
                Spacer(Modifier.height(8.dp))

                // IDM-style Segmented Progress Visualizer
                if (item.threads > 1) {
                    MultiThreadSegmentVisualizer(
                        threads = item.threads,
                        partProgress = item.partProgress,
                        statusColor = statusColor
                    )
                    Spacer(Modifier.height(8.dp))
                }

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
                        if (isNothingPlayerInstalled && (item.category == "Videos" || item.category == "Music" || item.isStreamable)) {
                            ActionBtn("⚡ Stream", Icons.Filled.PlayCircle, Purple) {
                                if (onStreamWithNothingPlayer != null) onStreamWithNothingPlayer()
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        ActionBtn("Pause",  Icons.Filled.Pause,  Amber, onPause)
                        Spacer(Modifier.width(8.dp))
                        ActionBtn("Cancel", Icons.Filled.Close,  Red,   onCancel)
                    }
                    DownloadStatus.PAUSED -> {
                        if (isNothingPlayerInstalled && (item.category == "Videos" || item.category == "Music" || item.isStreamable)) {
                            ActionBtn("⚡ Stream", Icons.Filled.PlayCircle, Purple) {
                                if (onStreamWithNothingPlayer != null) onStreamWithNothingPlayer()
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        ActionBtn("Resume", Icons.Filled.PlayArrow, Blue, onResume)
                        Spacer(Modifier.width(8.dp))
                        ActionBtn("Cancel", Icons.Filled.Close,     Red,  onCancel)
                    }
                    DownloadStatus.COMPLETED -> {
                        if (isNothingPlayerInstalled && (item.category == "Videos" || item.category == "Music")) {
                            ActionBtn("Nothing Player", Icons.Filled.PlayCircle, Purple) {
                                if (onOpenWithNothingPlayer != null) onOpenWithNothingPlayer() else onOpen()
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        ActionBtn("Open",   Icons.AutoMirrored.Filled.OpenInNew, Green, onOpen)
                        Spacer(Modifier.width(8.dp))
                        ActionBtn("Hash",   Icons.Filled.VerifiedUser, Purple, onShowChecksum)
                        Spacer(Modifier.width(8.dp))
                        ActionBtn("Delete", Icons.Filled.Delete,                 Red,   onDelete)
                    }
                    DownloadStatus.FAILED -> {
                        ActionBtn("Refresh URL", Icons.Filled.Link, Blue) {
                            if (onRefreshUrl != null) onRefreshUrl()
                        }
                        Spacer(Modifier.width(8.dp))
                        ActionBtn("Retry",   Icons.Filled.Refresh,  Purple, onResume)
                        Spacer(Modifier.width(8.dp))
                        ActionBtn("Details", Icons.Filled.Info,    Gray,  onShowError)
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

// ─── Add Download Dialog (Single Link & Batch Downloads) ──────────────────────
@Composable
fun AddDownloadDialog(
    initialUrl: String? = null,
    onDismiss: () -> Unit,
    onAdd: (url: String, name: String, threads: Int) -> Unit,
    onAddBatch: (urls: List<String>, threads: Int) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var url by remember { mutableStateOf(initialUrl ?: "") }
    var fileName by remember { mutableStateOf("") }
    var threads by remember { mutableStateOf(16) }
    var urlError by remember { mutableStateOf("") }

    var batchText by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    // Extract valid URLs from multi-line batch text
    val parsedBatchUrls = remember(batchText) {
        batchText.lines()
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
    }

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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Single vs Batch Tab Selector
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    contentColor = Purple,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Single Link", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Batch Links", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                if (parsedBatchUrls.isNotEmpty()) {
                                    Spacer(Modifier.width(4.dp))
                                    Surface(color = Purple, shape = CircleShape) {
                                        Text(" ${parsedBatchUrls.size} ", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    )
                }

                if (selectedTab == 0) {
                    // ── Single Link Mode ──
                    OutlinedTextField(
                        value = url,
                        onValueChange = { newUrl ->
                            url = newUrl
                            urlError = ""
                            if (newUrl.startsWith("magnet:?xt=urn:btih:", ignoreCase = true)) {
                                val meta = com.example.speeddown.engine.TorrentEngine.parseMagnet(newUrl)
                                if (meta != null && fileName.isBlank()) {
                                    fileName = meta.displayName
                                }
                            } else if (fileName.isBlank()) {
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
                        placeholder = { Text("https://... or magnet:?xt=urn:btih:...") },
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
                } else {
                    // ── Batch Download Mode ──
                    OutlinedTextField(
                        value = batchText,
                        onValueChange = { batchText = it },
                        label = { Text("Paste Links (1 per line)") },
                        placeholder = { Text("https://example.com/file1.zip\nhttps://example.com/file2.mkv\nhttps://example.com/file3.mp4") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 6,
                        trailingIcon = {
                            IconButton(onClick = {
                                val clipText = clipboardManager.getText()?.text?.trim() ?: ""
                                if (clipText.isNotBlank()) {
                                    batchText = if (batchText.isBlank()) clipText else "$batchText\n$clipText"
                                }
                            }) {
                                Icon(Icons.Filled.ContentPaste, "Paste", tint = Purple)
                            }
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = if (parsedBatchUrls.isNotEmpty()) Green.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                " ${parsedBatchUrls.size} links detected ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (parsedBatchUrls.isNotEmpty()) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                        if (batchText.isNotBlank()) {
                            TextButton(onClick = { batchText = "" }) {
                                Text("Clear", fontSize = 11.sp, color = Red)
                            }
                        }
                    }
                }

                // Threads slider & presets (shared for both modes)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Speed, null, tint = Purple, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (selectedTab == 0) "Download Threads" else "Threads per Download",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp
                            )
                        }
                        Surface(color = Purple.copy(0.15f), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                "  $threads  ",
                                fontWeight = FontWeight.ExtraBold,
                                color = Purple,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
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
                    Spacer(Modifier.height(6.dp))
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
            if (selectedTab == 0) {
                Button(
                    onClick = {
                        var cleaned = url.trim()
                        val httpsIdx = cleaned.indexOf("https://")
                        val httpIdx = cleaned.indexOf("http://")
                        if (httpsIdx >= 0) cleaned = cleaned.substring(httpsIdx)
                        else if (httpIdx >= 0) cleaned = cleaned.substring(httpIdx)

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
            } else {
                Button(
                    onClick = {
                        if (parsedBatchUrls.isNotEmpty()) {
                            onAddBatch(parsedBatchUrls, threads)
                        }
                    },
                    enabled = parsedBatchUrls.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start Batch (${parsedBatchUrls.size})", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ─── Checksum Verifier Dialog (MD5 & SHA-256) ─────────────────────────────────
@Composable
fun ChecksumDialog(
    item: DownloadItem,
    onCalculateChecksums: suspend (String) -> Pair<String, String>,
    onDismiss: () -> Unit
) {
    var md5Hash by remember { mutableStateOf("Calculating...") }
    var sha256Hash by remember { mutableStateOf("Calculating...") }
    var isCalculating by remember { mutableStateOf(true) }
    var compareInput by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(item.filePath) {
        try {
            val (md5, sha256) = onCalculateChecksums(item.filePath)
            md5Hash = md5
            sha256Hash = sha256
        } catch (e: Exception) {
            md5Hash = "Error: ${e.message}"
            sha256Hash = "Error: ${e.message}"
        } finally {
            isCalculating = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.VerifiedUser, null, tint = Purple, modifier = Modifier.size(28.dp)) },
        title = { Text("File Checksum Verifier", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.fileName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatSize(item.downloadedSize), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (isCalculating) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Purple)
                        Spacer(Modifier.width(10.dp))
                        Text("Calculating cryptographic hashes...", fontSize = 12.sp)
                    }
                } else {
                    // MD5 block
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.4f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("MD5", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Purple)
                                IconButton(onClick = { clipboardManager.setText(AnnotatedString(md5Hash)) }, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Filled.ContentCopy, "Copy MD5", modifier = Modifier.size(14.dp), tint = Purple)
                                }
                            }
                            Text(md5Hash, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }

                    // SHA-256 block
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.4f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("SHA-256", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Purple)
                                IconButton(onClick = { clipboardManager.setText(AnnotatedString(sha256Hash)) }, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Filled.ContentCopy, "Copy SHA-256", modifier = Modifier.size(14.dp), tint = Purple)
                                }
                            }
                            Text(sha256Hash, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    // Compare block
                    OutlinedTextField(
                        value = compareInput,
                        onValueChange = { compareInput = it.trim() },
                        label = { Text("Compare Expected Hash") },
                        placeholder = { Text("Paste expected MD5 or SHA-256") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (compareInput.isNotBlank()) {
                                IconButton(onClick = { compareInput = "" }) {
                                    Icon(Icons.Filled.Clear, "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    )

                    if (compareInput.isNotBlank()) {
                        val matches = compareInput.equals(md5Hash, ignoreCase = true) ||
                                compareInput.equals(sha256Hash, ignoreCase = true)
                        Surface(
                            color = if (matches) Green.copy(0.12f) else Red.copy(0.12f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (matches) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                    null,
                                    tint = if (matches) Green else Red,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (matches) "✓ Hash Matched! File is authentic & verified." else "✗ Hash Mismatch! Checksum does not match.",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (matches) Green else Red
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
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
