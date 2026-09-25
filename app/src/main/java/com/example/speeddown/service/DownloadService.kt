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
        const val NOTIFICATION_ID = 1001
    }

    private lateinit var store: DownloadStore
    private lateinit var downloader: MultiThreadDownloader
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        store = DownloadStore.getInstance(this)

        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 250
            maxRequestsPerHost = 200
        }
        val connectionPool = okhttp3.ConnectionPool(120, 5, TimeUnit.MINUTES)

        val client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        downloader = MultiThreadDownloader(client, store)
        createNotificationChannel()
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

            downloader.startDownload(
                scope = serviceScope,
                item = item,
                speedLimitKbps = settings.speedLimitKbps,
                onProgress = { downloaded, speed, parts ->
                    serviceScope.launch {
                        val currentItem = store.getById(downloadId)
                        // If item was cancelled, deleted from store (null), or not downloading:
                        if (currentItem == null || currentItem.status != DownloadStatus.DOWNLOADING) {
                            downloader.cancelDownload(downloadId)
                            updateActiveNotification()
                            return@launch
                        }
                        store.updateProgress(downloadId, downloaded, speed, DownloadStatus.DOWNLOADING, parts)
                        val total = currentItem.totalSize
                        val name = currentItem.fileName
                        updateNotification(downloadId, name, total, downloaded, speed)
                    }
                },
                onComplete = {
                    serviceScope.launch {
                        val currentItem = store.getById(downloadId)
                        if (currentItem != null && currentItem.status == DownloadStatus.DOWNLOADING) {
                            store.markCompleted(downloadId)
                            showDownloadCompleteNotification(currentItem.fileName)
                        }
                        updateActiveNotification()
                        checkAndStartNextQueued()
                    }
                },
                onError = { error ->
                    serviceScope.launch {
                        val currentItem = store.getById(downloadId)
                        if (currentItem != null && currentItem.status == DownloadStatus.DOWNLOADING) {
                            store.updateError(downloadId, DownloadStatus.FAILED, error)
                        }
                        updateActiveNotification()
                        checkAndStartNextQueued()
                    }
                }
            )
        }
    }

    private fun resumeDownload(downloadId: Long) {
        startDownload(downloadId)
    }

    private fun updateActiveNotification() {
        val active = store.getActiveDownloads()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (active.isEmpty()) {
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
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOngoing(false)
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
        val channel = NotificationChannel(
            CHANNEL_ID, "SpeedDown Downloads", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Download progress notifications" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
        serviceScope.cancel()
    }
}
