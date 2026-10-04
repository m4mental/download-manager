package com.example.speeddown.engine

import android.util.Log
import com.example.speeddown.data.DownloadItem
import com.example.speeddown.data.DownloadStatus
import com.example.speeddown.data.DownloadStore
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

        private val activeJobs = ConcurrentHashMap<Long, Job>()
        private val activeOwnershipTokens = ConcurrentHashMap<Long, String>()
        private val cancelFlags = ConcurrentHashMap<Long, AtomicBoolean>()
        private val pauseFlags = ConcurrentHashMap<Long, AtomicBoolean>()
        private val activeCalls = ConcurrentHashMap<Long, MutableList<okhttp3.Call>>()

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

        data class ContentRangeInfo(val start: Long, val end: Long, val total: Long?)

        fun parseContentRange(contentRange: String?): ContentRangeInfo? {
            if (contentRange.isNullOrBlank()) return null
            val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE).find(contentRange.trim())
                ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull()
            return ContentRangeInfo(start, end, total)
        }

        fun isValidContentRange(contentRange: String?, expectedStart: Long, expectedEnd: Long): Boolean {
            val info = parseContentRange(contentRange) ?: return false
            if (info.start != expectedStart) return false
            if (expectedEnd > 0 && info.end != expectedEnd) return false
            if (expectedEnd <= 0 && info.end < info.start) return false
            return true
        }

        fun shouldRestartFromZeroOn200(useRange: Boolean, statusCode: Int): Boolean {
            return useRange && statusCode == 200
        }

        fun isNumberedPartFileName(fileName: String, baseFileName: String): Boolean {
            val escaped = Regex.escape(baseFileName)
            return fileName.matches(Regex("""^$escaped\.part(\d+)?(\.helper)?$"""))
        }

        fun shouldDiscardPartsAndRestart(
            savedValidator: String?,
            newValidator: String?,
            savedLength: Long,
            newLength: Long,
            savedThreads: Int?,
            newThreads: Int
        ): Boolean {
            if (!savedValidator.isNullOrBlank() && !newValidator.isNullOrBlank() && savedValidator != newValidator) {
                return true
            }
            if (savedLength > 0 && newLength > 0 && savedLength != newLength) {
                return true
            }
            if (savedThreads != null && savedThreads > 0 && newThreads > 0 && savedThreads != newThreads) {
                return true
            }
            return false
        }

        fun selectRangeValidator(etag: String?, lastModified: String?): String? {
            if (!etag.isNullOrBlank() && !etag.trim().startsWith("W/", ignoreCase = true)) {
                return etag.trim()
            }
            return lastModified?.trim()?.takeIf { it.isNotBlank() }
        }

        fun publishSinglePart(partFile: File, targetFile: File): Boolean {
            if (!partFile.exists()) return false
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (partFile.renameTo(targetFile)) {
                return true
            }
            // Fallback: copy-and-delete
            return try {
                partFile.inputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 65536)
                    }
                }
                partFile.delete()
                true
            } catch (_: Exception) {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                false
            }
        }
    }

    private fun isYouTubeOrGoogleVideo(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("googlevideo.com") || lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    private val downloadDispatcher = Dispatchers.IO.limitedParallelism(256)

    /**
     * Dedicated HTTP/1.1 client for multi-part downloads.
     * Prevents HTTP/2 stream multiplexing where 100 threads choke on 1 single TCP connection.
     * With HTTP/1.1, each worker thread gets its own dedicated, unconstrained TCP socket.
     */
    private val chunkHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
    }

    suspend fun cancelAndJoinDownload(downloadId: Long) {
        cancelAndJoin(downloadId)
    }

    suspend fun pauseAndJoinDownload(downloadId: Long) {
        pauseAndJoin(downloadId)
    }

    fun startDownload(
        scope: CoroutineScope,
        item: DownloadItem,
        speedLimitKbps: Long = 0L,
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
                performDownload(item, speedLimitKbps, cancelFlag, pauseFlag, onProgress, onComplete, onError)
            } catch (_: CancellationException) {
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
                // Remove tracking entries ONLY when ownership token matches
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

    class RangedRequestReturned200Exception : IOException("Ranged request returned 200 OK - restarting single-threaded from zero")
    class RangeNotSatisfiable416Exception : IOException("HTTP 416: Range Not Satisfiable")

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

            var threadCount = when {
                isYouTubeOrGoogleVideo(item.url) -> 1
                !acceptsRanges || contentLength <= 0 -> 1
                contentLength < 1_000_000 -> 1
                contentLength < 10_000_000 -> minOf(item.threads, 8)
                else -> item.threads.coerceIn(1, 100)
            }

            store.updateTransferDetails(
                id = item.id,
                etag = probeResult.etag,
                lastModified = probeResult.lastModified,
                totalSize = contentLength,
                actualThreads = threadCount
            )

            if (shouldDiscardPartsAndRestart(
                    savedValidator = item.etag ?: item.lastModified,
                    newValidator = probeResult.etag ?: probeResult.lastModified,
                    savedLength = item.totalSize,
                    newLength = contentLength,
                    savedThreads = item.actualThreads,
                    newThreads = threadCount
                )
            ) {
                Log.w(TAG, "Validator, length, or partition layout changed on resume for ${item.id}. Discarding parts.")
                cleanupParts(item.filePath, item.actualThreads ?: item.threads)
                if (targetFile.exists()) targetFile.delete()
            }

            var success = false
            try {
                success = downloadStreamInternal(
                    downloadId = item.id,
                    url = item.url,
                    filePath = item.filePath,
                    contentLength = contentLength,
                    acceptsRanges = acceptsRanges,
                    threads = threadCount,
                    speedLimitKbps = speedLimitKbps,
                    cancelFlag = cancelFlag,
                    pauseFlag = pauseFlag,
                    baseDownloaded = 0L,
                    totalSizeForProgress = contentLength,
                    validator = selectRangeValidator(probeResult.etag, probeResult.lastModified),
                    onProgress = onProgress
                )
            } catch (_: RangedRequestReturned200Exception) {
                Log.w(TAG, "Ranged request returned 200 OK for ${item.id}. Deleting all parts and restarting single-threaded from zero.")
                cleanupParts(item.filePath, threadCount)
                if (targetFile.exists()) targetFile.delete()
                threadCount = 1
                store.updateProgress(item.id, 0L, 0L, DownloadStatus.DOWNLOADING, emptyList(), forceReset = true)
                store.updateTransferDetails(
                    id = item.id,
                    etag = probeResult.etag,
                    lastModified = probeResult.lastModified,
                    totalSize = contentLength,
                    actualThreads = 1
                )
                success = downloadStreamInternal(
                    downloadId = item.id,
                    url = item.url,
                    filePath = item.filePath,
                    contentLength = contentLength,
                    acceptsRanges = false,
                    threads = 1,
                    speedLimitKbps = speedLimitKbps,
                    cancelFlag = cancelFlag,
                    pauseFlag = pauseFlag,
                    baseDownloaded = 0L,
                    totalSizeForProgress = contentLength,
                    validator = null,
                    onProgress = onProgress
                )
            }

            if (success && !cancelFlag.get() && !pauseFlag.get()) {
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
                var videoSuccess = false
                var videoThreads = item.threads
                try {
                    videoSuccess = downloadStreamInternal(
                        downloadId = item.id,
                        url = item.url,
                        filePath = videoTempPath,
                        contentLength = videoLength,
                        acceptsRanges = videoProbe.acceptsRanges,
                        threads = videoThreads,
                        speedLimitKbps = speedLimitKbps,
                        cancelFlag = cancelFlag,
                        pauseFlag = pauseFlag,
                        baseDownloaded = 0L,
                        totalSizeForProgress = combinedTotal,
                        validator = selectRangeValidator(videoProbe.etag, videoProbe.lastModified),
                        onProgress = onProgress
                    )
                } catch (_: RangedRequestReturned200Exception) {
                    Log.w(TAG, "Video stream ranged request returned 200 OK. Restarting single-threaded.")
                    cleanupParts(videoTempPath, videoThreads)
                    if (videoTempFile.exists()) videoTempFile.delete()
                    videoThreads = 1
                    store.updateProgress(item.id, 0L, 0L, DownloadStatus.DOWNLOADING, emptyList(), forceReset = true)
                    videoSuccess = downloadStreamInternal(
                        downloadId = item.id,
                        url = item.url,
                        filePath = videoTempPath,
                        contentLength = videoLength,
                        acceptsRanges = false,
                        threads = 1,
                        speedLimitKbps = speedLimitKbps,
                        cancelFlag = cancelFlag,
                        pauseFlag = pauseFlag,
                        baseDownloaded = 0L,
                        totalSizeForProgress = combinedTotal,
                        validator = null,
                        onProgress = onProgress
                    )
                }
                if (!videoSuccess || cancelFlag.get() || pauseFlag.get()) return
            }

            // 2. Download Audio Stream
            val effectiveVideoBytes = if (videoLength > 0) videoLength else videoTempFile.length()
            val isAudioDone = audioTempFile.exists() && (audioLength <= 0 || audioTempFile.length() >= audioLength)
            if (!isAudioDone) {
                var audioThreads = if (isYouTubeOrGoogleVideo(item.audioUrl)) 1 else minOf(item.threads, 4)
                var audioSuccess = false
                try {
                    audioSuccess = downloadStreamInternal(
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
                        validator = selectRangeValidator(audioProbe.etag, audioProbe.lastModified),
                        onProgress = onProgress
                    )
                } catch (_: RangedRequestReturned200Exception) {
                    Log.w(TAG, "Audio stream ranged request returned 200 OK. Restarting single-threaded.")
                    cleanupParts(audioTempPath, audioThreads)
                    if (audioTempFile.exists()) audioTempFile.delete()
                    audioThreads = 1
                    store.updateProgress(item.id, effectiveVideoBytes, 0L, DownloadStatus.DOWNLOADING, emptyList(), forceReset = true)
                    audioSuccess = downloadStreamInternal(
                        downloadId = item.id,
                        url = item.audioUrl,
                        filePath = audioTempPath,
                        contentLength = audioLength,
                        acceptsRanges = false,
                        threads = 1,
                        speedLimitKbps = speedLimitKbps,
                        cancelFlag = cancelFlag,
                        pauseFlag = pauseFlag,
                        baseDownloaded = effectiveVideoBytes,
                        totalSizeForProgress = combinedTotal,
                        validator = null,
                        onProgress = onProgress
                    )
                }
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
                // Delete .video.tmp and .audio.tmp source files ONLY after mux success
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
        validator: String? = null,
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

        // Track bytes per part for live segmented visualizer
        val partProgressBytes = Array(threadCount) { i ->
            val pFile = getPartFile(filePath, i, threadCount)
            AtomicLong(if (acceptsRanges && pFile.exists()) pFile.length() else 0L)
        }

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
        val maxReported = AtomicLong(baseDownloaded + initialBytes)

        val progressJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && !cancelFlag.get() && !pauseFlag.get()) {
                delay(500)
                val now = System.currentTimeMillis()
                val elapsed = (now - lastSpeedCheck).coerceAtLeast(1)
                val currentStream = streamDownloaded.get()
                val bytesDelta = (currentStream - lastSpeedBytes).coerceAtLeast(0)
                val instantSpeed = (bytesDelta * 1000L) / elapsed

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

                val rawTotal = baseDownloaded + currentStream
                val safeTotal = maxReported.updateAndGet { prev -> maxOf(prev, rawTotal) }
                val reportedTotal = if (contentLength > 0) minOf(safeTotal, contentLength) else safeTotal
                onProgress(reportedTotal, smoothedSpeed.coerceAtLeast(0L), parts)
            }
        }

        val trackers = (0 until threadCount).map { i ->
            val partFile = getPartFile(filePath, i, threadCount)
            val realStart = if (chunkSize > 0) i * chunkSize else 0L
            val realEnd = if (chunkSize > 0) {
                if (i == threadCount - 1) contentLength - 1 else (i + 1) * chunkSize - 1
            } else -1L
            PartTracker(
                partIndex = i,
                chunkStart = realStart,
                chunkEnd = realEnd,
                partFile = partFile,
                partProgressBytes = partProgressBytes[i]
            )
        }
        val activeThreadsCount = AtomicInteger(threadCount)

        try {
            coroutineScope {
                val deferreds = (0 until threadCount).map { i ->
                    val myTracker = trackers[i]
                    async(downloadDispatcher) {
                        try {
                            downloadPart(
                                downloadId = downloadId,
                                url = url,
                                partFile = myTracker.partFile,
                                chunkStart = myTracker.chunkStart,
                                chunkEnd = myTracker.chunkEnd,
                                partProgressBytes = myTracker.partProgressBytes,
                                totalDownloaded = streamDownloaded,
                                speedLimitKbps = speedLimitKbps,
                                threadCount = threadCount,
                                cancelFlag = cancelFlag,
                                pauseFlag = pauseFlag,
                                useRange = acceptsRanges && myTracker.chunkEnd > 0,
                                validator = validator,
                                activeThreadsCount = activeThreadsCount,
                                dynamicEndSupplier = { myTracker.chunkEnd }
                            )
                        } finally {
                            myTracker.isCompleted.set(true)
                            activeThreadsCount.decrementAndGet()
                        }

                        // Endgame Work-Stealing:
                        // When this worker finishes its own chunk early, help any unfinished straggler chunk (> 256 KB)
                        if (acceptsRanges && threadCount > 1) {
                            while (!cancelFlag.get() && !pauseFlag.get()) {
                                if (activeThreadsCount.get() <= 0) break

                                val candidate = trackers
                                    .filter { !it.isCompleted.get() && !it.isBeingHelped.get() }
                                    .maxByOrNull { t ->
                                        val dl = t.partProgressBytes.get()
                                        val totalExp = t.chunkEnd - t.chunkStart + 1
                                        totalExp - dl
                                    } ?: break

                                val currentDownloaded = candidate.partProgressBytes.get()
                                val totalExp = candidate.chunkEnd - candidate.chunkStart + 1
                                val remaining = totalExp - currentDownloaded

                                if (remaining < 256 * 1024L) break
                                if (!candidate.isBeingHelped.compareAndSet(false, true)) continue

                                val originalCandidateEnd = candidate.chunkEnd
                                val half = remaining / 2
                                val helperStart = candidate.chunkStart + currentDownloaded + half
                                val helperEnd = originalCandidateEnd
                                val newCandidateEnd = helperStart - 1

                                candidate.chunkEnd = newCandidateEnd

                                val helperFile = File(candidate.partFile.parentFile, "${candidate.partFile.name}.helper")
                                if (helperFile.exists()) helperFile.delete()

                                val helperProgress = AtomicLong(0L)
                                var helperSuccess = false
                                activeThreadsCount.incrementAndGet()
                                try {
                                    downloadPart(
                                        downloadId = downloadId,
                                        url = url,
                                        partFile = helperFile,
                                        chunkStart = helperStart,
                                        chunkEnd = helperEnd,
                                        partProgressBytes = helperProgress,
                                        totalDownloaded = streamDownloaded,
                                        speedLimitKbps = speedLimitKbps,
                                        threadCount = threadCount,
                                        cancelFlag = cancelFlag,
                                        pauseFlag = pauseFlag,
                                        useRange = true,
                                        validator = validator,
                                        activeThreadsCount = activeThreadsCount,
                                        dynamicEndSupplier = null
                                    )
                                    val expectedHelperBytes = helperEnd - helperStart + 1
                                    if (helperFile.exists() && helperFile.length() == expectedHelperBytes) {
                                        helperSuccess = true
                                    }
                                } catch (_: Exception) {
                                    helperSuccess = false
                                } finally {
                                    activeThreadsCount.decrementAndGet()
                                }

                                if (helperSuccess) {
                                    // Wait until candidate thread finishes writing its lower half and closes its stream
                                    while (!candidate.isCompleted.get() && !cancelFlag.get() && !pauseFlag.get()) {
                                        delay(50L)
                                    }
                                    val expectedCandidateLowerBytes = helperStart - candidate.chunkStart
                                    if (!cancelFlag.get() && !pauseFlag.get() &&
                                        candidate.partFile.exists() && candidate.partFile.length() == expectedCandidateLowerBytes) {
                                        appendFile(helperFile, candidate.partFile)
                                        candidate.partProgressBytes.set(candidate.partFile.length())
                                        candidate.chunkEnd = originalCandidateEnd
                                    }
                                    if (helperFile.exists()) helperFile.delete()
                                } else {
                                    if (helperFile.exists()) helperFile.delete()
                                    if (!candidate.isCompleted.get()) {
                                        candidate.chunkEnd = originalCandidateEnd
                                        candidate.isBeingHelped.set(false)
                                    }
                                }
                            }
                        }
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

    private data class ProbeResult(
        val contentLength: Long,
        val acceptsRanges: Boolean,
        val etag: String? = null,
        val lastModified: String? = null
    )

    private fun probeUrl(downloadId: Long, url: String, cancelFlag: AtomicBoolean): ProbeResult {
        if (cancelFlag.get()) return ProbeResult(-1L, false)

        val callList = activeCalls.computeIfAbsent(downloadId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }

        val isYt = isYouTubeOrGoogleVideo(url)
        val userAgent = if (isYt) YOUTUBE_USER_AGENT else BROWSER_USER_AGENT

        // Step 1: Probe with GET Range: bytes=0-0
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
                    val etag = response.header("ETag")
                    val lastModified = response.header("Last-Modified")

                    if (response.code == 206) {
                        val contentRange = response.header("Content-Range")
                        val totalFromRange = contentRange?.substringAfterLast("/")?.toLongOrNull() ?: -1L
                        val length = if (totalFromRange > 0) totalFromRange else (response.header("Content-Length")?.toLongOrNull() ?: -1L)
                        return ProbeResult(length, acceptsRanges = true, etag = etag, lastModified = lastModified)
                    } else if (response.isSuccessful) {
                        val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
                        val ranges = response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                        return ProbeResult(length, acceptsRanges = ranges, etag = etag, lastModified = lastModified)
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
                val etag = response.header("ETag")
                val lastModified = response.header("Last-Modified")
                return ProbeResult(length, acceptsRanges, etag = etag, lastModified = lastModified)
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
                val helper = File(f.parentFile, "${f.name}.helper")
                if (helper.exists()) helper.delete()
            } catch (_: Exception) {}
        }
        val singlePart = File("$filePath.part")
        if (singlePart.exists()) {
            try { singlePart.delete() } catch (_: Exception) {}
        }
    }

    private class PartTracker(
        val partIndex: Int,
        val chunkStart: Long,
        @Volatile var chunkEnd: Long,
        val partFile: File,
        val partProgressBytes: AtomicLong,
        val isCompleted: AtomicBoolean = AtomicBoolean(false),
        val isBeingHelped: AtomicBoolean = AtomicBoolean(false)
    )

    private fun appendFile(source: File, destination: File) {
        if (!source.exists()) return
        val inStream = FileInputStream(source)
        val outStream = FileOutputStream(destination, true)
        inStream.use { input ->
            outStream.use { output ->
                input.copyTo(output, bufferSize = 262144)
            }
        }
    }

    private fun mergeParts(targetFile: File, filePath: String, threadCount: Int) {
        if (threadCount == 1) {
            val singlePart = getPartFile(filePath, 0, 1)
            if (singlePart.exists()) {
                val published = publishSinglePart(singlePart, targetFile)
                if (!published) {
                    throw IOException("Failed to publish single-part download to ${targetFile.name}")
                }
            }
            return
        }

        if (targetFile.exists()) targetFile.delete()
        val rawOut = FileOutputStream(targetFile)
        BufferedOutputStream(rawOut, 512 * 1024).use { outputStream ->
            for (i in 0 until threadCount) {
                val pFile = getPartFile(filePath, i, threadCount)
                if (pFile.exists()) {
                    pFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream, bufferSize = 262144)
                    }
                    pFile.delete()
                }
            }
            outputStream.flush()
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
        useRange: Boolean,
        validator: String? = null,
        activeThreadsCount: AtomicInteger? = null,
        dynamicEndSupplier: (() -> Long)? = null
    ) {
        val isYt = isYouTubeOrGoogleVideo(url)
        val userAgent = if (isYt) YOUTUBE_USER_AGENT else BROWSER_USER_AGENT

        val callList = activeCalls.computeIfAbsent(downloadId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }

        // Use dedicated HTTP/1.1 client for multi-threaded chunk downloads to avoid HTTP/2 single-connection multiplex bottlenecks
        val clientToUse = if (threadCount > 1 && !isYt) chunkHttpClient else okHttpClient

        var attempts = 0
        val maxAttempts = 6
        var lastDownloadedBytes = -1L
        while (attempts < maxAttempts && !cancelFlag.get() && !pauseFlag.get()) {
            val currentDynamicEnd = dynamicEndSupplier?.invoke() ?: chunkEnd
            val expectedLength = if (currentDynamicEnd > 0) (currentDynamicEnd - chunkStart + 1) else -1L

            val existingBytes = if (useRange && partFile.exists()) partFile.length() else 0L
            partProgressBytes.set(existingBytes)

            if (useRange && expectedLength > 0 && existingBytes >= expectedLength) {
                if (existingBytes > expectedLength) {
                    try {
                        java.io.RandomAccessFile(partFile, "rw").use { it.setLength(expectedLength) }
                    } catch (_: Exception) {}
                    partProgressBytes.set(expectedLength)
                }
                return
            }

            // If progress was made since last attempt, reset failure counter so large downloads don't fail after multiple auto-refreshes
            if (existingBytes > lastDownloadedBytes) {
                attempts = 0
                lastDownloadedBytes = existingBytes
            }
            attempts++

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
                val targetEnd = dynamicEndSupplier?.invoke() ?: chunkEnd
                val rangeHeader = if (targetEnd > 0) "bytes=$requestStart-$targetEnd" else "bytes=$requestStart-"
                requestBuilder.header("Range", rangeHeader)
                val safeValidator = if (!validator.isNullOrBlank() && !validator.trim().startsWith("W/", ignoreCase = true)) {
                    validator.trim()
                } else null
                if (!safeValidator.isNullOrBlank()) {
                    requestBuilder.header("If-Range", safeValidator)
                }
            }

            val call = clientToUse.newCall(requestBuilder.build())
            callList.add(call)

            try {
                val response = call.execute()
                val code = response.code

                if (shouldRestartFromZeroOn200(useRange, code)) {
                    response.close()
                    throw RangedRequestReturned200Exception()
                }

                if (code == 416) {
                    response.close()
                    Log.w(TAG, "HTTP 416 Range Not Satisfiable on part $chunkStart for download $downloadId. Resetting part.")
                    if (partFile.exists()) partFile.delete()
                    partProgressBytes.set(0L)
                    if (attempts < maxAttempts) {
                        delay(1000L)
                        continue
                    }
                    throw RangeNotSatisfiable416Exception()
                }

                if (useRange && code != 206) {
                    response.close()
                    if ((code == 429 || code == 403) && attempts < maxAttempts) {
                        Log.w(TAG, "Server responded with HTTP $code, retrying ($attempts/$maxAttempts) in ${attempts * 1500}ms...")
                        delay(attempts * 1500L)
                        continue
                    }
                    throw IOException("HTTP $code: Expected 206 Partial Content but received $code")
                }

                if (!response.isSuccessful && code != 206) {
                    response.close()
                    throw IOException("HTTP $code: ${getHttpErrorMessage(code)}")
                }

                if (code == 206) {
                    val contentRange = response.header("Content-Range")
                    val targetEnd = dynamicEndSupplier?.invoke() ?: chunkEnd
                    if (!isValidContentRange(contentRange, requestStart, targetEnd)) {
                        response.close()
                        throw IOException("HTTP 206 Content-Range mismatch: '$contentRange', expected start $requestStart, end $targetEnd")
                    }
                }

                val body = response.body ?: run {
                    response.close()
                    throw IOException("Empty response body from server")
                }

                val buffer = ByteArray(131072)
                val rawOut = FileOutputStream(partFile, useRange)
                BufferedOutputStream(rawOut, 256 * 1024).use { fileOut ->
                    body.byteStream().use { stream ->
                        var uncommittedBytes = 0L
                        var intervalStartTime = System.currentTimeMillis()
                        var intervalBytesRead = 0L

                        while (!cancelFlag.get() && !pauseFlag.get()) {
                            val curEnd = dynamicEndSupplier?.invoke() ?: chunkEnd
                            val curExpectedLen = if (curEnd > 0) (curEnd - chunkStart + 1) else expectedLength
                            val currentPartLen = partProgressBytes.get() + uncommittedBytes
                            val remainingExpected = if (curExpectedLen > 0) (curExpectedLen - currentPartLen) else Long.MAX_VALUE
                            if (remainingExpected <= 0) break

                            val read = stream.read(buffer)
                            if (read == -1) break

                            val now = System.currentTimeMillis()
                            intervalBytesRead += read

                            val maxToWrite = minOf(read.toLong(), remainingExpected).toInt()
                            if (maxToWrite > 0) {
                                fileOut.write(buffer, 0, maxToWrite)
                                uncommittedBytes += maxToWrite
                                if (uncommittedBytes >= 65536L || (curExpectedLen > 0 && currentPartLen + maxToWrite >= curExpectedLen)) {
                                    partProgressBytes.addAndGet(uncommittedBytes)
                                    totalDownloaded.addAndGet(uncommittedBytes)
                                    uncommittedBytes = 0L
                                }
                            }

                            // If this part has received all its expected bytes, finish reading this stream cleanly!
                            if (curExpectedLen > 0 && (currentPartLen + maxToWrite >= curExpectedLen)) {
                                break
                            }

                            if (speedLimitKbps > 0) {
                                val perThreadBps = (speedLimitKbps * 1024L) / java.lang.Math.max(1, threadCount)
                                val sleepMs = ((read.toDouble() / perThreadBps) * 1000.0).toLong()
                                if (sleepMs > 0) {
                                    delay(sleepMs.coerceAtMost(250L))
                                }
                            }

                            // Dynamic connection stall auto-refresh & Endgame burst accelerator:
                            // In endgame (few active threads remaining), detect stalls within 3s and require >= 150 KB/s
                            if (useRange && threadCount > 1 && remainingExpected > 32 * 1024L) {
                                val remainingActive = activeThreadsCount?.get() ?: threadCount
                                val isEndgame = remainingActive <= maxOf(3, threadCount / 10)
                                val checkIntervalMs = if (isEndgame) 3000L else 8000L
                                val intervalElapsed = now - intervalStartTime

                                if (intervalElapsed >= checkIntervalMs) {
                                    val intervalSpeedBps = (intervalBytesRead * 1000L) / intervalElapsed
                                    val stallThresholdBps = if (isEndgame) {
                                        150 * 1024L // Fast burst threshold in endgame
                                    } else {
                                        (10 * 1024L) / maxOf(1, threadCount / 8)
                                    }

                                    if (intervalSpeedBps < stallThresholdBps) {
                                        Log.w(TAG, "Part $chunkStart stalled/throttled (${intervalSpeedBps / 1024} KB/s < ${stallThresholdBps / 1024} KB/s, endgame=$isEndgame), auto-refreshing connection...")
                                        throw SocketTimeoutException("Connection throttled by server, auto-accelerating...")
                                    }
                                    intervalStartTime = now
                                    intervalBytesRead = 0L
                                }
                            }
                        }
                        if (uncommittedBytes > 0) {
                            partProgressBytes.addAndGet(uncommittedBytes)
                            totalDownloaded.addAndGet(uncommittedBytes)
                            uncommittedBytes = 0L
                        }
                        fileOut.flush()
                    }
                }
                response.close()

                val finalPartLen = partFile.length()
                val curTargetEnd = dynamicEndSupplier?.invoke() ?: chunkEnd
                val curExpectedLen = if (curTargetEnd > 0) (curTargetEnd - chunkStart + 1) else expectedLength
                if (curExpectedLen > 0) {
                    if (finalPartLen > curExpectedLen) {
                        try {
                            java.io.RandomAccessFile(partFile, "rw").use { it.setLength(curExpectedLen) }
                        } catch (_: Exception) {}
                        partProgressBytes.set(curExpectedLen)
                        return
                    }
                    if (finalPartLen < curExpectedLen) {
                        if (!useRange) {
                            totalDownloaded.addAndGet(-finalPartLen)
                            partProgressBytes.set(0L)
                        }
                        if (attempts >= maxAttempts) {
                            if (!cancelFlag.get() && !pauseFlag.get()) {
                                throw IOException("Short part received: $finalPartLen of expected $curExpectedLen bytes after $maxAttempts attempts")
                            }
                            return
                        }
                        Log.w(TAG, "Short part received: $finalPartLen < $curExpectedLen. Retrying from offset...")
                        continue
                    }
                }
                return
            } catch (e: IOException) {
                if (cancelFlag.get() || pauseFlag.get()) {
                    return
                }
                if (e is RangedRequestReturned200Exception || e is RangeNotSatisfiable416Exception) {
                    throw e
                }
                val isConnectionDrop = e is SocketTimeoutException ||
                        e is ConnectException ||
                        e is java.net.SocketException ||
                        e is java.io.EOFException ||
                        e is SSLException ||
                        e.message?.contains("unexpected end of stream", ignoreCase = true) == true ||
                        e.message?.contains("reset", ignoreCase = true) == true ||
                        e.message?.contains("pipe", ignoreCase = true) == true ||
                        e.message?.contains("connection abort", ignoreCase = true) == true ||
                        e.message?.contains("auto-accelerating", ignoreCase = true) == true

                if (attempts < maxAttempts && isConnectionDrop) {
                    val isAutoAccelerate = e.message?.contains("auto-accelerating", ignoreCase = true) == true
                    val retryDelay = if (isAutoAccelerate) 100L else (attempts * 1000L)
                    Log.w(TAG, "Chunk $chunkStart connection dropped/re-accelerating (${e.message}), auto-retrying in ${retryDelay}ms...")
                    delay(retryDelay)
                    continue
                }
                throw e
            } finally {
                callList.remove(call)
            }
        }
        val finalExpectedLen = run {
            val curEnd = dynamicEndSupplier?.invoke() ?: chunkEnd
            if (curEnd > 0) (curEnd - chunkStart + 1) else -1L
        }
        if (!cancelFlag.get() && !pauseFlag.get() && finalExpectedLen > 0 && partFile.length() < finalExpectedLen) {
            throw IOException("Part incomplete: ${partFile.length()} of expected $finalExpectedLen bytes after $attempts attempts")
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

