package com.example.speeddown.engine

import android.net.Uri
import android.util.Log
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.example.speeddown.data.DownloadItem
import com.example.speeddown.data.DownloadStatus
import com.example.speeddown.data.DownloadStore
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class HlsStreamVariant(
    val resolution: String,
    val bandwidth: Long,
    val url: String,
    val label: String
)

data class HlsSegment(
    val index: Int,
    val url: String,
    val keyUrl: String? = null,
    val iv: ByteArray? = null
)

/**
 * High-performance, memory-safe M3U8/HLS stream downloader powered by official
 * AndroidX Media3 / ExoPlayer HLS playlist parsing engine.
 *
 * Supports AES-128 hardware-accelerated decryption, progressive 64KB disk streaming
 * without heap buffer accumulation (preventing OutOfMemoryError), and seamless resumption.
 */
class HlsDownloader(
    private val okHttpClient: OkHttpClient,
    private val store: DownloadStore
) {
    companion object {
        private const val TAG = "HlsDownloader"
        private const val BUFFER_SIZE = 65536
    }

    private val media3PlaylistParser by lazy { HlsPlaylistParser() }

    /**
     * Parses all adaptive stream variants using AndroidX Media3 HlsPlaylistParser.
     */
    fun parseVariantStreams(url: String): List<HlsStreamVariant> {
        val text = fetchText(url) ?: return emptyList()
        val uri = Uri.parse(url)

        // 1. Try official Media3 HlsPlaylistParser
        try {
            val playlist = media3PlaylistParser.parse(uri, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            if (playlist is HlsMultivariantPlaylist && playlist.variants.isNotEmpty()) {
                val variants = playlist.variants.map { variant ->
                    val width = variant.format.width
                    val height = variant.format.height
                    val bw = variant.format.bitrate.toLong().coerceAtLeast(0L)
                    val res = if (width > 0 && height > 0) "${width}x${height}" else ""
                    val fullUrl = variant.url.toString()

                    val label = when {
                        height >= 1080 -> "1080p Full HD"
                        height >= 720 -> "720p HD"
                        height >= 480 -> "480p SD"
                        height >= 360 -> "360p"
                        res.isNotEmpty() -> res
                        bw > 3_000_000 -> "High Quality (${bw / 1000} kbps)"
                        bw > 1_000_000 -> "Medium Quality (${bw / 1000} kbps)"
                        bw > 0 -> "Low Quality (${bw / 1000} kbps)"
                        else -> "Stream Variant"
                    }

                    HlsStreamVariant(
                        resolution = if (res.isNotEmpty()) res else "${bw / 1000} kbps",
                        bandwidth = bw,
                        url = fullUrl,
                        label = label
                    )
                }
                return variants.sortedByDescending { it.bandwidth }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Media3 HLS multivariant parse fallback: ${e.message}")
        }

        // 2. Fallback regex parser
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
            val highestStreamUrl = parseHighestStream(item.url, playlistText)
            if (highestStreamUrl != null) {
                mediaPlaylistUrl = highestStreamUrl
                mediaPlaylistText = fetchText(highestStreamUrl) ?: playlistText
            }
        }

        // 3. Extract all segment URLs and encryption keys using Media3 parser
        val segments = parseSegmentsWithMedia3(mediaPlaylistUrl, mediaPlaylistText)
        if (segments.isEmpty()) {
            onError("No stream segments found in M3U8 playlist")
            return@withContext
        }

        // Fetch any AES-128 encryption keys needed
        val keyCache = ConcurrentHashMap<String, ByteArray>()
        val keyUrls = segments.mapNotNull { it.keyUrl }.distinct()
        for (kUrl in keyUrls) {
            val keyBytes = fetchBinary(kUrl)
            if (keyBytes != null && keyBytes.isNotEmpty()) {
                keyCache[kUrl] = keyBytes
            }
        }

        val totalSegments = segments.size
        val partFolder = File(item.filePath + "_parts")
        partFolder.mkdirs()

        val downloadedBytes = AtomicLong(0L)
        val completedSegments = AtomicLong(0L)
        var lastSpeedTime = System.currentTimeMillis()
        var lastSpeedBytes = 0L

        // Track completed segments on resume
        segments.forEach { seg ->
            val segFile = File(partFolder, "seg_${seg.index}.ts")
            if (segFile.exists() && segFile.length() > 0) {
                downloadedBytes.addAndGet(segFile.length())
                completedSegments.incrementAndGet()
            }
        }

        // Segment downloader with worker pool of 4 concurrent streams
        val concurrency = 4
        val chunks = segments.chunked(concurrency)

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
                chunk.map { seg ->
                    async(Dispatchers.IO) {
                        val segFile = File(partFolder, "seg_${seg.index}.ts")
                        if (!segFile.exists() || segFile.length() == 0L) {
                            val bytes = downloadSegmentStreaming(seg, segFile, keyCache)
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

        // Merge segments into destination video file using BufferedOutputStream to avoid flash storage thrashing
        val outputStream = BufferedOutputStream(FileOutputStream(targetFile), BUFFER_SIZE)
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            for (seg in segments) {
                val segFile = File(partFolder, "seg_${seg.index}.ts")
                if (segFile.exists()) {
                    segFile.inputStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            outputStream.write(buffer, 0, read)
                        }
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

    private fun fetchBinary(url: String): ByteArray? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", MultiThreadDownloader.BROWSER_USER_AGENT)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseHighestStream(baseUrl: String, text: String): String? {
        val variants = parseVariantStreams(baseUrl)
        if (variants.isNotEmpty()) {
            return variants.first().url
        }

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

    /**
     * Extracts segments using AndroidX Media3 HlsPlaylistParser with graceful fallback.
     */
    private fun parseSegmentsWithMedia3(baseUrl: String, text: String): List<HlsSegment> {
        val uri = Uri.parse(baseUrl)
        try {
            val playlist = media3PlaylistParser.parse(uri, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            if (playlist is HlsMediaPlaylist && playlist.segments.isNotEmpty()) {
                val list = mutableListOf<HlsSegment>()
                playlist.segments.forEachIndexed { idx, segment ->
                    val fullUrl = resolveUrl(baseUrl, segment.url)
                    val rawKeyUri = segment.fullSegmentEncryptionKeyUri
                    val keyUri = rawKeyUri?.let { resolveUrl(baseUrl, it) }
                    val rawIv = segment.encryptionIV
                    val iv = if (rawIv != null) {
                        hexToBytes(rawIv)
                    } else if (keyUri != null) {
                        createSequenceIv(idx)
                    } else null

                    list.add(HlsSegment(index = idx, url = fullUrl, keyUrl = keyUri, iv = iv))
                }
                return list
            }
        } catch (e: Exception) {
            Log.d(TAG, "Media3 HlsMediaPlaylist parse fallback: ${e.message}")
        }

        // Fallback manual parser
        return parseSegments(baseUrl, text)
    }

    private fun parseSegments(baseUrl: String, text: String): List<HlsSegment> {
        val list = mutableListOf<HlsSegment>()
        var idx = 0
        var currentKeyUrl: String? = null
        var currentIv: ByteArray? = null

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-KEY")) {
                val methodMatch = Regex("METHOD=([A-Z0-9-]+)").find(trimmed)
                val method = methodMatch?.groupValues?.get(1) ?: "NONE"
                if (method.equals("AES-128", ignoreCase = true)) {
                    val uriMatch = Regex("URI=\"([^\"]+)\"").find(trimmed)
                    val rawUri = uriMatch?.groupValues?.get(1)
                    if (rawUri != null) {
                        currentKeyUrl = resolveUrl(baseUrl, rawUri)
                    }
                    val ivMatch = Regex("IV=0x([0-9a-fA-F]+)").find(trimmed)
                    val hexIv = ivMatch?.groupValues?.get(1)
                    currentIv = if (hexIv != null) {
                        hexToBytes(hexIv)
                    } else null
                } else if (method.equals("NONE", ignoreCase = true)) {
                    currentKeyUrl = null
                    currentIv = null
                }
            } else if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                val fullUrl = resolveUrl(baseUrl, trimmed)
                val iv = currentIv ?: createSequenceIv(idx)
                list.add(HlsSegment(index = idx, url = fullUrl, keyUrl = currentKeyUrl, iv = iv))
                idx++
            }
        }
        return list
    }

    private fun createSequenceIv(sequenceNumber: Int): ByteArray {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(0L)
        buf.putLong(sequenceNumber.toLong())
        return buf.array()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val padded = if (hex.length % 2 != 0) "0$hex" else hex
        val len = padded.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(padded[i], 16) shl 4) + Character.digit(padded[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun resolveUrl(base: String, path: String): String {
        return try {
            if (path.startsWith("http://") || path.startsWith("https://")) path
            else URI.create(base).resolve(path).toString()
        } catch (_: Exception) {
            path
        }
    }

    /**
     * Memory-safe streaming segment downloader.
     * Streams chunk directly into destination file with 64KB buffer without accumulating
     * large byte arrays in heap memory, preventing OutOfMemoryError.
     * Handles AES-128 stream decryption on the fly when encrypted.
     */
    private fun downloadSegmentStreaming(
        seg: HlsSegment,
        dest: File,
        keyCache: Map<String, ByteArray>
    ): Long {
        return try {
            val req = Request.Builder()
                .url(seg.url)
                .header("User-Agent", MultiThreadDownloader.BROWSER_USER_AGENT)
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return 0L
                val body = resp.body ?: return 0L

                val keyBytes = seg.keyUrl?.let { keyCache[it] }
                var total = 0L
                val buffer = ByteArray(BUFFER_SIZE)

                if (keyBytes != null && keyBytes.size == 16 && seg.iv != null && seg.iv.size == 16) {
                    // Decrypt stream on the fly with AES-128-CBC
                    val secretKey = SecretKeySpec(keyBytes, "AES")
                    val ivSpec = IvParameterSpec(seg.iv)
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

                    CipherInputStream(body.byteStream(), cipher).use { cipherIn ->
                        FileOutputStream(dest).use { fileOut ->
                            while (true) {
                                val read = cipherIn.read(buffer)
                                if (read == -1) break
                                fileOut.write(buffer, 0, read)
                                total += read
                            }
                            fileOut.flush()
                        }
                    }
                } else {
                    // Plain streaming without memory accumulation
                    body.byteStream().use { input ->
                        FileOutputStream(dest).use { fileOut ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                fileOut.write(buffer, 0, read)
                                total += read
                            }
                            fileOut.flush()
                        }
                    }
                }
                total
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download segment ${seg.index}: ${e.message}")
            0L
        }
    }
}
