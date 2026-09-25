package com.example.speeddown.service

import android.app.*
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
        startForegroundCompat()

        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) startDownload(id)
            }
            ACTION_PAUSE -> {
                val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) {
                    downloader.pauseDownload(id)
                    serviceScope.launch {
                        store.updateStatus(id, DownloadStatus.PAUSED)
                        updateActiveNotification()
                    }
                }
            }
            ACTION_RESUME -> {
                val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) resumeDownload(id)
            }
            ACTION_CANCEL -> {
                val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) {
                    downloader.cancelDownload(id)
                    serviceScope.launch {
                        store.updateStatus(id, DownloadStatus.CANCELLED)
                        updateActiveNotification()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(downloadId: Long) {
        serviceScope.launch {
            val item = store.getById(downloadId) ?: return@launch
            // If already cancelled or paused, do not initiate download
            if (item.status == DownloadStatus.CANCELLED || item.status == DownloadStatus.PAUSED) {
                return@launch
            }
            store.updateStatus(downloadId, DownloadStatus.DOWNLOADING)

            downloader.startDownload(
                scope = serviceScope,
                item = item,
                onProgress = { downloaded, speed ->
                    serviceScope.launch {
                        val currentItem = store.getById(downloadId)
                        if (currentItem?.status == DownloadStatus.PAUSED ||
                            currentItem?.status == DownloadStatus.CANCELLED ||
                            currentItem?.status == DownloadStatus.COMPLETED ||
                            currentItem?.status == DownloadStatus.FAILED
                        ) {
                            return@launch
                        }
                        store.updateProgress(downloadId, downloaded, speed, DownloadStatus.DOWNLOADING)
                        val total = currentItem?.totalSize ?: item.totalSize
                        val name = currentItem?.fileName ?: item.fileName
                        updateNotification(name, total, downloaded, speed)
                    }
                },
                onComplete = {
                    serviceScope.launch {
                        store.markCompleted(downloadId)
                        updateActiveNotification()
                    }
                },
                onError = { error ->
                    serviceScope.launch {
                        store.updateError(downloadId, DownloadStatus.FAILED, error)
                        updateActiveNotification()
                    }
                }
            )
        }
    }

    private fun resumeDownload(downloadId: Long) {
        serviceScope.launch {
            val item = store.getById(downloadId) ?: return@launch
            store.updateStatus(downloadId, DownloadStatus.DOWNLOADING)

            downloader.resumeDownload(
                scope = serviceScope,
                item = item,
                onProgress = { downloaded, speed ->
                    serviceScope.launch {
                        val currentItem = store.getById(downloadId)
                        if (currentItem?.status == DownloadStatus.PAUSED ||
                            currentItem?.status == DownloadStatus.CANCELLED ||
                            currentItem?.status == DownloadStatus.COMPLETED ||
                            currentItem?.status == DownloadStatus.FAILED
                        ) {
                            return@launch
                        }
                        store.updateProgress(downloadId, downloaded, speed, DownloadStatus.DOWNLOADING)
                        val total = currentItem?.totalSize ?: item.totalSize
                        val name = currentItem?.fileName ?: item.fileName
                        updateNotification(name, total, downloaded, speed)
                    }
                },
                onComplete = {
                    serviceScope.launch {
                        store.markCompleted(downloadId)
                        updateActiveNotification()
                    }
                },
                onError = { error ->
                    serviceScope.launch {
                        store.updateError(downloadId, DownloadStatus.FAILED, error)
                        updateActiveNotification()
                    }
                }
            )
        }
    }

    private fun updateActiveNotification() {
        serviceScope.launch {
            val all = store.getAll()
            val active = all.filter { it.status == DownloadStatus.DOWNLOADING }
            if (active.isEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val completed = all.count { it.status == DownloadStatus.COMPLETED }
                if (completed > 0) {
                    nm.notify(
                        NOTIFICATION_ID,
                        buildNotification("SpeedDown", "All active downloads finished", 100, 100, ongoing = false)
                    )
                } else {
                    nm.cancel(NOTIFICATION_ID)
                }
            }
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("SpeedDown", "Download Service Active", 0, 0, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(name: String, total: Long, downloaded: Long, speed: Long) {
        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        val speedStr = formatSpeed(speed)
        val text = if (total > 0) "$progress% • $speedStr" else "$speedStr downloaded"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(name, text, progress, total, ongoing = true))
    }

    private fun buildNotification(
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openIntent)
            .setOngoing(ongoing)
            .apply {
                if (total > 0) setProgress(100, progress, false)
                else if (ongoing) setProgress(0, 0, true)
                else setProgress(0, 0, false)
            }
            .build()
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
        serviceScope.cancel()
    }
}
