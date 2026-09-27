package com.example.speeddown.engine

import android.util.Log
import com.example.speeddown.data.DownloadItem
import com.example.speeddown.data.DownloadStatus
import com.example.speeddown.data.DownloadStore
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

/**
 * High-performance Multi-threaded download engine with part-file chunking,
 * browser emulation, seamless pause/resume without re-downloading, and EMA speed smoothing.
 */
class MultiThreadDownloader(
    private val okHttpClient: OkHttpClient,
    private val store: DownloadStore
) {
    private val TAG = "MultiThreadDownloader"

    companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        const val YOUTUBE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    private fun isYouTubeOrGoogleVideo(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("googlevideo.com") || lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val cancelFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val pauseFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val activeCalls = ConcurrentHashMap<Long, MutableList<okhttp3.Call>>()
    private val downloadDispatcher = Dispatchers.IO.limitedParallelism(128)

    fun startDownload(
        scope: CoroutineScope,
        item: DownloadItem,
        speedLimitKbps: Long = 0L,
        onProgress: (downloaded: Long, speed: Long, parts: List<Float>) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        // Stop any running job and cancel ongoing calls for this download first
        cancelOngoingCalls(item.id)
        activeJobs.remove(item.id)?.cancel()

        val cancelFlag = AtomicBoolean(false)
        val pauseFlag = AtomicBoolean(false)
        cancelFlags[item.id] = cancelFlag
        pauseFlags[item.id] = pauseFlag

        var job: Job? = null
        job = scope.launch(Dispatchers.IO) {
            try {
                performDownload(item, speedLimitKbps, cancelFlag, pauseFlag, onProgress, onComplete, onError)
            } catch (_: CancellationException) {
                // Coroutine cancellation occurs normally on pause or restart.
                Log.d(TAG, "Download ${item.id} coroutine stopped (paused=${pauseFlag.get()})")
                if (pauseFlag.get()) {
                    store.updateStatus(item.id, DownloadStatus.PAUSED)
                }
            } catch (e: Exception) {
                if (cancelFlag.get() || pauseFlag.get()) {
                    Log.d(TAG, "Download ${item.id} stopped due to cancel/pause, ignoring: ${e.message}")
                } else {
                    Log.e(TAG, "Download ${item.id} error: ${e.message}", e)
                    val friendlyError = mapToFriendlyError(e)
                    onError(friendlyError)
                }
            } finally {
                job?.let { activeJobs.remove(item.id, it) }
                cancelFlags.remove(item.id)
                pauseFlags.remove(item.id)
                activeCalls.remove(item.id)
            }
        }
        activeJobs[item.id] = job
    }

    fun pauseDownload(downloadId: Long) {
        pauseFlags[downloadId]?.set(true)
        cancelOngoingCalls(downloadId)
        activeJobs[downloadId]?.cancel()
    }

    fun resumeDownload(
        scope: CoroutineScope,
        item: DownloadItem,
        speedLimitKbps: Long = 0L,
        onProgress: (Long, Long, List<Float>) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        startDownload(scope, item, speedLimitKbps, onProgress, onComplete, onError)
    }

    fun cancelDownload(downloadId: Long) {
        cancelFlags[downloadId]?.set(true)
        cancelOngoingCalls(downloadId)
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)
    }

    private fun cancelOngoingCalls(downloadId: Long) {
        val calls = activeCalls[downloadId]?.toList()
        calls?.forEach { call ->
            try { call.cancel() } catch (_: Exception) {}
        }
        activeCalls.remove(downloadId)
    }

    private suspend fun performDownload(
        item: DownloadItem,
        speedLimitKbps: Long,
        cancelFlag: AtomicBoolean,
        pauseFlag: AtomicBoolean,
        onProgress: (Long, Long, List<Float>) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (cancelFlag.get()) {
            store.updateStatus(item.id, DownloadStatus.CANCELLED)
            return
        }

        val targetFile = File(item.filePath)
        targetFile.parentFile?.mkdirs()

        if (item.audioUrl.isNullOrBlank()) {
            // --- Normal Single-Stream Download ---
            val probeResult = probeUrl(item.id, item.url, cancelFlag)
            if (cancelFlag.get()) {
                store.updateStatus(item.id, DownloadStatus.CANCELLED)
                return
            }

            val contentLength = probeResult.contentLength
            val acceptsRanges = probeResult.acceptsRanges

            if (contentLength > 0) {
                store.updateTotalSize(item.id, contentLength)
            }

            val success = downloadStreamInternal(
                downloadId = item.id,
                url = item.url,
                filePath = item.filePath,
                contentLength = contentLength,
                acceptsRanges = acceptsRanges,
                threads = item.threads,
                speedLimitKbps = speedLimitKbps,
                cancelFlag = cancelFlag,
                pauseFlag = pauseFlag,
                baseDownloaded = 0L,
                totalSizeForProgress = contentLength,
                onProgress = onProgress
            )

            if (success && !cancelFlag.get() && !pauseFlag.get()) {
                val threadCount = when {
                    isYouTubeOrGoogleVideo(item.url) -> 1
                    !acceptsRanges || contentLength <= 0 -> 1
                    contentLength < 1_000_000 -> 1
                    contentLength < 10_000_000 -> minOf(item.threads, 8)
                    else -> item.threads.coerceIn(1, 100)
                }
                val completedParts = (0 until threadCount).map { 1f }
                onProgress(contentLength.coerceAtLeast(targetFile.length()), 0L, completedParts)
                onComplete()
            }
        } else {
            // --- Dual-Stream Video + Audio Download (YouTube Muxing) ---
            val videoTempPath = "${item.filePath}.video.tmp"
            val audioTempPath = "${item.filePath}.audio.tmp"
            val videoTempFile = File(videoTempPath)
            val audioTempFile = File(audioTempPath)

            val videoProbe = probeUrl(item.id, item.url, cancelFlag)
            if (cancelFlag.get()) {
                store.updateStatus(item.id, DownloadStatus.CANCELLED)
                return
            }
            val audioProbe = probeUrl(item.id, item.audioUrl, cancelFlag)
            if (cancelFlag.get()) {
                store.updateStatus(item.id, DownloadStatus.CANCELLED)
                return
            }

            val videoLength = videoProbe.contentLength
            val audioLength = audioProbe.contentLength
            val combinedTotal = if (videoLength > 0 && audioLength > 0) videoLength + audioLength
                                else if (videoLength > 0) videoLength else -1L

            if (combinedTotal > 0) {
                store.updateTotalSize(item.id, combinedTotal)
            }

            // 1. Download Video Stream
            val isVideoDone = videoTempFile.exists() && (videoLength <= 0 || videoTempFile.length() >= videoLength)
            if (!isVideoDone) {
                val videoSuccess = downloadStreamInternal(
                    downloadId = item.id,
                    url = item.url,
                    filePath = videoTempPath,
                    contentLength = videoLength,
                    acceptsRanges = videoProbe.acceptsRanges,
                    threads = item.threads,
                    speedLimitKbps = speedLimitKbps,
                    cancelFlag = cancelFlag,
                    pauseFlag = pauseFlag,
                    baseDownloaded = 0L,
                    totalSizeForProgress = combinedTotal,
                    onProgress = onProgress
                )
                if (!videoSuccess || cancelFlag.get() || pauseFlag.get()) return
            }

            // 2. Download Audio Stream
            val effectiveVideoBytes = if (videoLength > 0) videoLength else videoTempFile.length()
            val isAudioDone = audioTempFile.exists() && (audioLength <= 0 || audioTempFile.length() >= audioLength)
            if (!isAudioDone) {
                val audioThreads = if (isYouTubeOrGoogleVideo(item.audioUrl)) 1 else minOf(item.threads, 4)
                val audioSuccess = downloadStreamInternal(
                    downloadId = item.id,
                    url = item.audioUrl,
                    filePath = audioTempPath,
                    contentLength = audioLength,
                    acceptsRanges = audioProbe.acceptsRanges,
                    threads = audioThreads,
                    speedLimitKbps = speedLimitKbps,
                    cancelFlag = cancelFlag,
                    pauseFlag = pauseFlag,
                    baseDownloaded = effectiveVideoBytes,
                    totalSizeForProgress = combinedTotal,
                    onProgress = onProgress
                )
                if (!audioSuccess || cancelFlag.get() || pauseFlag.get()) return
            }

            // 3. Hardware-Accelerated Muxing
            Log.d(TAG, "Both video and audio downloaded for ${item.fileName}. Starting MediaMuxer...")
            onProgress(combinedTotal, 0L, listOf(1f))

            val muxResult = MediaMuxerEngine.mux(
                videoFile = videoTempFile,
                audioFile = audioTempFile,
                outputFile = targetFile
            )

            if (muxResult.isSuccess) {
                Log.d(TAG, "MediaMuxer successfully created ${targetFile.name} (${targetFile.length()} bytes)")
                cleanupParts(videoTempPath, item.threads)
                cleanupParts(audioTempPath, 4)
                try { videoTempFile.delete() } catch (_: Exception) {}
                try { audioTempFile.delete() } catch (_: Exception) {}
                onProgress(combinedTotal, 0L, listOf(1f))
                onComplete()
            } else {
                val err = muxResult.exceptionOrNull()
                Log.e(TAG, "MediaMuxer failed for ${item.fileName}: ${err?.message}", err)
                if (videoTempFile.exists() && !targetFile.exists()) {
                    videoTempFile.renameTo(targetFile)
                }
                onError("Video downloaded, but audio merge failed: ${err?.message}")
            }
        }
    }

    private suspend fun downloadStreamInternal(
        downloadId: Long,
        url: String,
        filePath: String,
        contentLength: Long,
        acceptsRanges: Boolean,
        threads: Int,
        speedLimitKbps: Long,
        cancelFlag: AtomicBoolean,
        pauseFlag: AtomicBoolean,
        baseDownloaded: Long = 0L,
        totalSizeForProgress: Long = -1L,
        onProgress: (Long, Long, List<Float>) -> Unit
    ): Boolean {
        if (cancelFlag.get()) {
            store.updateStatus(downloadId, DownloadStatus.CANCELLED)
            return false
        }

        val targetFile = File(filePath)
        targetFile.parentFile?.mkdirs()

        val threadCount = when {
            isYouTubeOrGoogleVideo(url) -> 1
            !acceptsRanges || contentLength <= 0 -> 1
            contentLength < 1_000_000 -> 1
            contentLength < 10_000_000 -> minOf(threads, 8)
            else -> threads.coerceIn(1, 100)
        }

        val chunkSize = if (contentLength > 0) contentLength / threadCount else 0L

        // Track bytes per part for IDM-style live segmented visualizer
        val partProgressBytes = Array(threadCount) { i ->
            val pFile = getPartFile(filePath, i, threadCount)
            AtomicLong(if (acceptsRanges && pFile.exists()) pFile.length() else 0L)
        }

        // Calculate already downloaded bytes from existing part files
        val initialBytes = if (acceptsRanges) {
            (0 until threadCount).sumOf { i ->
                val pFile = getPartFile(filePath, i, threadCount)
                if (pFile.exists()) pFile.length() else 0L
            }
        } else {
            cleanupParts(filePath, threadCount)
            0L
        }
        val streamDownloaded = AtomicLong(initialBytes)

        var lastSpeedCheck = System.currentTimeMillis()
        var lastSpeedBytes = initialBytes
        var smoothedSpeed = 0L

        // Speed & Progress Reporter with Exponential Moving Average (EMA) and Segment Tracking
        val progressJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && !cancelFlag.get() && !pauseFlag.get()) {
                delay(500)
                val now = System.currentTimeMillis()
                val elapsed = (now - lastSpeedCheck).coerceAtLeast(1)
                val currentStream = streamDownloaded.get()
                val bytesDelta = (currentStream - lastSpeedBytes).coerceAtLeast(0)
                val instantSpeed = (bytesDelta * 1000L) / elapsed

                // EMA smoothing (70% previous + 30% instant) for rock-solid speed & ETA
                smoothedSpeed = if (smoothedSpeed == 0L) {
                    instantSpeed
                } else {
                    ((smoothedSpeed * 7) + (instantSpeed * 3)) / 10
                }

                lastSpeedCheck = now
                lastSpeedBytes = currentStream

                val parts = if (chunkSize > 0) {
                    (0 until threadCount).map { i ->
                        val targetLen = if (i == threadCount - 1) (contentLength - i * chunkSize) else chunkSize
                        if (targetLen > 0) (partProgressBytes[i].get().toFloat() / targetLen.toFloat()).coerceIn(0f, 1f) else 0f
                    }
                } else emptyList()

                val reportedTotal = baseDownloaded + currentStream
                onProgress(reportedTotal, smoothedSpeed.coerceAtLeast(0L), parts)
            }
        }

        try {
            coroutineScope {
                val deferreds = (0 until threadCount).map { i ->
                    val partFile = getPartFile(filePath, i, threadCount)
                    val realStart = if (chunkSize > 0) i * chunkSize else 0L
                    val realEnd = if (chunkSize > 0) {
                        if (i == threadCount - 1) contentLength - 1 else (i + 1) * chunkSize - 1
                    } else -1L

                    async(downloadDispatcher) {
                        downloadPart(
                            downloadId = downloadId,
                            url = url,
                            partFile = partFile,
                            chunkStart = realStart,
                            chunkEnd = realEnd,
                            partProgressBytes = partProgressBytes[i],
                            totalDownloaded = streamDownloaded,
                            speedLimitKbps = speedLimitKbps,
                            threadCount = threadCount,
                            cancelFlag = cancelFlag,
                            pauseFlag = pauseFlag,
                            useRange = acceptsRanges && realEnd > 0
                        )
                    }
                }
                deferreds.awaitAll()
            }
        } finally {
            progressJob.cancel()
        }

        if (cancelFlag.get()) {
            store.updateStatus(downloadId, DownloadStatus.CANCELLED)
            cleanupParts(filePath, threadCount)
            try { targetFile.delete() } catch (_: Exception) {}
            return false
        }

        if (pauseFlag.get()) {
            val currentStream = streamDownloaded.get()
            val pausedParts = if (chunkSize > 0) {
                (0 until threadCount).map { i ->
                    val targetLen = if (i == threadCount - 1) (contentLength - i * chunkSize) else chunkSize
                    if (targetLen > 0) (partProgressBytes[i].get().toFloat() / targetLen.toFloat()).coerceIn(0f, 1f) else 0f
                }
            } else emptyList()
            store.updateProgress(downloadId, baseDownloaded + currentStream, 0L, DownloadStatus.PAUSED, pausedParts)
            return false
        }

        // All parts completed successfully! Merge parts into target file.
        mergeParts(targetFile, filePath, threadCount)
        return true
    }

    private data class ProbeResult(val contentLength: Long, val acceptsRanges: Boolean)

    private fun probeUrl(downloadId: Long, url: String, cancelFlag: AtomicBoolean): ProbeResult {
        if (cancelFlag.get()) return ProbeResult(-1L, false)

        val callList = activeCalls.computeIfAbsent(downloadId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }

        val isYt = isYouTubeOrGoogleVideo(url)
        val userAgent = if (isYt) YOUTUBE_USER_AGENT else BROWSER_USER_AGENT

        // Step 1: Probe with GET Range: bytes=0-0 (Standard browser method for S3/R2 presigned URLs)
        try {
            val rangeReqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .header("Range", "bytes=0-0")
            if (isYt) {
                rangeReqBuilder.header("Origin", "https://www.youtube.com")
                rangeReqBuilder.header("Referer", "https://www.youtube.com/")
                rangeReqBuilder.header("Accept-Encoding", "identity")
            }
            val rangeReq = rangeReqBuilder.build()

            val call = okHttpClient.newCall(rangeReq)
            callList.add(call)

            try {
                call.execute().use { response ->
                    callList.remove(call)
                    if (cancelFlag.get()) return ProbeResult(-1L, false)
                    if (response.code == 206) {
                        val contentRange = response.header("Content-Range")
                        val totalFromRange = contentRange?.substringAfterLast("/")?.toLongOrNull() ?: -1L
                        val length = if (totalFromRange > 0) totalFromRange else (response.header("Content-Length")?.toLongOrNull() ?: -1L)
                        return ProbeResult(length, acceptsRanges = true)
                    } else if (response.isSuccessful) {
                        val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
                        val ranges = response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                        return ProbeResult(length, acceptsRanges = ranges)
                    } else {
                        throw IOException("HTTP ${response.code}: ${getHttpErrorMessage(response.code)}")
                    }
                }
            } catch (e: IOException) {
                callList.remove(call)
                if (cancelFlag.get()) return ProbeResult(-1L, false)
                if (e.message?.startsWith("HTTP ") == true) throw e
            }
        } catch (_: Exception) {
            if (cancelFlag.get()) return ProbeResult(-1L, false)
        }

        if (cancelFlag.get()) return ProbeResult(-1L, false)

        // Step 2: Fallback to standard GET probe
        val getReqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
        if (isYt) {
            getReqBuilder.header("Origin", "https://www.youtube.com")
            getReqBuilder.header("Referer", "https://www.youtube.com/")
            getReqBuilder.header("Accept-Encoding", "identity")
        }
        val getReq = getReqBuilder.build()

        val getCall = okHttpClient.newCall(getReq)
        callList.add(getCall)

        try {
            getCall.execute().use { response ->
                callList.remove(getCall)
                if (cancelFlag.get()) return ProbeResult(-1L, false)
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: ${getHttpErrorMessage(response.code)}")
                }
                val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val acceptsRanges = response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                return ProbeResult(length, acceptsRanges)
            }
        } catch (e: Exception) {
            callList.remove(getCall)
            if (cancelFlag.get()) return ProbeResult(-1L, false)
            throw e
        }
    }

    private fun getPartFile(filePath: String, partIndex: Int, threadCount: Int): File {
        return if (threadCount == 1) {
            File("$filePath.part")
        } else {
            File("$filePath.part$partIndex")
        }
    }

    private fun cleanupParts(filePath: String, threadCount: Int) {
        for (i in 0 until threadCount) {
            try {
                val f = getPartFile(filePath, i, threadCount)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
    }

    private fun mergeParts(targetFile: File, filePath: String, threadCount: Int) {
        if (threadCount == 1) {
            val singlePart = getPartFile(filePath, 0, 1)
            if (singlePart.exists()) {
                if (targetFile.exists()) targetFile.delete()
                singlePart.renameTo(targetFile)
            }
            return
        }

        if (targetFile.exists()) targetFile.delete()
        FileOutputStream(targetFile).use { outputStream ->
            for (i in 0 until threadCount) {
                val pFile = getPartFile(filePath, i, threadCount)
                if (pFile.exists()) {
                    pFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream, bufferSize = 65536)
                    }
                    pFile.delete()
                }
            }
        }
    }

    private suspend fun downloadPart(
        downloadId: Long,
        url: String,
        partFile: File,
        chunkStart: Long,
        chunkEnd: Long,
        partProgressBytes: AtomicLong,
        totalDownloaded: AtomicLong,
        speedLimitKbps: Long,
        threadCount: Int,
        cancelFlag: AtomicBoolean,
        pauseFlag: AtomicBoolean,
        useRange: Boolean
    ) {
        val existingBytes = if (useRange && partFile.exists()) partFile.length() else 0L
        val isYt = isYouTubeOrGoogleVideo(url)
        val userAgent = if (isYt) YOUTUBE_USER_AGENT else BROWSER_USER_AGENT

        val callList = activeCalls.computeIfAbsent(downloadId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }

        var attempts = 0
        val maxAttempts = 6 // Allow up to 6 reconnect attempts for continuous streams
        while (attempts < maxAttempts && !cancelFlag.get() && !pauseFlag.get()) {
            attempts++
            val existingBytes = if (useRange && partFile.exists()) partFile.length() else 0L
            partProgressBytes.set(existingBytes)

            // Check if this chunk is already finished
            if (useRange && chunkEnd > 0 && chunkStart + existingBytes > chunkEnd) {
                return
            }

            val requestStart = if (useRange) chunkStart + existingBytes else 0L
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")

            if (isYt) {
                requestBuilder.header("Origin", "https://www.youtube.com")
                requestBuilder.header("Referer", "https://www.youtube.com/")
                requestBuilder.header("Accept-Encoding", "identity")
            }

            if (useRange && chunkStart >= 0) {
                val rangeHeader = if (chunkEnd > 0) "bytes=$requestStart-$chunkEnd" else "bytes=$requestStart-"
                requestBuilder.header("Range", rangeHeader)
            }

            val call = okHttpClient.newCall(requestBuilder.build())
            callList.add(call)

            try {
                val response = call.execute()
                if (!response.isSuccessful && response.code != 206) {
                    val code = response.code
                    response.close()
                    // If CDN throttles or temporarily returns 429/403, retry with backoff
                    if ((code == 429 || code == 403) && attempts < maxAttempts) {
                        Log.w(TAG, "Server responded with HTTP $code, retrying ($attempts/$maxAttempts) in ${attempts * 1500}ms...")
                        delay(attempts * 1500L)
                        continue
                    }
                    throw IOException("HTTP $code: ${getHttpErrorMessage(code)}")
                }

                val body = response.body ?: run {
                    response.close()
                    throw IOException("Empty response body from server")
                }

                val buffer = ByteArray(65536) // 64KB buffer for high speed
                FileOutputStream(partFile, useRange).use { fileOut ->
                    body.byteStream().use { stream ->
                        while (!cancelFlag.get() && !pauseFlag.get()) {
                            val read = stream.read(buffer)
                            if (read == -1) break
                            fileOut.write(buffer, 0, read)
                            partProgressBytes.addAndGet(read.toLong())
                            totalDownloaded.addAndGet(read.toLong())
                            if (speedLimitKbps > 0) {
                                val perThreadBps = (speedLimitKbps * 1024L) / java.lang.Math.max(1, threadCount)
                                val sleepMs = ((read.toDouble() / perThreadBps) * 1000.0).toLong()
                                if (sleepMs > 0) {
                                    delay(sleepMs.coerceAtMost(250L))
                                }
                            }
                        }
                        fileOut.flush()
                    }
                }
                response.close()
                // Successfully completed chunk
                return
            } catch (e: IOException) {
                if (cancelFlag.get() || pauseFlag.get()) {
                    return
                }
                val isConnectionDrop = e is SocketTimeoutException ||
                        e is ConnectException ||
                        e is java.net.SocketException ||
                        e is java.io.EOFException ||
                        e is SSLException ||
                        e.message?.contains("unexpected end of stream", ignoreCase = true) == true ||
                        e.message?.contains("reset", ignoreCase = true) == true ||
                        e.message?.contains("pipe", ignoreCase = true) == true ||
                        e.message?.contains("connection abort", ignoreCase = true) == true

                if (attempts < maxAttempts && isConnectionDrop) {
                    Log.w(TAG, "Chunk $chunkStart connection dropped (${e.message}), auto-retrying ($attempts/$maxAttempts) in ${attempts * 1000}ms...")
                    delay(attempts * 1000L)
                    continue
                }
                throw e
            } finally {
                callList.remove(call)
            }
        }
    }

    private fun mapToFriendlyError(e: Throwable): String {
        val msg = e.message ?: ""
        return when {
            msg.startsWith("HTTP ") -> msg
            e is UnknownHostException -> "Unable to reach server. Please check internet connection or URL."
            e is SocketTimeoutException -> "Connection timed out. Server is taking too long to respond."
            e is ConnectException -> "Failed to connect to server. Connection refused."
            e is SSLException -> "SSL/TLS Security Handshake failed."
            e is IllegalArgumentException -> "Invalid URL format: ${e.message}"
            msg.contains("ENOSPC", ignoreCase = true) -> "Device storage is full (No space left)."
            msg.contains("Permission denied", ignoreCase = true) -> "Storage permission denied."
            else -> msg.ifBlank { "Download failed due to an unexpected error" }
        }
    }

    private fun getHttpErrorMessage(code: Int): String = when (code) {
        400 -> "Bad Request (400)"
        401 -> "Authentication required (401 Unauthorized)"
        403 -> "Access forbidden (403 Forbidden - Direct download blocked)"
        404 -> "File not found on server (404 Not Found)"
        405 -> "Method Not Allowed (405)"
        408 -> "Request Timed Out (408)"
        410 -> "Download link expired or removed (410 Gone)"
        416 -> "Range Not Satisfiable (416)"
        429 -> "Too Many Requests (429 Rate limited)"
        500 -> "Server Internal Error (500)"
        502 -> "Bad Gateway (502)"
        503 -> "Service Unavailable / Server Overloaded (503)"
        504 -> "Gateway Timeout (504)"
        else -> "Server error ($code)"
    }
}
