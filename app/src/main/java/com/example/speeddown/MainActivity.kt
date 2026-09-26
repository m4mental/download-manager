package com.example.speeddown

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import com.example.speeddown.theme.SpeedDownTheme
import com.example.speeddown.ui.DownloadManagerScreen

class MainActivity : ComponentActivity() {

    private val viewModel: DownloadViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Dismiss any orphaned notification if no downloads are active
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val store = com.example.speeddown.data.DownloadStore.getInstance(this@MainActivity)
            store.ensureLoaded()
            if (store.getActiveDownloads().isEmpty()) {
                val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                nm?.cancel(com.example.speeddown.service.DownloadService.NOTIFICATION_ID)
                try {
                    val cancelIntent = android.content.Intent(this@MainActivity, com.example.speeddown.service.DownloadService::class.java).apply {
                        action = com.example.speeddown.service.DownloadService.ACTION_CANCEL
                    }
                    startService(cancelIntent)
                } catch (_: Exception) {}
            }
        }

        handleIntent(intent)

        setContent {
            SpeedDownTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DownloadManagerScreen(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra("EXTRA_ACTION_NEW_DOWNLOAD", false)) {
            viewModel.setIncomingShareUrl("")
            return
        }

        val rawUrl = when (intent.action) {
            android.content.Intent.ACTION_SEND -> {
                intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            }
            android.content.Intent.ACTION_VIEW -> {
                intent.dataString
            }
            else -> null
        } ?: return

        val clean = rawUrl.trim()
        if (clean.startsWith("magnet:?xt=urn:btih:", ignoreCase = true)) {
            viewModel.setIncomingShareUrl(clean)
            return
        }

        val matcher = Regex("https?://[\\w\\d:#@%/;\$()~_?\\+-=\\\\\\.&]+", RegexOption.IGNORE_CASE)
        val extracted = matcher.find(clean)?.value ?: clean
        if (extracted.startsWith("http://") || extracted.startsWith("https://")) {
            viewModel.setIncomingShareUrl(extracted)
        }
    }
}
