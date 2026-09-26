package com.example.speeddown.engine

import android.util.Log
import com.example.speeddown.data.DownloadItem
import com.example.speeddown.data.DownloadStatus
import com.example.speeddown.data.DownloadStore
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class HlsStreamVariant(
    val resolution: String,
    val bandwidth: Long,
    val url: String,
    val label: String
)

/**
 * High-performance M3U8/HLS stream segment parser and concurrent downloader.
 * Fetches HLS stream playlists, downloads .ts segments, and merges them into a clean video file.
 */
class HlsDownloader(
    private val okHttpClient: OkHttpClient,
    private val store: DownloadStore
) {
    companion object {
        private const val TAG = "HlsDownloader"
    }

    fun parseVariantStreams(url: String): List<HlsStreamVariant> {
        val text = fetchText(url) ?: return emptyList()
        if (!text.contains("#EXT-X-STREAM-INF")) return emptyList()

        val variants = mutableListOf<HlsStreamVariant>()
        val lines = text.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val res = Regex("RESOLUTION=([\\dx]+)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1) ?: ""
                val nextLine = lines.getOrNull(i + 1)?.trim()
                if (nextLine != null && !nextLine.startsWith("#")) {
                    val fullUrl = resolveUrl(url, nextLine)
                    val label = when {
                        res.contains("1920") || res.contains("1080") -> "1080p Full HD"
                        res.contains("1280") || res.contains("720") -> "720p HD"
                        res.contains("854") || res.contains("480") -> "480p SD"
                        res.contains("640") || res.contains("360") -> "360p"
                        res.isNotEmpty() -> res
                        bw > 3_000_000 -> "High Quality (${bw / 1000} kbps)"
                        bw > 1_000_000 -> "Medium Quality (${bw / 1000} kbps)"
                        bw > 0 -> "Low Quality (${bw / 1000} kbps)"
                        else -> "Stream Variant"
                    }
                    variants.add(
                        HlsStreamVariant(
                            resolution = if (res.isNotEmpty()) res else "${bw / 1000} kbps",
                            bandwidth = bw,
                            url = fullUrl,
                            label = label
                        )
                    )
                }
            }
        }
        return variants.sortedByDescending { it.bandwidth }
    }

    private val cancelFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val pauseFlags = ConcurrentHashMap<Long, AtomicBoolean>()

    fun startHlsDownload(
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
                performHlsDownload(item, cancelFlag, pauseFlag, onProgress, onComplete, onError)
            } catch (e: Exception) {
                if (!cancelFlag.get() && !pauseFlag.get()) {
                    Log.e(TAG, "HLS download error", e)
                    onError("HLS Error: ${e.localizedMessage ?: "Failed to download stream"}")
                }
            } finally {
                cancelFlags.remove(item.id)
                pauseFlags.remove(item.id)
            }
        }
    }

    fun pauseHls(downloadId: Long) {
        pauseFlags[downloadId]?.set(true)
    }

    fun cancelHls(downloadId: Long) {
        cancelFlags[downloadId]?.set(true)
    }

    private suspend fun performHlsDownload(
        item: DownloadItem,
        cancelFlag: AtomicBoolean,
        pauseFlag: AtomicBoolean,
        onProgress: (Long, Long, List<Float>) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val targetFile = File(item.filePath)
        targetFile.parentFile?.mkdirs()

        // 1. Fetch playlist content
        val playlistText = fetchText(item.url)
        if (playlistText == null) {
            onError("Unable to fetch M3U8 playlist")
            return@withContext
        }

        // 2. Resolve Master playlist vs Media playlist
        var mediaPlaylistUrl = item.url
        var mediaPlaylistText = playlistText

        if (playlistText.contains("#EXT-X-STREAM-INF")) {
            // Master playlist with multiple bitrates; choose highest bandwidth
            val highestStreamUrl = parseHighestStream(item.url, playlistText)
            if (highestStreamUrl != null) {
                mediaPlaylistUrl = highestStreamUrl
                mediaPlaylistText = fetchText(highestStreamUrl) ?: playlistText
            }
        }

        // 3. Extract all segment URLs
        val segmentUrls = parseSegments(mediaPlaylistUrl, mediaPlaylistText)
        if (segmentUrls.isEmpty()) {
            onError("No stream segments found in M3U8 playlist")
            return@withContext
        }

        val totalSegments = segmentUrls.size
        val partFolder = File(item.filePath + "_parts")
        partFolder.mkdirs()

        val downloadedBytes = AtomicLong(0L)
        val completedSegments = AtomicLong(0L)
        var lastSpeedTime = System.currentTimeMillis()
        var lastSpeedBytes = 0L

        // Track completed segments on resume
        segmentUrls.forEachIndexed { idx, _ ->
            val segFile = File(partFolder, "seg_$idx.ts")
            if (segFile.exists() && segFile.length() > 0) {
                downloadedBytes.addAndGet(segFile.length())
                completedSegments.incrementAndGet()
            }
        }

        // Segment downloader with worker pool of 4 concurrent streams
        val concurrency = 4
        val chunks = segmentUrls.chunked(concurrency)

        for (chunk in chunks) {
            if (cancelFlag.get()) {
                store.updateStatus(item.id, DownloadStatus.CANCELLED)
                partFolder.deleteRecursively()
                return@withContext
            }
            if (pauseFlag.get()) {
                store.updateStatus(item.id, DownloadStatus.PAUSED)
                return@withContext
            }

            coroutineScope {
                chunk.map { (index, segUrl) ->
                    async(Dispatchers.IO) {
                        val segFile = File(partFolder, "seg_$index.ts")
                        if (!segFile.exists() || segFile.length() == 0L) {
                            val bytes = downloadSegment(segUrl, segFile)
                            if (bytes > 0) {
                                downloadedBytes.addAndGet(bytes)
                                completedSegments.incrementAndGet()
                            }
                        }

                        val now = System.currentTimeMillis()
                        val elapsed = (now - lastSpeedTime).coerceAtLeast(1)
                        val currBytes = downloadedBytes.get()
                        val speed = ((currBytes - lastSpeedBytes) * 1000L) / elapsed
                        if (elapsed >= 500) {
                            lastSpeedTime = now
                            lastSpeedBytes = currBytes
                        }

                        val progRatio = completedSegments.get().toFloat() / totalSegments.toFloat()
                        onProgress(currBytes, speed, listOf(progRatio))
                    }
                }.awaitAll()
            }
        }

        if (cancelFlag.get() || pauseFlag.get()) return@withContext

        // Merge segments into destination video file
        val outputStream = FileOutputStream(targetFile)
        try {
            for (i in segmentUrls.indices) {
                val segFile = File(partFolder, "seg_$i.ts")
                if (segFile.exists()) {
                    segFile.inputStream().use { input ->
                        input.copyTo(outputStream)
                    }
                }
            }
            outputStream.flush()
        } finally {
            outputStream.close()
        }

        // Clean up temporary segment files
        partFolder.deleteRecursively()
        store.updateTotalSize(item.id, targetFile.length())
        store.markCompleted(item.id)
        onComplete()
    }

    private fun fetchText(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", MultiThreadDownloader.BROWSER_USER_AGENT)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseHighestStream(baseUrl: String, text: String): String? {
        var highestUrl: String? = null
        var maxBandwidth = -1L
        val lines = text.lines()

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bwMatch = Regex("BANDWIDTH=(\\d+)").find(line)
                val bw = bwMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val nextLine = lines.getOrNull(i + 1)?.trim()
                if (nextLine != null && !nextLine.startsWith("#") && bw > maxBandwidth) {
                    maxBandwidth = bw
                    highestUrl = resolveUrl(baseUrl, nextLine)
                }
            }
        }
        return highestUrl
    }

    private fun parseSegments(baseUrl: String, text: String): List<Pair<Int, String>> {
        val list = mutableListOf<Pair<Int, String>>()
        var idx = 0
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                val fullUrl = resolveUrl(baseUrl, trimmed)
                list.add(idx to fullUrl)
                idx++
            }
        }
        return list
    }

    private fun resolveUrl(base: String, path: String): String {
        return try {
            if (path.startsWith("http://") || path.startsWith("https://")) path
            else URI.create(base).resolve(path).toString()
        } catch (_: Exception) {
            path
        }
    }

    private fun downloadSegment(url: String, dest: File): Long {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", MultiThreadDownloader.BROWSER_USER_AGENT)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return 0L
                val body = resp.body ?: return 0L
                val bytes = body.bytes()
                dest.writeBytes(bytes)
                bytes.size.toLong()
            }
        } catch (_: Exception) {
            0L
        }
    }
}
