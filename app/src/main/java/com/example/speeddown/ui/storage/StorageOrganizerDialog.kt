package com.example.speeddown.ui.storage

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.speeddown.DownloadRepository
import com.example.speeddown.DownloadViewModel
import kotlinx.coroutines.launch

private val Purple = Color(0xFF7C3AED)
private val Blue = Color(0xFF2563EB)
private val Green = Color(0xFF16A34A)
private val Amber = Color(0xFFD97706)
private val Pink = Color(0xFFDB2777)
private val Gray = Color(0xFF6B7280)

@Composable
fun StorageOrganizerDialog(
    viewModel: DownloadViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var breakdown by remember { mutableStateOf<DownloadRepository.StorageBreakdown?>(null) }
    var cleanedMsg by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        breakdown = viewModel.getStorageBreakdown()
        isLoading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Purple.copy(0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.PieChart, null, tint = Purple, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Storage Organizer",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                "SpeedDown Space Breakdown",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "Close")
                    }
                }

                Spacer(Modifier.height(18.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Purple, modifier = Modifier.size(36.dp))
                    }
                } else {
                    val b = breakdown ?: DownloadRepository.StorageBreakdown(0, 0, 0, 0, 0, 0, 0)
                    val totalStr = Formatter.formatFileSize(context, b.totalBytes)

                    // Total Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.35f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Total Downloaded Space", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text(totalStr, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, color = Purple)
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Categories List
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StorageItemRow(
                            icon = Icons.Filled.Movie,
                            color = Purple,
                            title = "Videos",
                            sizeBytes = b.videoBytes,
                            context = context
                        )
                        StorageItemRow(
                            icon = Icons.Filled.MusicNote,
                            color = Pink,
                            title = "Music & Audio",
                            sizeBytes = b.musicBytes,
                            context = context
                        )
                        StorageItemRow(
                            icon = Icons.Filled.FolderZip,
                            color = Amber,
                            title = "Archives & APKs",
                            sizeBytes = b.archiveBytes,
                            context = context
                        )
                        StorageItemRow(
                            icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                            color = Blue,
                            title = "Documents",
                            sizeBytes = b.docBytes,
                            context = context
                        )
                        StorageItemRow(
                            icon = Icons.Filled.CleaningServices,
                            color = Gray,
                            title = "Orphaned Temp Chunks",
                            sizeBytes = b.orphanedPartBytes,
                            context = context
                        )
                    }

                    if (cleanedMsg != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            cleanedMsg!!,
                            color = Green,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    if (b.orphanedPartBytes > 0) {
                        Button(
                            onClick = {
                                viewModel.cleanupOrphanedParts { count ->
                                    scope.launch {
                                        cleanedMsg = "Cleaned $count orphaned temp files!"
                                        breakdown = viewModel.getStorageBreakdown()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.CleaningServices, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Clean Temp Part Files", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun StorageItemRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    title: String,
    sizeBytes: Long,
    context: android.content.Context
) {
    val formatted = Formatter.formatFileSize(context, sizeBytes)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color.copy(0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        }
        Text(formatted, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = color)
    }
}
