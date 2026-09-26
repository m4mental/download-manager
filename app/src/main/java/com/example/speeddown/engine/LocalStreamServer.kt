package com.example.speeddown.engine

import android.util.Log
import com.example.speeddown.data.DownloadStore
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Embedded ultra-lightweight progressive HTTP stream server for Nothing Player integration.
 * Allows Nothing Player (or any media player) to stream audio/video files progressively
 * across multi-part download chunks while SpeedDown is still downloading them in the background.
 */
class LocalStreamServer private constructor(
    private val store: DownloadStore
) {
    companion object {
        private const val TAG = "LocalStreamServer"
        const val DEFAULT_PORT = 8998

        @Volatile
        private var instance: LocalStreamServer? = null

        fun getInstance(store: DownloadStore): LocalStreamServer {
            return instance ?: synchronized(this) {
                instance ?: LocalStreamServer(store).also { instance = it }
            }
        }
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val port: Int
        get() = serverSocket?.localPort ?: DEFAULT_PORT

    fun start() {
        if (isRunning.getAndSet(true)) return

        serverJob = scope.launch {
            try {
                // Try preferred port or bind to any available free port
                serverSocket = try {
                    ServerSocket(DEFAULT_PORT)
                } catch (_: Exception) {
                    ServerSocket(0)
                }
                Log.d(TAG, "LocalStreamServer started on port ${serverSocket?.localPort}")

                while (isActive && isRunning.get()) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        launch(Dispatchers.IO) {
                            handleClient(client)
                        }
                    } catch (se: SocketException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error accepting stream client", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LocalStreamServer startup failed", e)
            } finally {
                isRunning.set(false)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverJob?.cancel()
        serverSocket = null
        Log.d(TAG, "LocalStreamServer stopped")
    }

    fun getStreamUrl(downloadId: Long, fileName: String = "video.mp4"): String {
        start()
        val safeName = fileName.replace(" ", "%20")
        return "http://127.0.0.1:$port/stream/$downloadId/$safeName"
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        try {
            client.soTimeout = 30000
            val input = BufferedReader(InputStreamReader(client.getInputStream()))
            val rawOutput = client.getOutputStream()

            val requestLine = input.readLine() ?: return@withContext
            Log.d(TAG, "Stream Request: $requestLine")

            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                sendError(rawOutput, 400, "Bad Request")
                return@withContext
            }

            // Parse headers
            var rangeHeader: String? = null
            var line: String? = input.readLine()
            while (!line.isNullOrBlank()) {
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
                line = input.readLine()
            }

            val path = parts[1] // /stream/{id}/{fileName}
            val segments = path.split("/").filter { it.isNotBlank() }
            if (segments.size < 2 || segments[0] != "stream") {
                sendError(rawOutput, 404, "Not Found")
                return@withContext
            }

            val downloadId = segments[1].toLongOrNull()
            if (downloadId == null) {
                sendError(rawOutput, 400, "Invalid Download ID")
                return@withContext
            }

            val item = store.getById(downloadId)
            if (item == null) {
                sendError(rawOutput, 404, "Download item not found")
                return@withContext
            }

            serveMedia(client, rawOutput, item, rangeHeader)
        } catch (e: Exception) {
            Log.w(TAG, "Client streaming disconnected: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private data class FileResolution(val file: File?, val offsetInFile: Long, val maxLimitInPart: Long)

    private fun resolveVirtualFile(
        basePath: String,
        offset: Long,
        chunkSize: Long,
        threadCount: Int,
        totalSize: Long
    ): FileResolution {
        val target = File(basePath)
        if (target.exists() && target.length() > 0) {
            return FileResolution(target, offset, totalSize)
        }
        val singlePart = File("$basePath.part")
        if (singlePart.exists() && singlePart.length() > 0) {
            return FileResolution(singlePart, offset, totalSize)
        }

        if (chunkSize > 0 && threadCount > 1) {
            val partIdx = (offset / chunkSize).toInt().coerceIn(0, threadCount - 1)
            val partStart = partIdx * chunkSize
            val offsetInPart = offset - partStart
            val maxLimit = if (partIdx == threadCount - 1) (totalSize - partStart) else chunkSize
            val partFile = File("$basePath.part$partIdx")
            return FileResolution(partFile, offsetInPart, maxLimit)
        }

        val part0 = File("$basePath.part0")
        return FileResolution(if (part0.exists()) part0 else null, offset, totalSize)
    }

    private suspend fun serveMedia(
        client: Socket,
        output: OutputStream,
        item: com.example.speeddown.data.DownloadItem,
        rangeHeader: String?
    ) {
        val targetFile = File(item.filePath)
        val singlePart = File(item.filePath + ".part")
        val part0 = File(item.filePath + ".part0")

        val hasData = (targetFile.exists() && targetFile.length() > 0) ||
                      (singlePart.exists() && singlePart.length() > 0) ||
                      (part0.exists() && part0.length() > 0)

        if (!hasData) {
            sendError(output, 404, "No streamable data ready yet")
            return
        }

        val totalSize = if (item.totalSize > 0) {
            item.totalSize
        } else if (targetFile.exists()) {
            targetFile.length()
        } else if (singlePart.exists()) {
            singlePart.length()
        } else {
            part0.length()
        }

        val mimeType = getMimeType(item.fileName)
        var startByte = 0L
        var endByte = totalSize - 1

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeVal = rangeHeader.removePrefix("bytes=").trim()
            val dashIdx = rangeVal.indexOf('-')
            if (dashIdx >= 0) {
                val startStr = rangeVal.substring(0, dashIdx).trim()
                val endStr = rangeVal.substring(dashIdx + 1).trim()
                if (startStr.isNotBlank()) startByte = startStr.toLongOrNull() ?: 0L
                if (endStr.isNotBlank()) endByte = (endStr.toLongOrNull() ?: (totalSize - 1)).coerceAtMost(totalSize - 1)
            }
        }

        val contentLength = (endByte - startByte + 1).coerceAtLeast(0L)
        val isPartial = rangeHeader != null

        val responseHeader = buildString {
            append(if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: $mimeType\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (isPartial) {
                append("Content-Range: bytes $startByte-$endByte/$totalSize\r\n")
            }
            append("Content-Length: $contentLength\r\n")
            append("Connection: keep-alive\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }

        output.write(responseHeader.toByteArray(Charsets.UTF_8))
        output.flush()

        // Stream data bytes with multi-part virtual file resolution and live polling
        val buffer = ByteArray(64 * 1024)
        var currentOffset = startByte
        val threadCount = item.threads.coerceAtLeast(1)
        val chunkSize = if (threadCount > 1 && totalSize > 0) totalSize / threadCount else 0L

        try {
            while (currentOffset <= endByte && !client.isClosed) {
                val res = resolveVirtualFile(item.filePath, currentOffset, chunkSize, threadCount, totalSize)
                val activeFile = res.file
                val targetOffset = res.offsetInFile
                val partLimit = res.maxLimitInPart

                if (activeFile == null || !activeFile.exists()) {
                    // Wait up to 3 seconds for downloader to create part
                    var waited = 0
                    while (waited < 30 && !client.isClosed) {
                        delay(100)
                        waited++
                        val check = resolveVirtualFile(item.filePath, currentOffset, chunkSize, threadCount, totalSize).file
                        if (check != null && check.exists() && check.length() > targetOffset) {
                            break
                        }
                    }
                }

                val currentFile = resolveVirtualFile(item.filePath, currentOffset, chunkSize, threadCount, totalSize).file
                if (currentFile == null || !currentFile.exists()) {
                    break
                }

                // If downloader hasn't written bytes up to targetOffset yet, poll briefly
                if (currentFile.length() <= targetOffset) {
                    var waited = 0
                    while (waited < 40 && !client.isClosed && currentFile.length() <= targetOffset) {
                        delay(100)
                        waited++
                    }
                    if (currentFile.length() <= targetOffset) {
                        break
                    }
                }

                val availableNow = (currentFile.length() - targetOffset).coerceAtLeast(0L)
                val remainingInPart = if (partLimit > 0) (partLimit - targetOffset).coerceAtLeast(0L) else availableNow
                val bytesToRead = minOf(buffer.size.toLong(), availableNow, remainingInPart, endByte - currentOffset + 1).toInt()

                if (bytesToRead <= 0) {
                    break
                }

                RandomAccessFile(currentFile, "r").use { raf ->
                    raf.seek(targetOffset)
                    val read = raf.read(buffer, 0, bytesToRead)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        currentOffset += read
                    }
                }
            }
            output.flush()
        } catch (_: SocketException) {
            // Player stopped, paused or seeked
        } catch (e: Exception) {
            Log.d(TAG, "Stream write ended: ${e.message}")
        }
    }

    private fun sendError(output: OutputStream, code: Int, msg: String) {
        val response = "HTTP/1.1 $code $msg\r\nContent-Type: text/plain\r\nContent-Length: ${msg.length}\r\n\r\n$msg"
        try {
            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (_: Exception) {}
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            else -> "video/mp4"
        }
    }
}
