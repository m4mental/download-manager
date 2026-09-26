package com.example.speeddown.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.speeddown.MainActivity
import com.example.speeddown.data.DownloadStatus
import com.example.speeddown.data.DownloadStore
import com.example.speeddown.engine.MultiThreadDownloader
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DownloadService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_CANCEL = "ACTION_CANCEL"
        const val EXTRA_DOWNLOAD_ID = "EXTRA_DOWNLOAD_ID"
        const val CHANNEL_ID = "SpeedDown_Channel"
        const val COMPLETE_CHANNEL_ID = "SpeedDown_Complete_Channel"
        const val NOTIFICATION_ID = 1001
    }

    private lateinit var store: DownloadStore
    private lateinit var downloader: MultiThreadDownloader
    private lateinit var hlsDownloader: com.example.speeddown.engine.HlsDownloader
    private lateinit var torrentEngine: com.example.speeddown.engine.TorrentEngine
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isForeground = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SpeedDown:DownloadWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours safe timeout
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        store = DownloadStore.getInstance(this)

        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 250
            maxRequestsPerHost = 200
        }
        val connectionPool = okhttp3.ConnectionPool(120, 5, TimeUnit.MINUTES)

        val browserSettings = runCatching {
            kotlinx.coroutines.runBlocking {
                com.example.speeddown.data.BrowserSettingsStore.getInstance(applicationContext).getSnapshot()
            }
        }.getOrNull()

        val dns = if (browserSettings != null) {
            com.example.speeddown.engine.SecureDnsHelper.createOkHttpDns(browserSettings.dnsProvider, browserSettings.customDnsIp)
        } else {
            okhttp3.Dns.SYSTEM
        }

        val client = OkHttpClient.Builder()
            .dns(dns)
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        downloader = MultiThreadDownloader(client, store)
        hlsDownloader = com.example.speeddown.engine.HlsDownloader(client, store)
        torrentEngine = com.example.speeddown.engine.TorrentEngine(client, store)
        createNotificationChannel()
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    super.onAvailable(network)
                    serviceScope.launch {
                        // Network is back: auto-resume any queued or interrupted downloads
                        checkAndStartNextQueued()
                    }
                }
            }
            networkCallback?.let { cm.registerNetworkCallback(request, it) }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val id = intent?.getLongExtra(EXTRA_DOWNLOAD_ID, -1L) ?: -1L

        if (action == ACTION_START || action == ACTION_RESUME) {
            startForegroundCompat(id)
        }

        when (action) {
            ACTION_START -> {
                if (id != -1L) startDownload(id)
            }
            ACTION_PAUSE -> {
                if (id != -1L) {
                    downloader.pauseDownload(id)
                    hlsDownloader.pauseHls(id)
                    torrentEngine.pauseTorrent(id)
                    serviceScope.launch {
                        store.updateStatus(id, DownloadStatus.PAUSED)
                        updateActiveNotification()
                        checkAndStartNextQueued()
                    }
                }
            }
            ACTION_RESUME -> {
                if (id != -1L) resumeDownload(id)
            }
            ACTION_CANCEL -> {
                if (id != -1L) {
                    downloader.cancelDownload(id)
                    hlsDownloader.cancelHls(id)
                    torrentEngine.cancelTorrent(id)
                    serviceScope.launch {
                        store.updateStatus(id, DownloadStatus.CANCELLED)
                        updateActiveNotification()
                        checkAndStartNextQueued()
                    }
                } else {
                    updateActiveNotification()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun checkAndStartNextQueued() {
        serviceScope.launch {
            val settings = store.getSettingsSnapshot()
            val active = store.getActiveDownloads().count()
            if (settings.maxConcurrent == 0 || active < settings.maxConcurrent) {
                val nextQueued = store.getAll().firstOrNull { it.status == DownloadStatus.QUEUED }
                if (nextQueued != null) {
                    startDownload(nextQueued.id)
                }
            }
        }
    }

    private fun startDownload(downloadId: Long) {
        serviceScope.launch {
            val item = store.getById(downloadId) ?: return@launch
            // If already cancelled or paused, do not initiate download
            if (item.status == DownloadStatus.CANCELLED || item.status == DownloadStatus.PAUSED) {
                return@launch
            }

            val settings = store.getSettingsSnapshot()
            if (settings.wifiOnly && !isWifiConnected()) {
                store.updateStatus(downloadId, DownloadStatus.QUEUED)
                store.updateError(downloadId, DownloadStatus.QUEUED, "Waiting for Wi-Fi connection (Wi-Fi Only enabled)")
                updateActiveNotification()
                return@launch
            }

            val activeCount = store.getActiveDownloads().count { it.id != downloadId }
            if (settings.maxConcurrent in 1..activeCount) {
                store.updateStatus(downloadId, DownloadStatus.QUEUED)
                updateActiveNotification()
                return@launch
            }

            store.updateStatus(downloadId, DownloadStatus.DOWNLOADING)
            startForegroundCompat(downloadId, item.fileName)

            val progressCallback: (Long, Long, List<Float>) -> Unit = { downloaded, speed, parts ->
                serviceScope.launch {
                    val currentItem = store.getById(downloadId)
                    if (currentItem == null || currentItem.status != DownloadStatus.DOWNLOADING) {
                        downloader.cancelDownload(downloadId)
                        hlsDownloader.cancelHls(downloadId)
                        torrentEngine.cancelTorrent(downloadId)
                        updateActiveNotification()
                        return@launch
                    }
                    store.updateProgress(downloadId, downloaded, speed, DownloadStatus.DOWNLOADING, parts)
                    val total = currentItem.totalSize
                    val name = currentItem.fileName
                    updateNotification(downloadId, name, total, downloaded, speed)
                }
            }

            val completeCallback: () -> Unit = {
                serviceScope.launch {
                    val currentItem = store.getById(downloadId)
                    if (currentItem != null && currentItem.status == DownloadStatus.DOWNLOADING) {
                        store.markCompleted(downloadId)
                        showDownloadCompleteNotification(currentItem.fileName)
                    }
                    updateActiveNotification()
                    checkAndStartNextQueued()
                }
            }

            val errorCallback: (String) -> Unit = { error ->
                serviceScope.launch {
                    val currentItem = store.getById(downloadId)
                    if (currentItem != null && currentItem.status == DownloadStatus.DOWNLOADING) {
                        store.updateError(downloadId, DownloadStatus.FAILED, error)
                    }
                    updateActiveNotification()
                    checkAndStartNextQueued()
                }
            }

            when {
                item.isTorrent || com.example.speeddown.engine.TorrentEngine.isMagnet(item.url) -> {
                    torrentEngine.startTorrentDownload(
                        scope = serviceScope,
                        item = item,
                        onProgress = progressCallback,
                        onComplete = completeCallback,
                        onError = errorCallback
                    )
                }
                item.isHls || item.url.contains(".m3u8", ignoreCase = true) -> {
                    hlsDownloader.startHlsDownload(
                        scope = serviceScope,
                        item = item,
                        onProgress = progressCallback,
                        onComplete = completeCallback,
                        onError = errorCallback
                    )
                }
                else -> {
                    downloader.startDownload(
                        scope = serviceScope,
                        item = item,
                        speedLimitKbps = settings.speedLimitKbps,
                        onProgress = progressCallback,
                        onComplete = completeCallback,
                        onError = errorCallback
                    )
                }
            }
        }
    }

    private fun resumeDownload(downloadId: Long) {
        startDownload(downloadId)
    }

    private fun updateActiveNotification() {
        val active = store.getActiveDownloads()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (active.isEmpty()) {
            releaseWakeLock()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Exception) {}
            isForeground = false
            nm.cancel(NOTIFICATION_ID)
            stopSelf()
        } else {
            acquireWakeLock()
            val firstActive = active.first()
            updateNotification(
                downloadId = firstActive.id,
                name = firstActive.fileName,
                total = firstActive.totalSize,
                downloaded = firstActive.downloadedSize,
                speed = firstActive.speed
            )
        }
    }

    private fun startForegroundCompat(downloadId: Long = -1L, name: String = "Download Service Active") {
        acquireWakeLock()
        if (!isForeground) {
            val notification = buildNotification(downloadId, "SpeedDown", name, 0, 0, ongoing = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        }
    }

    private fun updateNotification(downloadId: Long, name: String, total: Long, downloaded: Long, speed: Long) {
        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        val speedStr = formatSpeed(speed)
        val text = if (total > 0) "$progress% • $speedStr" else "$speedStr downloaded"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(downloadId, name, text, progress, total, ongoing = true))
    }

    private fun buildNotification(
        downloadId: Long = -1L,
        title: String,
        text: String,
        progress: Int,
        total: Long,
        ongoing: Boolean = true
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply {
                if (total > 0) setProgress(100, progress, false)
                else if (ongoing) setProgress(0, 0, true)
                else setProgress(0, 0, false)
            }

        if (downloadId != -1L && ongoing) {
            val cancelIntent = Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            val cancelPendingIntent = PendingIntent.getService(
                this,
                (downloadId and 0x7FFFFFFF).toInt(),
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )
        }

        return builder.build()
    }

    private fun showDownloadCompleteNotification(fileName: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val settings = kotlinx.coroutines.runBlocking {
            try { store.getSettingsSnapshot() } catch (_: Exception) { com.example.speeddown.data.DownloadSettings() }
        }

        // Haptic feedback if enabled
        if (settings.vibrateOnComplete) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                    vm?.defaultVibrator?.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    val v = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v?.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        v?.vibrate(200)
                    }
                }
            } catch (_: Exception) {}
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val channelToUse = if (settings.soundOnComplete) COMPLETE_CHANNEL_ID else CHANNEL_ID
        val notification = NotificationCompat.Builder(this, channelToUse)
            .setContentTitle("Download Complete ✓")
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(if (settings.soundOnComplete) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val completeId = (System.currentTimeMillis() % 100000).toInt() + 2000
        nm.notify(completeId, notification)
    }

    private fun formatSpeed(bps: Long): String {
        if (bps <= 0) return "0 KB/s"
        val kb = bps / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) "%.1f MB/s".format(mb) else "%.0f KB/s".format(kb)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val progressChannel = NotificationChannel(
                CHANNEL_ID, "SpeedDown Active Downloads", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download progress notifications"
                enableVibration(false)
                setSound(null, null)
            }
            val completeChannel = NotificationChannel(
                COMPLETE_CHANNEL_ID, "SpeedDown Completed Downloads", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when downloads finish"
                enableVibration(true)
            }
            nm.createNotificationChannel(progressChannel)
            nm.createNotificationChannel(completeChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
        serviceScope.cancel()
    }
}
