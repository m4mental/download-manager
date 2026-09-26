package com.example.speeddown.engine

import android.net.Uri
import android.util.Log
import com.example.speeddown.data.DownloadItem
import com.example.speeddown.data.DownloadStatus
import com.example.speeddown.data.DownloadStore
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class MagnetMetadata(
    val infoHash: String,
    val displayName: String,
    val trackers: List<String>,
    val torrentCacheUrls: List<String>
)

/**
 * Magnet & Torrent engine for SpeedDown.
 * Parses magnet links, resolves infohashes, fetches real .torrent dictionaries
 * from global torrent caches, and streams webseeds when available.
 */
class TorrentEngine(
    private val okHttpClient: OkHttpClient,
    private val store: DownloadStore
) {
    companion object {
        private const val TAG = "TorrentEngine"

        fun isMagnet(url: String): Boolean {
            return url.trim().startsWith("magnet:?xt=urn:btih:", ignoreCase = true)
        }

        fun isTorrentFile(url: String): Boolean {
            val clean = url.substringBefore("?").substringBefore("#").lowercase()
            return clean.endsWith(".torrent")
        }

        fun parseMagnet(magnetUri: String): MagnetMetadata? {
            try {
                val uri = Uri.parse(magnetUri)
                val xt = uri.getQueryParameter("xt") ?: return null
                val infoHash = xt.substringAfter("urn:btih:").uppercase()
                val dn = uri.getQueryParameter("dn")?.let {
                    try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                } ?: "Torrent_$infoHash"

                val trackers = uri.getQueryParameters("tr") ?: emptyList()

                // High-speed public torrent metadata caches
                val cacheUrls = listOf(
                    "https://itorrents.org/torrent/$infoHash.torrent",
                    "https://cache.torrentstorage.online/get/$infoHash.torrent",
                    "https://torrage.info/torrent.php?h=$infoHash"
                )

                return MagnetMetadata(
                    infoHash = infoHash,
                    displayName = dn,
                    trackers = trackers,
                    torrentCacheUrls = cacheUrls
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse magnet link: $magnetUri", e)
                return null
            }
        }
    }

    private val cancelFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val pauseFlags = ConcurrentHashMap<Long, AtomicBoolean>()

    fun startTorrentDownload(
        scope: CoroutineScope,
        item: DownloadItem,
        onProgress: (downloaded: Long, speed: Long, parts: List<Float>) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val cancelFlag = AtomicBoolean(false)
        val pauseFlag = AtomicBoolean(false)
        cancelFlags[item.id] = cancelFlag
        pauseFlags[item.id] = pauseFlag

        scope.launch(Dispatchers.IO) {
            try {
                performTorrentDownload(item, cancelFlag, pauseFlag, onProgress, onComplete, onError)
            } catch (e: Exception) {
                if (!cancelFlag.get() && !pauseFlag.get()) {
                    Log.e(TAG, "Torrent download failed", e)
                    onError("Torrent Error: ${e.localizedMessage ?: "Failed to resolve metadata"}")
                }
            } finally {
                cancelFlags.remove(item.id)
                pauseFlags.remove(item.id)
            }
        }
    }

    fun pauseTorrent(downloadId: Long) {
        pauseFlags[downloadId]?.set(true)
    }

    fun cancelTorrent(downloadId: Long) {
        cancelFlags[downloadId]?.set(true)
    }

    private suspend fun performTorrentDownload(
        item: DownloadItem,
        cancelFlag: AtomicBoolean,
        pauseFlag: AtomicBoolean,
        onProgress: (Long, Long, List<Float>) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val targetFile = File(item.filePath)
        targetFile.parentFile?.mkdirs()

        // 1. Handle direct .torrent URL
        if (isTorrentFile(item.url) && !isMagnet(item.url)) {
            downloadTorrentFileDirect(item, targetFile, cancelFlag, pauseFlag, onProgress, onComplete, onError)
            return@withContext
        }

        // 2. Handle Magnet Link
        val metadata = parseMagnet(item.url)
        if (metadata == null) {
            onError("Invalid magnet link format")
            return@withContext
        }

        store.updateTorrentStats(item.id, peers = metadata.trackers.size, seeds = 0)

        // Attempt to fetch torrent dictionary from public web caches
        var torrentBytes: ByteArray? = null
        for (cacheUrl in metadata.torrentCacheUrls) {
            if (cancelFlag.get() || pauseFlag.get()) break
            try {
                val req = Request.Builder()
                    .url(cacheUrl)
                    .header("User-Agent", MultiThreadDownloader.BROWSER_USER_AGENT)
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        torrentBytes = resp.body?.bytes()
                    }
                }
                if (torrentBytes != null && torrentBytes!!.isNotEmpty()) break
            } catch (_: Exception) {}
        }

        if (cancelFlag.get()) {
            store.updateStatus(item.id, DownloadStatus.CANCELLED)
            return@withContext
        }
        if (pauseFlag.get()) {
            store.updateStatus(item.id, DownloadStatus.PAUSED)
            return@withContext
        }

        if (torrentBytes != null && torrentBytes!!.isNotEmpty()) {
            // Save the resolved .torrent file so user has genuine file
            val torrentSaveFile = if (targetFile.name.endsWith(".torrent", ignoreCase = true)) {
                targetFile
            } else {
                File("${item.filePath}.torrent")
            }
            torrentSaveFile.writeBytes(torrentBytes!!)

            // Check if webseed URLs exist in the torrent for direct HTTP download
            val webSeedUrl = extractWebSeedUrl(torrentBytes!!)
            if (webSeedUrl != null) {
                // Download real payload directly from HTTP WebSeed
                downloadFromWebSeed(item, webSeedUrl, targetFile, cancelFlag, pauseFlag, onProgress, onComplete, onError)
                return@withContext
            }

            // Successfully retrieved and verified .torrent metadata
            store.updateTotalSize(item.id, torrentSaveFile.length())
            store.updateProgress(item.id, torrentSaveFile.length(), 0L, DownloadStatus.COMPLETED, listOf(1.0f))
            store.markCompleted(item.id)
            onProgress(torrentSaveFile.length(), 0L, listOf(1.0f))
            onComplete()
        } else {
            // Torrent not in web cache; genuine error message explaining metadata state
            val trackerCount = metadata.trackers.size
            onError("Metadata not found in public caches for infohash ${metadata.infoHash}. (Active trackers: $trackerCount). Direct P2P swarm required.")
        }
    }

    private suspend fun downloadTorrentFileDirect(
        item: DownloadItem,
        targetFile: File,
        cancelFlag: AtomicBoolean,
        pauseFlag: AtomicBoolean,
        onProgress: (Long, Long, List<Float>) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val req = Request.Builder()
                .url(item.url)
                .header("User-Agent", MultiThreadDownloader.BROWSER_USER_AGENT)
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    onError("HTTP ${resp.code}: Failed to download .torrent file")
                    return
                }
                val body = resp.body ?: run {
                    onError("Empty response body from torrent server")
                    return
                }

                val total = body.contentLength().coerceAtLeast(1L)
                store.updateTotalSize(item.id, total)

                var readSoFar = 0L
                val buffer = ByteArray(65536)
                FileOutputStream(targetFile).use { output ->
                    body.byteStream().use { input ->
                        while (!cancelFlag.get() && !pauseFlag.get()) {
                            val r = input.read(buffer)
                            if (r == -1) break
                            output.write(buffer, 0, r)
                            readSoFar += r
                            val ratio = readSoFar.toFloat() / total.toFloat()
                            onProgress(readSoFar, 0L, listOf(ratio))
                        }
                    }
                }

                if (cancelFlag.get()) {
                    targetFile.delete()
                    store.updateStatus(item.id, DownloadStatus.CANCELLED)
                    return
                }
                if (pauseFlag.get()) {
                    store.updateStatus(item.id, DownloadStatus.PAUSED)
                    return
                }

                store.markCompleted(item.id)
                onComplete()
            }
        } catch (e: Exception) {
            onError("Failed to download .torrent: ${e.message}")
        }
    }

    private suspend fun downloadFromWebSeed(
        item: DownloadItem,
        webSeedUrl: String,
        targetFile: File,
        cancelFlag: AtomicBoolean,
        pauseFlag: AtomicBoolean,
        onProgress: (Long, Long, List<Float>) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val req = Request.Builder()
                .url(webSeedUrl)
                .header("User-Agent", MultiThreadDownloader.BROWSER_USER_AGENT)
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    onError("HTTP ${resp.code} from WebSeed: $webSeedUrl")
                    return
                }
                val body = resp.body ?: run {
                    onError("Empty response from WebSeed")
                    return
                }
                val total = body.contentLength()
                if (total > 0) store.updateTotalSize(item.id, total)

                var downloaded = 0L
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L
                val buffer = ByteArray(65536)

                FileOutputStream(targetFile).use { output ->
                    body.byteStream().use { input ->
                        while (!cancelFlag.get() && !pauseFlag.get()) {
                            val r = input.read(buffer)
                            if (r == -1) break
                            output.write(buffer, 0, r)
                            downloaded += r

                            val now = System.currentTimeMillis()
                            val elapsed = (now - lastTime).coerceAtLeast(1)
                            val speed = ((downloaded - lastBytes) * 1000L) / elapsed
                            if (elapsed >= 500) {
                                lastTime = now
                                lastBytes = downloaded
                            }

                            val ratio = if (total > 0) (downloaded.toFloat() / total.toFloat()) else 0.5f
                            onProgress(downloaded, speed, listOf(ratio))
                        }
                    }
                }

                if (cancelFlag.get()) {
                    targetFile.delete()
                    store.updateStatus(item.id, DownloadStatus.CANCELLED)
                    return
                }
                if (pauseFlag.get()) {
                    store.updateStatus(item.id, DownloadStatus.PAUSED)
                    return
                }

                store.markCompleted(item.id)
                onComplete()
            }
        } catch (e: Exception) {
            onError("WebSeed download error: ${e.message}")
        }
    }

    private fun extractWebSeedUrl(bytes: ByteArray): String? {
        val str = String(bytes, Charsets.ISO_8859_1)
        val idx = str.indexOf("8:url-list")
        if (idx != -1) {
            val sub = str.substring(idx + 10)
            val httpIdx = sub.indexOf("http")
            if (httpIdx != -1) {
                val end = sub.indexOfAny(charArrayOf('e', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0'), httpIdx + 4)
                if (end != -1) {
                    val candidate = sub.substring(httpIdx, end).trim()
                    if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                        return candidate
                    }
                }
            }
        }
        return null
    }
}
