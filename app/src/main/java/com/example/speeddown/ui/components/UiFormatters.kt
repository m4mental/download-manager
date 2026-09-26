package com.example.speeddown.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.speeddown.data.DownloadStatus
import kotlin.math.ln
import kotlin.math.pow

val Purple = Color(0xFF7C3AED)
val Blue   = Color(0xFF2563EB)
val Green  = Color(0xFF16A34A)
val Red    = Color(0xFFDC2626)
val Amber  = Color(0xFFF59E0B)
val Gray   = Color(0xFF6B7280)

fun statusColor(status: DownloadStatus): Color = when (status) {
    DownloadStatus.DOWNLOADING -> Blue
    DownloadStatus.COMPLETED   -> Green
    DownloadStatus.FAILED      -> Red
    DownloadStatus.PAUSED      -> Amber
    DownloadStatus.CANCELLED   -> Gray
    DownloadStatus.QUEUED      -> Purple
}

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
    name.endsWith(".mp4", true) || name.endsWith(".mkv", true) || name.endsWith(".avi", true) -> Icons.Filled.VideoFile
    name.endsWith(".mp3", true) || name.endsWith(".wav", true) || name.endsWith(".flac", true) -> Icons.Filled.AudioFile
    name.endsWith(".jpg", true) || name.endsWith(".png", true) || name.endsWith(".gif", true) -> Icons.Filled.Image
    name.endsWith(".pdf", true) -> Icons.Filled.PictureAsPdf
    name.endsWith(".apk", true) -> Icons.Filled.Android
    name.endsWith(".zip", true) || name.endsWith(".rar", true) || name.endsWith(".7z", true) -> Icons.Filled.FolderZip
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}
