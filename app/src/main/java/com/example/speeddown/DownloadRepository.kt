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

        val cleanUrl = url.trim()
        val isMagnet = com.example.speeddown.engine.TorrentEngine.isMagnet(cleanUrl)
        val isHls = cleanUrl.contains(".m3u8", ignoreCase = true)
        val magnetMetadata = if (isMagnet) com.example.speeddown.engine.TorrentEngine.parseMagnet(cleanUrl) else null

        val sanitizedFileName = if (isMagnet && magnetMetadata != null) {
            magnetMetadata.displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        } else {
            fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                .ifBlank { "download_${System.currentTimeMillis()}" }
        }

        val settingsSnapshot = store.getSettingsSnapshot()
        val effectiveThreads = if (threads <= 0) settingsSnapshot.defaultThreads else threads
        val category = when {
            isMagnet -> "Torrents"
            isHls -> "Videos"
            else -> determineCategory(sanitizedFileName)
        }
        val isStreamable = category == "Videos" || category == "Music" || isHls || isMagnet

        val targetDir = if (settingsSnapshot.autoCategorize) {
            File(baseDownloadsDir, "SpeedDown/$category")
        } else {
            File(baseDownloadsDir, "SpeedDown")
        }
        if (!targetDir.exists()) targetDir.mkdirs()

        val filePath = "${targetDir.absolutePath}/$sanitizedFileName"

        val item = DownloadItem(
            url = cleanUrl,
            fileName = sanitizedFileName,
            filePath = filePath,
            threads = effectiveThreads,
            status = DownloadStatus.QUEUED,
            category = category,
            isStreamable = isStreamable,
            isTorrent = isMagnet,
            isHls = isHls
        )
        store.upsert(item)
        startServiceAction(DownloadService.ACTION_START, item.id)
        return item.id
    }

    fun checkDuplicateFile(fileName: String): File? {
        val sanitized = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appExtDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val candidateDirs = listOfNotNull(
            File(publicDir, "SpeedDown/Videos"),
            File(publicDir, "SpeedDown/Music"),
            File(publicDir, "SpeedDown/Archives"),
            File(publicDir, "SpeedDown/Documents"),
            File(publicDir, "SpeedDown/Files"),
            File(publicDir, "SpeedDown/Torrents"),
            File(publicDir, "SpeedDown"),
            publicDir,
            appExtDir
        )
        return candidateDirs.map { File(it, sanitized) }.firstOrNull { it.exists() && it.length() > 0 }
    }

    suspend fun refreshDownloadUrl(downloadId: Long, newUrl: String) {
        store.updateUrl(downloadId, newUrl.trim())
        store.updateStatus(downloadId, DownloadStatus.DOWNLOADING)
        startServiceAction(DownloadService.ACTION_RESUME, downloadId)
    }

    fun streamInNothingPlayer(item: DownloadItem): Boolean {
        val streamServer = com.example.speeddown.engine.LocalStreamServer.getInstance(store)
        val streamUrl = streamServer.getStreamUrl(item.id, item.fileName)
        val uri = Uri.parse(streamUrl)
        return try {
            val intent = Intent().apply {
                setClassName("com.nothing.player", "com.nothing.player.ExoVideoPlayerActivity")
                putExtra("path", streamUrl)
                putExtra("video_path", streamUrl)
                putExtra("title", item.fileName)
                putExtra("video_title", item.fileName)
                putExtra("contentUri", streamUrl)
                putExtra("video_uri", streamUrl)
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val genericIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(genericIntent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    data class StorageBreakdown(
        val videoBytes: Long,
        val musicBytes: Long,
        val archiveBytes: Long,
        val docBytes: Long,
        val otherBytes: Long,
        val totalBytes: Long,
        val orphanedPartBytes: Long
    )

    suspend fun calculateStorageBreakdown(): StorageBreakdown = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val speedDownDir = File(publicDir, "SpeedDown")
        var vBytes = 0L
        var mBytes = 0L
        var aBytes = 0L
        var dBytes = 0L
        var oBytes = 0L
        var partBytes = 0L

        if (speedDownDir.exists()) {
            speedDownDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val len = file.length()
                    if (file.name.contains(".part")) {
                        partBytes += len
                    } else {
                        when (determineCategory(file.name)) {
                            "Videos" -> vBytes += len
                            "Music" -> mBytes += len
                            "Archives" -> aBytes += len
                            "Documents" -> dBytes += len
                            else -> oBytes += len
                        }
                    }
                }
            }
        }
        val total = vBytes + mBytes + aBytes + dBytes + oBytes + partBytes
        StorageBreakdown(vBytes, mBytes, aBytes, dBytes, oBytes, total, partBytes)
    }

    suspend fun cleanupOrphanedParts(): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val speedDownDir = File(publicDir, "SpeedDown")
        var deletedCount = 0
        val activeIds = store.getActiveDownloads().map { it.filePath }
        if (speedDownDir.exists()) {
            speedDownDir.walkTopDown().forEach { file ->
                if (file.isFile && file.name.contains(".part")) {
                    val isActive = activeIds.any { file.absolutePath.startsWith(it) }
                    if (!isActive) {
                        try {
                            if (file.delete()) deletedCount++
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        deletedCount
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

    fun isNothingPlayerInstalled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    "com.nothing.player",
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo("com.nothing.player", 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun launchNothingPlayerApp(): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.nothing.player")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun getShareableUri(file: File): Uri {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
            } else {
                Uri.fromFile(file)
            }
        } catch (_: Exception) {
            Uri.fromFile(file)
        }
    }

    fun openInNothingPlayer(item: DownloadItem): Boolean {
        val file = File(item.filePath)
        if (!file.exists()) return false

        val uri = getShareableUri(file)
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) { null } ?: when (item.category) {
            "Videos" -> "video/*"
            "Music" -> "audio/*"
            else -> "*/*"
        }

        return try {
            val intent = Intent().apply {
                setClassName("com.nothing.player", "com.nothing.player.ExoVideoPlayerActivity")
                putExtra("path", item.filePath)
                putExtra("video_path", item.filePath)
                putExtra("title", item.fileName)
                putExtra("video_title", item.fileName)
                putExtra("contentUri", uri.toString())
                putExtra("video_uri", uri.toString())
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // Fallback to launcher intent
            launchNothingPlayerApp()
        }
    }

    fun openFile(item: DownloadItem) {
        val file = File(item.filePath)
        if (!file.exists()) return

        // Smart route to Nothing Player if preferred and media type
        val snapshot = kotlinx.coroutines.runBlocking {
            try { store.getSettingsSnapshot() } catch (_: Exception) { DownloadSettings() }
        }
        if (snapshot.preferNothingPlayer && (item.category == "Videos" || item.category == "Music") && isNothingPlayerInstalled()) {
            if (openInNothingPlayer(item)) return
        }

        val uri = getShareableUri(file)
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) { null } ?: "*/*"

        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {}
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
