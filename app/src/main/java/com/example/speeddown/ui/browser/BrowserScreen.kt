package com.example.speeddown.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

data class SniffedMedia(
    val url: String,
    val fileName: String,
    val type: String,
    val timestamp: Long = System.currentTimeMillis()
)

private val Purple = Color(0xFF7C3AED)
private val Green = Color(0xFF16A34A)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    initialUrl: String = "https://www.google.com",
    onClose: () -> Unit,
    onStartDownload: (url: String, fileName: String, threads: Int) -> Unit
) {
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var inputUrl by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("SpeedDown Browser") }
    var webProgress by remember { mutableStateOf(0) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showSnifferSheet by remember { mutableStateOf(false) }

    val detectedMedia = remember { mutableStateListOf<SniffedMedia>() }
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        singleLine = true,
                        placeholder = { Text("Search or enter URL...", fontSize = 13.sp) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            focusManager.clearFocus()
                            var target = inputUrl.trim()
                            if (!target.startsWith("http://") && !target.startsWith("https://")) {
                                target = if (target.contains(".") && !target.contains(" ")) {
                                    "https://$target"
                                } else {
                                    "https://www.google.com/search?q=" + java.net.URLEncoder.encode(target, "UTF-8")
                                }
                            }
                            currentUrl = target
                            inputUrl = target
                            webViewInstance?.loadUrl(target)
                        }),
                        trailingIcon = {
                            if (inputUrl.isNotBlank()) {
                                IconButton(onClick = { inputUrl = "" }) {
                                    Icon(Icons.Filled.Close, "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Purple,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(0.4f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f)
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, "Back to Downloads")
                    }
                },
                actions = {
                    IconButton(onClick = { webViewInstance?.reload() }) {
                        Icon(Icons.Filled.Refresh, "Reload")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = { webViewInstance?.goBack() },
                            enabled = canGoBack
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                        IconButton(
                            onClick = { webViewInstance?.goForward() },
                            enabled = canGoForward
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
                        }
                        IconButton(onClick = {
                            currentUrl = "https://www.google.com"
                            inputUrl = currentUrl
                            webViewInstance?.loadUrl(currentUrl)
                        }) {
                            Icon(Icons.Filled.Home, "Home")
                        }
                    }

                    // Floating Media Sniffer Badge
                    AnimatedVisibility(
                        visible = detectedMedia.isNotEmpty(),
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut()
                    ) {
                        Button(
                            onClick = { showSnifferSheet = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Green),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Detected (${detectedMedia.size})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (webProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { webProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Purple,
                    trackColor = Color.Transparent
                )
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            userAgentString = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let {
                                    inputUrl = it
                                    currentUrl = it
                                }
                                canGoBack = canGoBack()
                                canGoForward = canGoForward()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                pageTitle = view?.title ?: "SpeedDown Browser"
                                canGoBack = canGoBack()
                                canGoForward = canGoForward()
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: ""
                                sniffMediaUrl(reqUrl, detectedMedia)
                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                webProgress = newProgress
                            }
                        }

                        loadUrl(currentUrl)
                        webViewInstance = this
                    }
                },
                update = { webViewInstance = it }
            )
        }
    }

    // Sniffer Media Bottom Sheet
    if (showSnifferSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSnifferSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Green.copy(0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Download, null, tint = Green, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("Captured Downloads", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    TextButton(onClick = { detectedMedia.clear() }) {
                        Text("Clear All", color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (detectedMedia.isEmpty()) {
                    Text(
                        "No downloadable media captured yet. Browse any site with videos or download links!",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(detectedMedia, key = { it.url }) { media ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(0.4f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            if (media.type.contains("Video")) Icons.Filled.PlayCircle
                                            else Icons.AutoMirrored.Filled.InsertDriveFile,
                                            null,
                                            tint = Purple,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                media.fileName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                media.type,
                                                fontSize = 11.sp,
                                                color = Purple
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            onStartDownload(media.url, media.fileName, 32)
                                            showSnifferSheet = false
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Purple),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("Download", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun sniffMediaUrl(url: String, list: MutableList<SniffedMedia>) {
    val clean = url.substringBefore("?").substringBefore("#").lowercase()
    val mediaType = when {
        clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm") -> "Video (MP4/MKV)"
        clean.endsWith(".m3u8") || clean.endsWith(".mpd") -> "Video Stream (HLS/DASH)"
        clean.endsWith(".mp3") || clean.endsWith(".m4a") || clean.endsWith(".aac") || clean.endsWith(".flac") -> "Audio (MP3/M4A)"
        clean.endsWith(".zip") || clean.endsWith(".rar") || clean.endsWith(".7z") || clean.endsWith(".tar.gz") -> "Archive (ZIP/RAR)"
        clean.endsWith(".apk") || clean.endsWith(".xapk") -> "App Package (APK)"
        clean.endsWith(".pdf") || clean.endsWith(".epub") -> "Document (PDF)"
        clean.endsWith(".iso") || clean.endsWith(".img") -> "Disk Image (ISO)"
        else -> null
    } ?: return

    val fileName = try {
        val raw = url.substringAfterLast("/").substringBefore("?").substringBefore("#")
        if (raw.isNotBlank()) java.net.URLDecoder.decode(raw, "UTF-8") else "media_${System.currentTimeMillis()}"
    } catch (_: Exception) {
        "media_${System.currentTimeMillis()}"
    }

    if (list.none { it.url == url }) {
        list.add(0, SniffedMedia(url = url, fileName = fileName, type = mediaType))
    }
}
