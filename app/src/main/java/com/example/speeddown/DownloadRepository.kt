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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DownloadRepository(private val context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = DownloadStore.getInstance(context)

    val allDownloads: Flow<List<DownloadItem>> = store.allDownloads
    val settings: Flow<DownloadSettings> = store.settings

    suspend fun updateSettings(newSettings: DownloadSettings) = store.updateSettings(newSettings)

    companion object {
        fun generateUniqueFileName(
            desiredName: String,
            targetDir: File,
            existingNames: Set<String>
        ): String {
            val dotIndex = desiredName.lastIndexOf('.')
            val baseName = if (dotIndex > 0) desiredName.substring(0, dotIndex) else desiredName
            val extension = if (dotIndex > 0) desiredName.substring(dotIndex) else ""

            var candidate = desiredName
            var counter = 1

            while (
                existingNames.contains(candidate.lowercase()) ||
                File(targetDir, candidate).exists()
            ) {
                candidate = "$baseName ($counter)$extension"
                counter++
            }
            return candidate
        }

        fun isExactNumberedPartFileName(name: String): Boolean {
            return name.matches(Regex("^.+\\.part(\\d+)?$"))
        }

        fun isPathContainedInRoots(canonicalPath: String, allowedRoots: List<File>): Boolean {
            val file = File(canonicalPath)
            val canonicalFile = try { file.canonicalFile } catch (_: Exception) { file.absoluteFile }
            return allowedRoots.any { root ->
                val canonicalRoot = try { root.canonicalFile } catch (_: Exception) { root.absoluteFile }
                canonicalFile.startsWith(canonicalRoot)
            }
        }

        fun isProtectedPartFile(file: File, protectedFilePaths: Set<String>): Boolean {
            val normPath = file.absolutePath.replace('\\', '/')
            return protectedFilePaths.any { protectedPath ->
                val normProtected = protectedPath.replace('\\', '/')
                normPath == "$normProtected.part" ||
                    normPath.matches(Regex("^\\Q$normProtected\\E\\.part\\d+$")) ||
                    normPath == "$normProtected.video.tmp.part" ||
                    normPath.matches(Regex("^\\Q$normProtected\\E\\.video\\.tmp\\.part\\d+$")) ||
                    normPath == "$normProtected.audio.tmp.part" ||
                    normPath.matches(Regex("^\\Q$normProtected\\E\\.audio\\.tmp\\.part\\d+$"))
            }
        }
    }

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

    fun addDownloadAsync(
        url: String,
        fileName: String,
        threads: Int = 4,
        audioUrl: String? = null,
        originalUrl: String? = null
    ) {
        appScope.launch {
            addDownload(url, fileName, threads, audioUrl, originalUrl)
        }
    }

    fun addBatchDownloadsAsync(urls: List<String>, threads: Int = 16) {
        appScope.launch {
            addBatchDownloads(urls, threads)
        }
    }

    suspend fun addDownload(
        url: String,
        fileName: String,
        threads: Int = 4,
        audioUrl: String? = null,
        originalUrl: String? = null
    ): Long = withContext(NonCancellable) {
        val cleanUrl = url.trim()

        // 1. Auto-revive / Restart check:
        // If the user previously cancelled, paused, or had a failure on this exact URL or filename,
        // revive and restart it directly instead of creating a conflicting duplicate or stalling.
        val allDownloads = store.getAllDownloads()
        val existingItem = allDownloads.firstOrNull {
            it.url.equals(cleanUrl, ignoreCase = true) ||
            (!originalUrl.isNullOrBlank() && it.originalUrl.equals(originalUrl, ignoreCase = true)) ||
            (it.fileName.equals(fileName.trim(), ignoreCase = true) && (it.status == DownloadStatus.CANCELLED || it.status == DownloadStatus.FAILED))
        }

        if (existingItem != null) {
            when (existingItem.status) {
                DownloadStatus.CANCELLED, DownloadStatus.FAILED, DownloadStatus.PAUSED -> {
                    val settingsSnapshot = store.getSettingsSnapshot()
                    val fallbackThreads = if (threads <= 0) settingsSnapshot.defaultThreads else threads
                    val preservedThreads = if (existingItem.actualThreads != null && existingItem.actualThreads > 0) {
                        existingItem.actualThreads
                    } else if (existingItem.threads > 0) {
                        existingItem.threads
                    } else fallbackThreads

                    val updatedItem = existingItem.copy(
                        url = cleanUrl,
                        audioUrl = audioUrl ?: existingItem.audioUrl,
                        originalUrl = originalUrl ?: existingItem.originalUrl,
                        threads = preservedThreads,
                        status = DownloadStatus.DOWNLOADING,
                        errorMessage = null
                    )
                    store.upsert(updatedItem)
                    resumeDownload(updatedItem)
                    return@withContext updatedItem.id
                }
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                    return@withContext existingItem.id
                }
                DownloadStatus.COMPLETED -> {
                    // Let regular unique file creation proceed below if re-downloading a completed file
                }
            }
        }

        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appExtDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val hasManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        val baseDownloadsDir = if (hasManager || (publicDir.exists() && publicDir.canWrite())) {
            publicDir
        } else {
            appExtDir ?: publicDir
        }

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

        // Reserve a unique destination filename on insert. Check store records and the filesystem.
        val existingNames = store.getAllDownloads().map { it.fileName.lowercase() }.toSet()
        val uniqueFileName = generateUniqueFileName(sanitizedFileName, targetDir, existingNames)
        val filePath = "${targetDir.absolutePath}/$uniqueFileName"

        val item = DownloadItem(
            url = cleanUrl,
            fileName = uniqueFileName,
            filePath = filePath,
            threads = effectiveThreads,
            status = DownloadStatus.DOWNLOADING,
            category = category,
            isStreamable = isStreamable,
            isTorrent = isMagnet,
            isHls = isHls,
            audioUrl = audioUrl,
            originalUrl = originalUrl
        )
        store.upsert(item)
        startServiceAction(DownloadService.ACTION_START, item.id)
        item.id
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

    suspend fun streamInNothingPlayer(item: DownloadItem): Boolean {
        val streamServer = com.example.speeddown.engine.LocalStreamServer.getInstance(store)
        val streamUrl = streamServer.getStreamUrl(item.id, item.fileName)
        val uri = Uri.parse(streamUrl)
        return try {
            val intent = Intent().apply {
                setClassName("com.nothing.player", "com.nothing.player.ExoVideoPlayerActivity")
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
                    if (isExactNumberedPartFileName(file.name)) {
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
        // Protect DOWNLOADING, PAUSED, QUEUED, and FAILED records during orphan cleanup
        val baseProtectedPaths = store.getProtectedFilePaths()
        val protectedPaths = baseProtectedPaths.flatMap { path ->
            listOf(path, "$path.video.tmp", "$path.audio.tmp")
        }.toSet()
        if (speedDownDir.exists()) {
            speedDownDir.walkTopDown().forEach { file ->
                if (file.isFile && isExactNumberedPartFileName(file.name)) {
                    val isProtected = isProtectedPartFile(file, protectedPaths)
                    if (!isProtected) {
                        try {
                            if (file.delete()) deletedCount++
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        deletedCount
    }

    suspend fun addBatchDownloads(urls: List<String>, threads: Int = 16): Int = withContext(NonCancellable) {
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
        count
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

    suspend fun pauseDownload(item: DownloadItem) = withContext(NonCancellable) {
        store.updateStatus(item.id, DownloadStatus.PAUSED)
        startServiceAction(DownloadService.ACTION_PAUSE, item.id)
    }

    suspend fun resumeDownload(item: DownloadItem) = withContext(NonCancellable) {
        val file = File(item.filePath)
        val parent = file.parentFile
        val correctedItem = if (parent == null || !parent.canWrite()) {
            val appExtDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (appExtDir != null) {
                val newPath = "${appExtDir.absolutePath}/${item.fileName}"
                item.copy(filePath = newPath)
            } else item
        } else item

        // If resume changes the destination to app-external storage, move existing parts.
        // If the move fails, restart cleanly.
        if (correctedItem.filePath != item.filePath) {
            val oldBase = item.filePath
            val newBase = correctedItem.filePath
            val oldTarget = File(oldBase)
            val newTarget = File(newBase)
            var moveSuccess = true
            try {
                newTarget.parentFile?.mkdirs()
                if (oldTarget.exists()) {
                    if (!oldTarget.renameTo(newTarget)) {
                        oldTarget.copyTo(newTarget, overwrite = true)
                        oldTarget.delete()
                    }
                }
                val oldPart = File("$oldBase.part")
                val newPart = File("$newBase.part")
                if (oldPart.exists()) {
                    if (!oldPart.renameTo(newPart)) {
                        oldPart.copyTo(newPart, overwrite = true)
                        oldPart.delete()
                    }
                }
                val oldParent = oldTarget.parentFile
                if (oldParent != null && oldParent.exists()) {
                    val partFiles = oldParent.listFiles { f -> f.name.startsWith(oldTarget.name + ".part") } ?: emptyArray()
                    for (pf in partFiles) {
                        val suffix = pf.name.substringAfter(oldTarget.name)
                        val newPf = File(newTarget.parentFile, "${newTarget.name}$suffix")
                        if (!pf.renameTo(newPf)) {
                            pf.copyTo(newPf, overwrite = true)
                            pf.delete()
                        }
                    }
                }
                val oldHlsDir = File("${oldBase}_parts")
                val newHlsDir = File("${newBase}_parts")
                if (oldHlsDir.exists()) {
                    if (!oldHlsDir.renameTo(newHlsDir)) {
                        oldHlsDir.copyRecursively(newHlsDir, overwrite = true)
                        oldHlsDir.deleteRecursively()
                    }
                }
                val oldV = File("$oldBase.video.tmp")
                val newV = File("$newBase.video.tmp")
                if (oldV.exists()) {
                    if (!oldV.renameTo(newV)) {
                        oldV.copyTo(newV, overwrite = true)
                        oldV.delete()
                    }
                }
                val oldA = File("$oldBase.audio.tmp")
                val newA = File("$newBase.audio.tmp")
                if (oldA.exists()) {
                    if (!oldA.renameTo(newA)) {
                        oldA.copyTo(newA, overwrite = true)
                        oldA.delete()
                    }
                }
            } catch (_: Exception) {
                moveSuccess = false
            }

            if (!moveSuccess) {
                // Restart cleanly
                try {
                    newTarget.delete()
                    File("$newBase.part").delete()
                    newTarget.parentFile?.listFiles()?.forEach { f ->
                        if (f.name.startsWith(newTarget.name + ".part")) f.delete()
                    }
                    File("${newBase}_parts").deleteRecursively()
                    File("$newBase.video.tmp").delete()
                    File("$newBase.audio.tmp").delete()
                } catch (_: Exception) {}
                store.updateProgress(correctedItem.id, 0L, 0L, DownloadStatus.DOWNLOADING, emptyList())
            }
        }

        store.upsert(correctedItem)
        store.updateStatus(correctedItem.id, DownloadStatus.DOWNLOADING)
        startServiceAction(DownloadService.ACTION_RESUME, correctedItem.id)
    }

    suspend fun cancelDownload(item: DownloadItem) = withContext(NonCancellable) {
        store.updateStatus(item.id, DownloadStatus.CANCELLED)
        startServiceAction(DownloadService.ACTION_CANCEL, item.id)
    }

    suspend fun deleteDownload(item: DownloadItem, deleteFile: Boolean = true) = withContext(NonCancellable) {
        // 1. Mark cancelled first so UI immediately updates
        store.updateStatus(item.id, DownloadStatus.CANCELLED)

        // 2. Use cancel-and-join instead of arbitrary delay
        com.example.speeddown.engine.MultiThreadDownloader.cancelAndJoin(item.id)
        com.example.speeddown.engine.HlsDownloader.cancelAndJoin(item.id)
        startServiceAction(DownloadService.ACTION_CANCEL, item.id)

        // 3. Remove from store
        store.remove(item.id)

        // 4. Delete target, HTTP parts, HLS _parts, .video.tmp, .audio.tmp, and .torrent sidecar
        if (deleteFile) {
            try {
                val file = File(item.filePath)
                if (file.exists()) file.delete()

                val singlePart = File("${item.filePath}.part")
                if (singlePart.exists()) singlePart.delete()

                file.parentFile?.listFiles()?.forEach { f ->
                    if (f.name.startsWith(file.name + ".part")) {
                        f.delete()
                    }
                }

                val hlsDir = File("${item.filePath}_parts")
                if (hlsDir.exists()) hlsDir.deleteRecursively()

                val vTmp = File("${item.filePath}.video.tmp")
                if (vTmp.exists()) vTmp.delete()

                val aTmp = File("${item.filePath}.audio.tmp")
                if (aTmp.exists()) aTmp.delete()

                val torrentSidecar = File("${item.filePath}.torrent")
                if (torrentSidecar.exists()) torrentSidecar.delete()
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

    fun getAllowedDownloadRoots(): List<File> {
        val roots = mutableListOf<File>()
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (publicDir != null) roots.add(publicDir)
        val appExtDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (appExtDir != null) roots.add(appExtDir)
        return roots
    }

    fun getShareableUri(file: File): Uri? {
        val canonicalFile = try { file.canonicalFile } catch (_: Exception) { file.absoluteFile }
        if (!isPathContainedInRoots(canonicalFile.path, getAllowedDownloadRoots())) {
            return null
        }
        return try {
            androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", canonicalFile
            )
        } catch (_: Exception) {
            null
        }
    }

    fun openInNothingPlayer(item: DownloadItem): Boolean {
        val file = File(item.filePath)
        if (!file.exists()) return false

        val uri = getShareableUri(file) ?: return false
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
            launchNothingPlayerApp()
        }
    }

    fun openFile(item: DownloadItem) {
        val file = File(item.filePath)
        if (!file.exists()) {
            android.widget.Toast.makeText(context, "File does not exist", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // Smart route to Nothing Player if preferred and media type
        val snapshot = kotlinx.coroutines.runBlocking {
            try { store.getSettingsSnapshot() } catch (_: Exception) { DownloadSettings() }
        }
        if (snapshot.preferNothingPlayer && (item.category == "Videos" || item.category == "Music") && isNothingPlayerInstalled()) {
            if (openInNothingPlayer(item)) return
        }

        val uri = getShareableUri(file)
        if (uri == null) {
            android.widget.Toast.makeText(context, "Security error: File path not within allowed download directories", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) { null } ?: "*/*"

        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, "No app found to open this file", android.widget.Toast.LENGTH_SHORT).show()
        }
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
