package com.example.speeddown

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.example.speeddown.data.DownloadItem
import com.example.speeddown.data.DownloadSettings
import com.example.speeddown.data.DownloadStatus
import com.example.speeddown.data.DownloadStore
import com.example.speeddown.service.DownloadService
import kotlinx.coroutines.flow.Flow
import java.io.File

class DownloadRepository(private val context: Context) {

    private val store = DownloadStore.getInstance(context)

    val allDownloads: Flow<List<DownloadItem>> = store.allDownloads
    val settings: Flow<DownloadSettings> = store.settings

    suspend fun updateSettings(newSettings: DownloadSettings) = store.updateSettings(newSettings)

    fun determineCategory(fileName: String): String {
        val ext = fileName.substringAfterLast(".", "").lowercase()
        return when (ext) {
            "mp4", "mkv", "webm", "avi", "mov", "flv", "3gp", "ts" -> "Videos"
            "mp3", "m4a", "wav", "flac", "aac", "ogg", "opus" -> "Music"
            "zip", "rar", "7z", "tar", "gz", "apk", "xapk", "iso", "bin" -> "Archives"
            "pdf", "epub", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt" -> "Documents"
            else -> "Files"
        }
    }

    suspend fun addDownload(url: String, fileName: String, threads: Int = 4): Long {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appExtDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val hasManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        val baseDownloadsDir = if (hasManager || (publicDir.exists() && publicDir.canWrite())) {
            publicDir
        } else {
            appExtDir ?: publicDir
        }

        val sanitizedFileName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            .ifBlank { "download_${System.currentTimeMillis()}" }

        val settingsSnapshot = store.getSettingsSnapshot()
        val category = determineCategory(sanitizedFileName)
        val targetDir = if (settingsSnapshot.autoCategorize) {
            File(baseDownloadsDir, "SpeedDown/$category")
        } else {
            File(baseDownloadsDir, "SpeedDown")
        }
        if (!targetDir.exists()) targetDir.mkdirs()

        val filePath = "${targetDir.absolutePath}/$sanitizedFileName"

        val item = DownloadItem(
            url = url.trim(),
            fileName = sanitizedFileName,
            filePath = filePath,
            threads = threads,
            status = DownloadStatus.QUEUED,
            category = category
        )
        store.upsert(item)
        startServiceAction(DownloadService.ACTION_START, item.id)
        return item.id
    }

    suspend fun addBatchDownloads(urls: List<String>, threads: Int = 16): Int {
        var count = 0
        for (u in urls) {
            val clean = u.trim()
            if (clean.startsWith("http://") || clean.startsWith("https://")) {
                val candidateName = clean.substringAfterLast("/").substringBefore("?").substringBefore("#")
                    .ifBlank { "download_${System.currentTimeMillis()}_$count" }
                addDownload(clean, candidateName, threads)
                count++
            }
        }
        return count
    }

    suspend fun calculateChecksums(filePath: String): Pair<String, String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists()) return@withContext Pair("File not found", "File not found")
        try {
            val md5 = java.security.MessageDigest.getInstance("MD5")
            val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(65536)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    md5.update(buf, 0, read)
                    sha256.update(buf, 0, read)
                }
            }
            fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
            Pair(bytesToHex(md5.digest()), bytesToHex(sha256.digest()))
        } catch (e: Exception) {
            Pair("Error: ${e.message}", "Error: ${e.message}")
        }
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
        // 1. Mark cancelled first so UI and coroutines immediately recognize termination
        store.updateStatus(item.id, DownloadStatus.CANCELLED)
        // 2. Send ACTION_CANCEL to DownloadService to terminate sockets and threads
        startServiceAction(DownloadService.ACTION_CANCEL, item.id)
        // 3. Brief delay to allow network threads to abort and release file streams
        kotlinx.coroutines.delay(120)
        // 4. Remove from store
        store.remove(item.id)
        // 5. Delete partial chunk files and final file from disk
        if (deleteFile) {
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
            if (action == DownloadService.ACTION_START || action == DownloadService.ACTION_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
