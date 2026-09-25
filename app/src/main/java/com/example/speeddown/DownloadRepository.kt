package com.example.speeddown

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.example.speeddown.data.DownloadItem
import com.example.speeddown.data.DownloadStatus
import com.example.speeddown.data.DownloadStore
import com.example.speeddown.service.DownloadService
import kotlinx.coroutines.flow.Flow
import java.io.File

class DownloadRepository(private val context: Context) {

    private val store = DownloadStore.getInstance(context)

    val allDownloads: Flow<List<DownloadItem>> = store.allDownloads

    suspend fun addDownload(url: String, fileName: String, threads: Int = 4): Long {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appExtDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val hasManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        val downloadsDir = if (hasManager || (publicDir.exists() && publicDir.canWrite())) {
            publicDir
        } else {
            appExtDir ?: publicDir
        }
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val sanitizedFileName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            .ifBlank { "download_${System.currentTimeMillis()}" }
        val filePath = "${downloadsDir.absolutePath}/$sanitizedFileName"

        val item = DownloadItem(
            url = url.trim(),
            fileName = sanitizedFileName,
            filePath = filePath,
            threads = threads,
            status = DownloadStatus.QUEUED
        )
        store.upsert(item)
        startServiceAction(DownloadService.ACTION_START, item.id)
        return item.id
    }

    suspend fun pauseDownload(item: DownloadItem) {
        store.updateStatus(item.id, DownloadStatus.PAUSED)
        startServiceAction(DownloadService.ACTION_PAUSE, item.id)
    }

    suspend fun resumeDownload(item: DownloadItem) {
        val file = File(item.filePath)
        val parent = file.parentFile
        val correctedItem = if (parent == null || !parent.canWrite()) {
            val appExtDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (appExtDir != null) {
                val newPath = "${appExtDir.absolutePath}/${item.fileName}"
                item.copy(filePath = newPath)
            } else item
        } else item

        store.upsert(correctedItem)
        store.updateStatus(correctedItem.id, DownloadStatus.DOWNLOADING)
        startServiceAction(DownloadService.ACTION_RESUME, correctedItem.id)
    }

    /**
     * Cancels download. Updates store immediately so UI updates with zero latency,
     * and sends ACTION_CANCEL to the background service to cancel active or queued coroutines.
     */
    suspend fun cancelDownload(item: DownloadItem) {
        store.updateStatus(item.id, DownloadStatus.CANCELLED)
        startServiceAction(DownloadService.ACTION_CANCEL, item.id)
    }

    suspend fun deleteDownload(item: DownloadItem, deleteFile: Boolean = true) {
        startServiceAction(DownloadService.ACTION_CANCEL, item.id)
        store.remove(item.id)
        if (deleteFile) {
            // Also remove physical file and any .part chunk files
            try {
                val file = File(item.filePath)
                if (file.exists()) file.delete()
                for (i in 0..120) {
                    val pf = File("${item.filePath}.part$i")
                    if (pf.exists()) pf.delete()
                }
                val sp = File("${item.filePath}.part")
                if (sp.exists()) sp.delete()
            } catch (_: Exception) {}
        }
    }

    suspend fun clearCompleted() = store.clearByStatus(DownloadStatus.COMPLETED)

    suspend fun clearAll() {
        store.clearByStatus(DownloadStatus.COMPLETED)
        store.clearByStatus(DownloadStatus.FAILED)
        store.clearByStatus(DownloadStatus.CANCELLED)
    }

    fun openFile(item: DownloadItem) {
        val file = File(item.filePath)
        if (!file.exists()) return
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
        } else Uri.fromFile(file)

        val mime = context.contentResolver.getType(uri) ?: "*/*"
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun startServiceAction(action: String, downloadId: Long) {
        try {
            val intent = Intent(context, DownloadService::class.java).apply {
                this.action = action
                putExtra(DownloadService.EXTRA_DOWNLOAD_ID, downloadId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
