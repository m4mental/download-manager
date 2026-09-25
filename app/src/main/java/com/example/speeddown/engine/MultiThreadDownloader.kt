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
    }

    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val cancelFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val pauseFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val activeCalls = ConcurrentHashMap<Long, MutableList<okhttp3.Call>>()
    private val downloadDispatcher = Dispatchers.IO.limitedParallelism(128)

    fun startDownload(
        scope: CoroutineScope,
        item: DownloadItem,
        onProgress: (downloaded: Long, speed: Long) -> Unit,
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
                performDownload(item, cancelFlag, pauseFlag, onProgress, onComplete, onError)
            } catch (_: CancellationException) {
                // Coroutine cancellation occurs normally on pause or restart.
                Log.d(TAG, "Download ${item.id} coroutine stopped (paused=${pauseFlag.get()})")
                if (pauseFlag.get()) {
                    store.updateStatus(item.id, DownloadStatus.PAUSED)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download ${item.id} error: ${e.message}", e)
                val friendlyError = mapToFriendlyError(e)
                onError(friendlyError)
            } finally {
                job?.let { activeJobs.remove(item.id, it) }
                cancelFlags.remove(item.id, cancelFlag)
                pauseFlags.remove(item.id, pauseFlag)
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
        onProgress: (Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        startDownload(scope, item, onProgress, onComplete, onError)
    }

    fun cancelDownload(downloadId: Long) {
        cancelFlags[downloadId]?.set(true)
        cancelOngoingCalls(downloadId)
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)
    }

    private fun cancelOngoingCalls(downloadId: Long) {
        activeCalls[downloadId]?.toList()?.forEach { call ->
            try { call.cancel() } catch (_: Exception) {}
        }
    }

    private suspend fun performDownload(
        item: DownloadItem,
        cancelFlag: AtomicBoolean,
        pauseFlag: AtomicBoolean,
        onProgress: (Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (cancelFlag.get()) {
            store.updateStatus(item.id, DownloadStatus.CANCELLED)
            return
        }

        val targetFile = File(item.filePath)
        targetFile.parentFile?.mkdirs()

        // Probe URL using GET Range to support S3/R2 Presigned URLs, CDNs, and Google Drive
        val probeResult = probeUrl(item.url)
        if (cancelFlag.get()) {
            store.updateStatus(item.id, DownloadStatus.CANCELLED)
            return
        }

        val contentLength = probeResult.contentLength
        val acceptsRanges = probeResult.acceptsRanges

        if (contentLength > 0) {
            store.updateTotalSize(item.id, contentLength)
        }

        val threadCount = when {
            !acceptsRanges || contentLength <= 0 -> 1
            contentLength < 1_000_000 -> 1
            contentLength < 10_000_000 -> minOf(item.threads, 8)
            else -> item.threads.coerceIn(1, 100)
        }

        val chunkSize = if (contentLength > 0) contentLength / threadCount else 0L

        // Calculate already downloaded bytes from existing part files
        val initialBytes = if (acceptsRanges) {
            (0 until threadCount).sumOf { i ->
                val pFile = getPartFile(item.filePath, i, threadCount)
                if (pFile.exists()) pFile.length() else 0L
            }
        } else {
            cleanupParts(item.filePath, threadCount)
            0L
        }
        val totalDownloaded = AtomicLong(initialBytes)

        var lastSpeedCheck = System.currentTimeMillis()
        var lastSpeedBytes = initialBytes
        var smoothedSpeed = 0L

        // Speed & Progress Reporter with Exponential Moving Average (EMA)
        val progressJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && !cancelFlag.get() && !pauseFlag.get()) {
                delay(500)
                val now = System.currentTimeMillis()
                val elapsed = (now - lastSpeedCheck).coerceAtLeast(1)
                val current = totalDownloaded.get()
                val bytesDelta = (current - lastSpeedBytes).coerceAtLeast(0)
                val instantSpeed = (bytesDelta * 1000L) / elapsed

                // EMA smoothing (70% previous + 30% instant) for rock-solid speed & ETA
                smoothedSpeed = if (smoothedSpeed == 0L) {
                    instantSpeed
                } else {
                    ((smoothedSpeed * 7) + (instantSpeed * 3)) / 10
                }

                lastSpeedCheck = now
                lastSpeedBytes = current
                onProgress(current, smoothedSpeed.coerceAtLeast(0L))
            }
        }

        try {
            coroutineScope {
                val deferreds = (0 until threadCount).map { i ->
                    val partFile = getPartFile(item.filePath, i, threadCount)
                    val realStart = if (chunkSize > 0) i * chunkSize else 0L
                    val realEnd = if (chunkSize > 0) {
                        if (i == threadCount - 1) contentLength - 1 else (i + 1) * chunkSize - 1
                    } else -1L

                    async(downloadDispatcher) {
                        downloadPart(
                            downloadId = item.id,
                            url = item.url,
                            partFile = partFile,
                            chunkStart = realStart,
                            chunkEnd = realEnd,
                            totalDownloaded = totalDownloaded,
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
            store.updateStatus(item.id, DownloadStatus.CANCELLED)
            cleanupParts(item.filePath, threadCount)
            try { targetFile.delete() } catch (_: Exception) {}
            return
        }

        if (pauseFlag.get()) {
            val currentDownloaded = totalDownloaded.get()
            store.updateProgress(item.id, currentDownloaded, 0L, DownloadStatus.PAUSED)
            return
        }

        // All parts completed successfully! Merge parts into target file.
        mergeParts(targetFile, item.filePath, threadCount)

        onProgress(totalDownloaded.get(), 0L)
        onComplete()
    }

    private data class ProbeResult(val contentLength: Long, val acceptsRanges: Boolean)

    private fun probeUrl(url: String): ProbeResult {
        // Step 1: Probe with GET Range: bytes=0-0 (Standard browser method for S3/R2 presigned URLs)
        try {
            val rangeReq = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "*/*")
                .header("Range", "bytes=0-0")
                .build()

            okHttpClient.newCall(rangeReq).execute().use { response ->
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
            if (e.message?.startsWith("HTTP ") == true) throw e
        }

        // Step 2: Fallback to standard GET probe
        val getReq = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "*/*")
            .build()

        okHttpClient.newCall(getReq).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${getHttpErrorMessage(response.code)}")
            }
            val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val acceptsRanges = response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            return ProbeResult(length, acceptsRanges)
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
        totalDownloaded: AtomicLong,
        cancelFlag: AtomicBoolean,
        pauseFlag: AtomicBoolean,
        useRange: Boolean
    ) {
        val existingBytes = if (useRange && partFile.exists()) partFile.length() else 0L

        // Check if this chunk is already finished
        if (useRange && chunkEnd > 0 && chunkStart + existingBytes > chunkEnd) {
            return
        }

        val requestStart = if (useRange) chunkStart + existingBytes else 0L
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "*/*")

        if (useRange && chunkStart >= 0) {
            val rangeHeader = if (chunkEnd > 0) "bytes=$requestStart-$chunkEnd" else "bytes=$requestStart-"
            requestBuilder.header("Range", rangeHeader)
        }

        val call = okHttpClient.newCall(requestBuilder.build())
        val callList = activeCalls.computeIfAbsent(downloadId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }
        callList.add(call)

        val response: Response
        try {
            response = call.execute()
        } catch (e: IOException) {
            callList.remove(call)
            if (cancelFlag.get() || pauseFlag.get()) {
                return
            }
            throw e
        }

        if (!response.isSuccessful && response.code != 206) {
            callList.remove(call)
            val code = response.code
            response.close()
            throw IOException("HTTP $code: ${getHttpErrorMessage(code)}")
        }

        val body = response.body ?: run {
            callList.remove(call)
            response.close()
            throw IOException("Empty response body from server")
        }

        val buffer = ByteArray(65536) // 64KB buffer for high speed
        try {
            // Append directly to the part file for seamless resumption
            FileOutputStream(partFile, useRange).use { fileOut ->
                body.byteStream().use { stream ->
                    while (!cancelFlag.get() && !pauseFlag.get()) {
                        val read = stream.read(buffer)
                        if (read == -1) break
                        fileOut.write(buffer, 0, read)
                        totalDownloaded.addAndGet(read.toLong())
                    }
                    fileOut.flush()
                }
            }
        } catch (e: IOException) {
            if (!cancelFlag.get() && !pauseFlag.get()) {
                throw e
            }
        } finally {
            callList.remove(call)
            response.close()
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
