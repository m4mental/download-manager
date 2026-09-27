package com.example.speeddown.ui

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.speeddown.DownloadViewModel
import com.example.speeddown.extractor.YouTubeExtractorEngine
import com.example.speeddown.theme.SpeedDownTheme

/**
 * Translucent pop-up activity for instant downloading.
 * When a user shares a link (from YouTube, Chrome, Telegram, WhatsApp, etc.),
 * this activity overlays directly over the current app as a bottom sheet / dialog
 * without opening the full SpeedDown application shell.
 */
class QuickDownloadActivity : ComponentActivity() {

    private val viewModel: DownloadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make window background transparent so host app (e.g. YouTube/Chrome) stays visible
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        // Pre-init engines
        YouTubeExtractorEngine.init(applicationContext)

        val targetUrl = extractUrlFromIntent(intent)
        if (targetUrl.isNullOrBlank()) {
            Toast.makeText(this, "No valid download link found in share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val isYouTube = YouTubeExtractorEngine.isYouTubeUrl(targetUrl)
        if (isYouTube) {
            viewModel.extractYouTubeMedia(targetUrl)
        }

        setContent {
            SpeedDownTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { finish() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isYouTube) {
                            YouTubeDownloadBottomSheet(
                                viewModel = viewModel,
                                onDismiss = { finish() }
                            )
                        } else {
                            AddDownloadDialog(
                                initialUrl = targetUrl,
                                onDismiss = { finish() },
                                onAdd = { url, name, threads ->
                                    viewModel.addDownload(url, name, threads)
                                    Toast.makeText(applicationContext, "Download started: $name", Toast.LENGTH_SHORT).show()
                                    finish()
                                },
                                onAddBatch = { urls, threads ->
                                    viewModel.addBatchDownloads(urls, threads)
                                    Toast.makeText(applicationContext, "${urls.size} downloads added", Toast.LENGTH_SHORT).show()
                                    finish()
                                },
                                onExtractYouTube = { ytUrl ->
                                    viewModel.extractYouTubeMedia(ytUrl)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val targetUrl = extractUrlFromIntent(intent)
        if (!targetUrl.isNullOrBlank()) {
            if (YouTubeExtractorEngine.isYouTubeUrl(targetUrl)) {
                viewModel.extractYouTubeMedia(targetUrl)
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return null

        val clean = raw.trim()
        val magnetIndex = clean.indexOf("magnet:?xt=urn:btih:", ignoreCase = true)
        if (magnetIndex != -1) {
            return clean.substring(magnetIndex).substringBefore(" ").substringBefore("\n").trim()
        }

        val matcher = Regex("https?://[\\w\\d:#@%/;\$()~_?\\+-=\\\\\\.&]+", RegexOption.IGNORE_CASE)
        val extracted = matcher.find(clean)?.value?.trimEnd('.', ',', ')', ']', '}', '>', '"', '\'', ';', ':') ?: clean
        return if (extracted.startsWith("http://") || extracted.startsWith("https://")) extracted else null
    }
}
