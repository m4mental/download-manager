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
import java.io.IOException
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
    val iv: ByteArray? = null,
    val initSegmentUrl: String? = null,
    val byteRangeOffset: Long? = null,
    val byteRangeLength: Long? = null
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

        private val activeJobs = ConcurrentHashMap<Long, Job>()
        private val activeCalls = ConcurrentHashMap<Long, MutableList<okhttp3.Call>>()
        private val cancelFlags = ConcurrentHashMap<Long, AtomicBoolean>()
        private val pauseFlags = ConcurrentHashMap<Long, AtomicBoolean>()
        private val activeOwnershipTokens = ConcurrentHashMap<Long, String>()

        suspend fun cancelAndJoin(downloadId: Long) {
            cancelFlags[downloadId]?.set(true)
            cancelOngoingCalls(downloadId)
            val job = activeJobs[downloadId]
            job?.cancel()
            job?.join()
            activeJobs.remove(downloadId, job)
            activeOwnershipTokens.remove(downloadId)
            cancelFlags.remove(downloadId)
            pauseFlags.remove(downloadId)
            activeCalls.remove(downloadId)
        }

        suspend fun pauseAndJoin(downloadId: Long) {
            pauseFlags[downloadId]?.set(true)
            cancelOngoingCalls(downloadId)
            val job = activeJobs[downloadId]
            job?.cancel()
            job?.join()
            activeJobs.remove(downloadId, job)
            activeOwnershipTokens.remove(downloadId)
            cancelFlags.remove(downloadId)
            pauseFlags.remove(downloadId)
            activeCalls.remove(downloadId)
        }

        fun cancelOngoingCalls(downloadId: Long) {
            val calls = activeCalls[downloadId]?.toList()
            calls?.forEach { call ->
                try { call.cancel() } catch (_: Exception) {}
            }
            activeCalls.remove(downloadId)
        }

        fun padIvTo16Bytes(ivBytes: ByteArray): ByteArray {
            return when {
                ivBytes.size == 16 -> ivBytes
                ivBytes.size < 16 -> {
                    val padded = ByteArray(16)
                    System.arraycopy(ivBytes, 0, padded, 16 - ivBytes.size, ivBytes.size)
                    padded
                }
                else -> ivBytes.copyOfRange(ivBytes.size - 16, ivBytes.size)
            }
        }

        fun parseHexIv(hex: String): ByteArray {
            val clean = hex.removePrefix("0x").removePrefix("0X")
            val padded = if (clean.length % 2 != 0) "0$clean" else clean
            val len = padded.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                val high = Character.digit(padded[i], 16)
                val low = Character.digit(padded[i + 1], 16)
                data[i / 2] = ((high shl 4) + low).toByte()
                i += 2
            }
            return padIvTo16Bytes(data)
        }

        fun createMediaSequenceIv(sequenceNumber: Long): ByteArray {
            val buf = ByteBuffer.allocate(16)
            buf.putLong(0L)
            buf.putLong(sequenceNumber)
            return buf.array()
        }

        fun checkUnsupportedPlaylistFeatures(playlistText: String): String? {
            if (playlistText.contains("#EXT-X-MEDIA:TYPE=AUDIO", ignoreCase = true)) {
                return "Unsupported HLS feature: Separate audio renditions (#EXT-X-MEDIA:TYPE=AUDIO) are not supported."
            }
            return null
        }
    }

    private val media3PlaylistParser by lazy { HlsPlaylistParser() }

    /**
     * Parses all adaptive stream variants using AndroidX Media3 HlsPlaylistParser.
     */
    fun parseVariantStreams(url: String): List<HlsStreamVariant> {
        val text = fetchText(url) ?: return emptyList()
        val uri = Uri.parse(url)

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

        if (!text.contains("#EXT-X-STREAM-INF")) return emptyList()
        val variants = mutableListOf<HlsStreamVariant>()
        val lines = text.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val res = Regex("""RESOLUTION=([\dx]+)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1) ?: ""
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

    suspend fun cancelAndJoinHls(downloadId: Long) {
        cancelAndJoin(downloadId)
    }

    suspend fun pauseAndJoinHls(downloadId: Long) {
        pauseAndJoin(downloadId)
    }

    fun startHlsDownload(
        scope: CoroutineScope,
        item: DownloadItem,
        onProgress: (downloaded: Long, speed: Long, parts: List<Float>) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            cancelAndJoin(item.id)

            val ownershipToken = java.util.UUID.randomUUID().toString()
            activeOwnershipTokens[item.id] = ownershipToken

            val cancelFlag = AtomicBoolean(false)
            val pauseFlag = AtomicBoolean(false)
            cancelFlags[item.id] = cancelFlag
            pauseFlags[item.id] = pauseFlag

            val job = coroutineContext[Job]
            if (job != null) {
                activeJobs[item.id] = job
            }

            try {
                performHlsDownload(item, cancelFlag, pauseFlag, onProgress, onComplete, onError)
            } catch (e: Exception) {
                if (!cancelFlag.get() && !pauseFlag.get()) {
                    Log.e(TAG, "HLS download error", e)
                    onError("HLS Error: ${e.localizedMessage ?: "Failed to download stream"}")
                }
            } finally {
                if (activeOwnershipTokens[item.id] == ownershipToken) {
                    activeOwnershipTokens.remove(item.id)
                    activeJobs.remove(item.id, job)
                    cancelFlags.remove(item.id)
                    pauseFlags.remove(item.id)
                    activeCalls.remove(item.id)
                }
            }
        }
    }

    fun pauseHls(downloadId: Long) {
        pauseFlags[downloadId]?.set(true)
        cancelOngoingCalls(downloadId)
        activeJobs[downloadId]?.cancel()
    }

    fun cancelHls(downloadId: Long) {
        cancelFlags[downloadId]?.set(true)
        cancelOngoingCalls(downloadId)
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)
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
        val playlistText = fetchText(item.url, item.id)
        if (playlistText == null) {
            onError("Unable to fetch M3U8 playlist")
            return@withContext
        }

        // Check unsupported playlist features (e.g. separate audio renditions)
        val initialUnsupported = checkUnsupportedPlaylistFeatures(playlistText)
        if (initialUnsupported != null) {
            onError(initialUnsupported)
            return@withContext
        }

        // 2. Resolve Master playlist vs Media playlist
        var mediaPlaylistUrl = item.url
        var mediaPlaylistText = playlistText

        if (playlistText.contains("#EXT-X-STREAM-INF")) {
            val highestStreamUrl = parseHighestStream(item.url, playlistText)
            if (highestStreamUrl != null) {
                mediaPlaylistUrl = highestStreamUrl
                mediaPlaylistText = fetchText(highestStreamUrl, item.id) ?: playlistText
                val mediaUnsupported = checkUnsupportedPlaylistFeatures(mediaPlaylistText)
                if (mediaUnsupported != null) {
                    onError(mediaUnsupported)
                    return@withContext
                }
            }
        }

        // 3. Extract all segment URLs, encryption keys, init segments, and byte ranges
        val segments = parseSegmentsWithMedia3(mediaPlaylistUrl, mediaPlaylistText)
        if (segments.isEmpty()) {
            onError("No stream segments found in M3U8 playlist")
            return@withContext
        }

        // Fetch any AES-128 encryption keys needed
        val keyCache = ConcurrentHashMap<String, ByteArray>()
        val keyUrls = segments.mapNotNull { it.keyUrl }.distinct()
        for (kUrl in keyUrls) {
            if (cancelFlag.get() || pauseFlag.get()) return@withContext
            val keyBytes = fetchBinary(kUrl, item.id)
            if (keyBytes == null || keyBytes.size != 16) {
                onError("Failed to fetch valid 16-byte AES-128 key from $kUrl")
                return@withContext
            }
            keyCache[kUrl] = keyBytes
        }

        val totalSegments = segments.size
        val partFolder = File(item.filePath + "_parts")
        partFolder.mkdirs()

        // Fetch init segment (EXT-X-MAP) once before media segments if present
        val initSegmentUrl = segments.firstOrNull()?.initSegmentUrl
        if (!initSegmentUrl.isNullOrBlank()) {
            val initFile = File(partFolder, "init.mp4")
            if (!initFile.exists() || initFile.length() == 0L) {
                val initBytes = fetchBinary(initSegmentUrl, item.id)
                if (initBytes == null || initBytes.isEmpty()) {
                    onError("Failed to fetch HLS init segment (EXT-X-MAP)")
                    return@withContext
                }
                val tmpInit = File(partFolder, "init.mp4.tmp")
                tmpInit.writeBytes(initBytes)
                if (!tmpInit.renameTo(initFile)) {
                    onError("Failed to write init segment")
                    return@withContext
                }
            }
        }

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

            var chunkFailed = false
            var failureMessage: String? = null

            try {
                coroutineScope {
                    chunk.map { seg ->
                        async(Dispatchers.IO) {
                            val segFile = File(partFolder, "seg_${seg.index}.ts")
                            if (!segFile.exists() || segFile.length() == 0L) {
                                val bytes = downloadSegmentStreaming(item.id, seg, segFile, keyCache)
                                downloadedBytes.addAndGet(bytes)
                                completedSegments.incrementAndGet()
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
            } catch (e: Exception) {
                chunkFailed = true
                failureMessage = e.message
            }

            if (chunkFailed) {
                if (!cancelFlag.get() && !pauseFlag.get()) {
                    onError("HLS segment download failed: ${failureMessage ?: "Unknown error"}")
                }
                return@withContext
            }
        }

        if (cancelFlag.get() || pauseFlag.get()) return@withContext

        // Verify EVERY segment exists before merging
        for (seg in segments) {
            val segFile = File(partFolder, "seg_${seg.index}.ts")
            if (!segFile.exists() || segFile.length() == 0L) {
                onError("HLS download incomplete: Segment ${seg.index} is missing or empty")
                return@withContext
            }
        }

        // Merge segments into destination video file using BufferedOutputStream
        try {
            if (targetFile.exists()) targetFile.delete()
            BufferedOutputStream(FileOutputStream(targetFile), BUFFER_SIZE).use { outputStream ->
                val buffer = ByteArray(BUFFER_SIZE)

                // Write init segment once if present
                val initFile = File(partFolder, "init.mp4")
                if (initFile.exists()) {
                    initFile.inputStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }

                for (seg in segments) {
                    val segFile = File(partFolder, "seg_${seg.index}.ts")
                    segFile.inputStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
                outputStream.flush()
            }
        } catch (e: Exception) {
            if (targetFile.exists()) targetFile.delete()
            onError("Failed to merge HLS segments: ${e.message}")
            return@withContext
        }

        // Clean up temporary segment files
        partFolder.deleteRecursively()
        store.updateTotalSize(item.id, targetFile.length())
        store.markCompleted(item.id)
        onComplete()
    }

    private fun fetchText(url: String, downloadId: Long? = null): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", MultiThreadDownloader.BROWSER_USER_AGENT)
                .build()
            val call = okHttpClient.newCall(req)
            if (downloadId != null) {
                activeCalls.computeIfAbsent(downloadId) {
                    java.util.Collections.synchronizedList(mutableListOf())
                }.add(call)
            }
            try {
                call.execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            } finally {
                if (downloadId != null) {
                    activeCalls[downloadId]?.remove(call)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchBinary(url: String, downloadId: Long? = null): ByteArray? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", MultiThreadDownloader.BROWSER_USER_AGENT)
                .build()
            val call = okHttpClient.newCall(req)
            if (downloadId != null) {
                activeCalls.computeIfAbsent(downloadId) {
                    java.util.Collections.synchronizedList(mutableListOf())
                }.add(call)
            }
            try {
                call.execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }
            } finally {
                if (downloadId != null) {
                    activeCalls[downloadId]?.remove(call)
                }
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

    private fun parseSegmentsWithMedia3(baseUrl: String, text: String): List<HlsSegment> {
        val uri = Uri.parse(baseUrl)
        try {
            val playlist = media3PlaylistParser.parse(uri, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            if (playlist is HlsMediaPlaylist && playlist.segments.isNotEmpty()) {
                val list = mutableListOf<HlsSegment>()
                val initMapUrl = Regex("""#EXT-X-MAP:.*URI="([^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.get(1)?.let { resolveUrl(baseUrl, it) }

                playlist.segments.forEachIndexed { idx, segment ->
                    val fullUrl = resolveUrl(baseUrl, segment.url)
                    val rawKeyUri = segment.fullSegmentEncryptionKeyUri
                    val keyUri = rawKeyUri?.let { resolveUrl(baseUrl, it) }
                    val rawIv = segment.encryptionIV
                    val iv = if (rawIv != null) {
                        parseHexIv(rawIv)
                    } else if (keyUri != null) {
                        createMediaSequenceIv(playlist.mediaSequence + idx)
                    } else null

                    list.add(
                        HlsSegment(
                            index = idx,
                            url = fullUrl,
                            keyUrl = keyUri,
                            iv = iv,
                            initSegmentUrl = initMapUrl,
                            byteRangeOffset = if (segment.byteRangeLength > 0) segment.byteRangeOffset else null,
                            byteRangeLength = if (segment.byteRangeLength > 0) segment.byteRangeLength else null
                        )
                    )
                }
                return list
            }
        } catch (e: Exception) {
            Log.d(TAG, "Media3 HlsMediaPlaylist parse fallback: ${e.message}")
        }

        return parseSegments(baseUrl, text)
    }

    private fun parseSegments(baseUrl: String, text: String): List<HlsSegment> {
        val list = mutableListOf<HlsSegment>()
        var idx = 0
        var currentKeyUrl: String? = null
        var currentIv: ByteArray? = null
        var currentInitUrl: String? = null
        var nextByteRangeOffset: Long? = null
        var nextByteRangeLength: Long? = null
        var mediaSequence = 0L

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = trimmed.substringAfter(":").trim().toLongOrNull() ?: 0L
            } else if (trimmed.startsWith("#EXT-X-MAP:")) {
                val uriMatch = Regex("""URI="([^"]+)"""").find(trimmed)
                val rawUri = uriMatch?.groupValues?.get(1)
                if (rawUri != null) {
                    currentInitUrl = resolveUrl(baseUrl, rawUri)
                }
            } else if (trimmed.startsWith("#EXT-X-KEY")) {
                val methodMatch = Regex("METHOD=([A-Z0-9-]+)").find(trimmed)
                val method = methodMatch?.groupValues?.get(1) ?: "NONE"
                if (method.equals("AES-128", ignoreCase = true)) {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(trimmed)
                    val rawUri = uriMatch?.groupValues?.get(1)
                    if (rawUri != null) {
                        currentKeyUrl = resolveUrl(baseUrl, rawUri)
                    }
                    val ivMatch = Regex("IV=0x([0-9a-fA-F]+)", RegexOption.IGNORE_CASE).find(trimmed)
                    val hexIv = ivMatch?.groupValues?.get(1)
                    currentIv = if (hexIv != null) {
                        parseHexIv(hexIv)
                    } else null
                } else if (method.equals("NONE", ignoreCase = true)) {
                    currentKeyUrl = null
                    currentIv = null
                }
            } else if (trimmed.startsWith("#EXT-X-BYTERANGE:")) {
                val rangeStr = trimmed.substringAfter(":")
                val parts = rangeStr.split("@")
                nextByteRangeLength = parts.getOrNull(0)?.toLongOrNull()
                nextByteRangeOffset = parts.getOrNull(1)?.toLongOrNull()
            } else if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                val fullUrl = resolveUrl(baseUrl, trimmed)
                val iv = currentIv ?: if (currentKeyUrl != null) createMediaSequenceIv(mediaSequence + idx) else null
                list.add(
                    HlsSegment(
                        index = idx,
                        url = fullUrl,
                        keyUrl = currentKeyUrl,
                        iv = iv,
                        initSegmentUrl = currentInitUrl,
                        byteRangeOffset = nextByteRangeOffset,
                        byteRangeLength = nextByteRangeLength
                    )
                )
                nextByteRangeOffset = null
                nextByteRangeLength = null
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

    private fun downloadSegmentStreaming(
        downloadId: Long,
        seg: HlsSegment,
        dest: File,
        keyCache: Map<String, ByteArray>
    ): Long {
        val tmpDest = File(dest.parentFile, "${dest.name}.tmp")
        try {
            val reqBuilder = Request.Builder()
                .url(seg.url)
                .header("User-Agent", MultiThreadDownloader.BROWSER_USER_AGENT)

            val rangeLen = seg.byteRangeLength
            val isRanged = rangeLen != null && rangeLen > 0
            if (rangeLen != null && rangeLen > 0) {
                val start = seg.byteRangeOffset ?: 0L
                val end = start + rangeLen - 1
                reqBuilder.header("Range", "bytes=$start-$end")
            }

            val call = okHttpClient.newCall(reqBuilder.build())
            activeCalls.computeIfAbsent(downloadId) {
                java.util.Collections.synchronizedList(mutableListOf())
            }.add(call)

            try {
                call.execute().use { resp ->
                    if (isRanged && resp.code != 206) {
                        throw IOException("HTTP ${resp.code} for byte-range segment ${seg.index} (expected 206 Partial Content)")
                    } else if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code} downloading segment ${seg.index}")
                    }
                    val body = resp.body ?: throw IOException("Empty response body for segment ${seg.index}")

                    val keyBytes = seg.keyUrl?.let { keyCache[it] }
                    var total = 0L
                    val buffer = ByteArray(BUFFER_SIZE)

                    if (seg.keyUrl != null) {
                        // Enforce strict key and IV validation; never publish ciphertext
                        if (keyBytes == null || keyBytes.size != 16) {
                            throw IOException("Segment ${seg.index} encrypted but valid 16-byte key is missing")
                        }
                        if (seg.iv == null || seg.iv.size != 16) {
                            throw IOException("Segment ${seg.index} encrypted but valid 16-byte IV is missing")
                        }

                        val secretKey = SecretKeySpec(keyBytes, "AES")
                        val ivSpec = IvParameterSpec(seg.iv)
                        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

                        CipherInputStream(body.byteStream(), cipher).use { cipherIn ->
                            FileOutputStream(tmpDest).use { fileOut ->
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
                        body.byteStream().use { input ->
                            FileOutputStream(tmpDest).use { fileOut ->
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

                    if (!tmpDest.renameTo(dest)) {
                        throw IOException("Failed to rename temporary segment file to ${dest.name}")
                    }
                    return total
                }
            } finally {
                activeCalls[downloadId]?.remove(call)
            }
        } catch (e: Exception) {
            if (tmpDest.exists()) tmpDest.delete()
            throw e
        }
    }
}

