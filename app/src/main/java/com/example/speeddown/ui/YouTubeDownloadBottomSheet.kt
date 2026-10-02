package com.example.speeddown.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speeddown.DownloadViewModel
import com.example.speeddown.YouTubeExtractionState
import com.example.speeddown.extractor.YouTubeMediaInfo
import com.example.speeddown.extractor.YouTubeMediaStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeDownloadBottomSheet(
    viewModel: DownloadViewModel,
    onDismiss: () -> Unit
) {
    val extractionState by viewModel.youtubeExtractionState.collectAsState()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
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
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFF0000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "YouTube",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "YouTube Downloader",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Music (Audio) & High-Speed Video",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.height(16.dp))

            when (val state = extractionState) {
                is YouTubeExtractionState.Idle -> {
                    // Closed or no state
                }
                is YouTubeExtractionState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Resolving YouTube audio & video streams...",
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Extracting highest quality M4A & MP4 formats",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                is YouTubeExtractionState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Failed to extract streams",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 16.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                state.message,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
                is YouTubeExtractionState.Success -> {
                    YouTubeMediaContent(
                        mediaInfo = state.mediaInfo,
                        onDownloadSelected = { stream, isAudio ->
                            val ext = if (isAudio) {
                                if (stream.format.equals("M4A", ignoreCase = true)) "m4a" else "opus"
                            } else {
                                if (stream.format.equals("WEBM", ignoreCase = true)) "webm" else "mp4"
                            }
                            val cleanTitle = state.mediaInfo.title
                                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                .trim()
                                .ifBlank { "youtube_${System.currentTimeMillis()}" }
                            val cleanQuality = stream.quality
                                .replace(Regex("(?i)\\s*\\(Audio Included\\)"), "")
                                .replace(Regex("[\\\\/:*?\"<>|]"), "")
                                .trim()
                            val tag = if (cleanQuality.isNotBlank()) " ($cleanQuality)" else " (${stream.format})"
                            val fullFileName = "$cleanTitle$tag.$ext"

                            val defaultThreads = viewModel.settings.value.defaultThreads
                            val audioUrl = if (!isAudio) stream.audioUrl else null

                            viewModel.addDownload(
                                url = stream.url,
                                fileName = fullFileName,
                                threads = defaultThreads,
                                audioUrl = audioUrl
                            )

                            val category = if (isAudio) "Music" else "Video"
                            Toast.makeText(context, "Added $category: $fullFileName", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun YouTubeMediaContent(
    mediaInfo: YouTubeMediaInfo,
    onDownloadSelected: (YouTubeMediaStream, Boolean) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) } // 0: Music (Audio), 1: Video
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(mediaInfo.thumbnailUrl) {
        if (!mediaInfo.thumbnailUrl.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    val req = Request.Builder().url(mediaInfo.thumbnailUrl).build()
                    val resp = client.newCall(req).execute()
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        thumbnailBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // Video Preview Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 110.dp, height = 66.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnailBitmap != null) {
                    Image(
                        bitmap = thumbnailBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Filled.VideoLibrary,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(32.dp)
                    )
                }

                if (mediaInfo.durationSeconds > 0) {
                    val minutes = mediaInfo.durationSeconds / 60
                    val seconds = mediaInfo.durationSeconds % 60
                    val durationText = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = durationText,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mediaInfo.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = mediaInfo.uploader,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    // Tab Selector: Music (Audio) vs Video
    TabRow(
        selectedTabIndex = selectedTab,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
    ) {
        Tab(
            selected = selectedTab == 0,
            onClick = { selectedTab = 0 },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Music / Audio (${mediaInfo.audioStreams.size})", fontWeight = FontWeight.SemiBold)
                }
            }
        )
        Tab(
            selected = selectedTab == 1,
            onClick = { selectedTab = 1 },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Video (${mediaInfo.videoStreams.size})", fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    Spacer(Modifier.height(12.dp))

    // Stream list
    if (selectedTab == 0) {
        // Audio Streams
        if (mediaInfo.audioStreams.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No audio streams available", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mediaInfo.audioStreams) { stream ->
                    StreamRowItem(
                        stream = stream,
                        isAudio = true,
                        onClick = { onDownloadSelected(stream, true) }
                    )
                }
            }
        }
    } else {
        // Video Streams
        if (mediaInfo.videoStreams.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No video streams available", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mediaInfo.videoStreams) { stream ->
                    StreamRowItem(
                        stream = stream,
                        isAudio = false,
                        onClick = { onDownloadSelected(stream, false) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamRowItem(
    stream: YouTubeMediaStream,
    isAudio: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Format Tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isAudio) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stream.format.uppercase(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = if (isAudio) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column {
                    Text(
                        text = stream.quality,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isAudio) "Direct High-Speed Audio Stream" else if (stream.isVideoOnly) "Video Stream" else "Full Video (Audio Included)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FilledTonalIconButton(
                onClick = onClick,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = "Download",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
