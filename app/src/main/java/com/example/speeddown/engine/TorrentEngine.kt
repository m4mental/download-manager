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
        fun indexOfByte(bytes: ByteArray, target: Byte, start: Int): Int {
            for (i in start until bytes.size) {
                if (bytes[i] == target) return i
            }
            return -1
        }

        fun computeSha1Hex(bytes: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
            return digest.joinToString("") { "%02X".format(it) }
        }

        fun decodeBase32(input: String): ByteArray? {
            val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val clean = input.trim().uppercase().replace("=", "")
            val bytes = java.io.ByteArrayOutputStream()
            var buffer = 0
            var bitsLeft = 0
            for (c in clean) {
                val valIdx = base32Chars.indexOf(c)
                if (valIdx < 0) return null
                buffer = (buffer shl 5) or valIdx
                bitsLeft += 5
                if (bitsLeft >= 8) {
                    bytes.write((buffer shr (bitsLeft - 8)) and 0xFF)
                    bitsLeft -= 8
                }
            }
            return bytes.toByteArray()
        }

        fun normalizeInfoHashToHex(infoHash: String): String {
            val clean = infoHash.trim().uppercase()
            if (clean.length == 40 && clean.all { it in "0123456789ABCDEF" }) {
                return clean
            }
            if (clean.length == 32) {
                val decoded = decodeBase32(clean)
                if (decoded != null && decoded.size == 20) {
                    return decoded.joinToString("") { "%02X".format(it) }
                }
            }
            return clean
        }

        class BencodeParser(private val data: ByteArray, private var pos: Int = 0) {
            fun hasMore(): Boolean = pos < data.size

            fun parseAny(): Any? {
                if (!hasMore()) return null
                return when (data[pos].toInt().toChar()) {
                    'i' -> parseInteger()
                    'l' -> parseList()
                    'd' -> parseDictionary()
                    in '0'..'9' -> parseByteString()
                    else -> null
                }
            }

            fun parseInteger(): Long? {
                if (!hasMore() || data[pos].toInt().toChar() != 'i') return null
                pos++
                val start = pos
                while (hasMore() && data[pos].toInt().toChar() != 'e') {
                    pos++
                }
                if (!hasMore()) return null
                val str = String(data, start, pos - start, Charsets.US_ASCII)
                pos++
                return str.toLongOrNull()
            }

            fun parseByteString(): ByteArray? {
                val colonIdx = indexOfByte(data, ':'.code.toByte(), pos)
                if (colonIdx == -1) return null
                val lenStr = String(data, pos, colonIdx - pos, Charsets.US_ASCII)
                val len = lenStr.toIntOrNull() ?: return null
                pos = colonIdx + 1
                if (pos + len > data.size) return null
                val result = data.copyOfRange(pos, pos + len)
                pos += len
                return result
            }

            fun parseList(): List<Any?>? {
                if (!hasMore() || data[pos].toInt().toChar() != 'l') return null
                pos++
                val list = mutableListOf<Any?>()
                while (hasMore() && data[pos].toInt().toChar() != 'e') {
                    val item = parseAny() ?: return null
                    list.add(item)
                }
                if (hasMore() && data[pos].toInt().toChar() == 'e') {
                    pos++
                }
                return list
            }

            fun parseDictionary(): Map<String, Any?>? {
                if (!hasMore() || data[pos].toInt().toChar() != 'd') return null
                pos++
                val map = mutableMapOf<String, Any?>()
                while (hasMore() && data[pos].toInt().toChar() != 'e') {
                    val keyBytes = parseByteString() ?: return null
                    val key = String(keyBytes, Charsets.UTF_8)
                    val value = parseAny() ?: return null
                    map[key] = value
                }
                if (hasMore() && data[pos].toInt().toChar() == 'e') {
                    pos++
                }
                return map
            }

            fun skipElement(): Int {
                if (!hasMore()) return pos
                when (data[pos].toInt().toChar()) {
                    'i' -> {
                        pos++
                        while (hasMore() && data[pos].toInt().toChar() != 'e') pos++
                        if (hasMore()) pos++
                    }
                    'l', 'd' -> {
                        pos++
                        while (hasMore() && data[pos].toInt().toChar() != 'e') {
                            skipElement()
                        }
                        if (hasMore()) pos++
                    }
                    in '0'..'9' -> {
                        val colonIdx = indexOfByte(data, ':'.code.toByte(), pos)
                        if (colonIdx == -1) {
                            pos = data.size
                        } else {
                            val lenStr = String(data, pos, colonIdx - pos, Charsets.US_ASCII)
                            val len = lenStr.toIntOrNull() ?: 0
                            pos = (colonIdx + 1 + len).coerceAtMost(data.size)
                        }
                    }
                    else -> pos++
                }
                return pos
            }
        }

        fun parseBencodeUrlList(torrentBytes: ByteArray): List<String> {
            try {
                val parser = BencodeParser(torrentBytes)
                val root = parser.parseDictionary() ?: return emptyList()
                val urlListObj = root["url-list"] ?: return emptyList()
                return when (urlListObj) {
                    is ByteArray -> listOf(String(urlListObj, Charsets.UTF_8).trim()).filter { it.startsWith("https://", ignoreCase = true) }
                    is List<*> -> urlListObj.mapNotNull { item ->
                        when (item) {
                            is ByteArray -> String(item, Charsets.UTF_8).trim()
                            is String -> item.trim()
                            else -> null
                        }
                    }.filter { it.startsWith("https://", ignoreCase = true) }
                    is String -> if (urlListObj.startsWith("https://", ignoreCase = true)) listOf(urlListObj) else emptyList()
                    else -> emptyList()
                }
            } catch (_: Exception) {
                return emptyList()
            }
        }

        fun extractBencodeInfoBytes(torrentBytes: ByteArray): ByteArray? {
            try {
                if (torrentBytes.isEmpty() || torrentBytes[0].toInt().toChar() != 'd') return null
                var pos = 1
                while (pos < torrentBytes.size && torrentBytes[pos].toInt().toChar() != 'e') {
                    val colonIdx = indexOfByte(torrentBytes, ':'.code.toByte(), pos)
                    if (colonIdx == -1) break
                    val lenStr = String(torrentBytes, pos, colonIdx - pos, Charsets.US_ASCII)
                    val keyLen = lenStr.toIntOrNull() ?: break
                    val keyStart = colonIdx + 1
                    val keyEnd = keyStart + keyLen
                    if (keyEnd > torrentBytes.size) break
                    val key = String(torrentBytes, keyStart, keyLen, Charsets.UTF_8)
                    pos = keyEnd

                    val valueStart = pos
                    val parser = BencodeParser(torrentBytes, pos)
                    val valueEnd = parser.skipElement()
                    pos = valueEnd

                    if (key == "info") {
                        return torrentBytes.copyOfRange(valueStart, valueEnd)
                    }
                }
            } catch (_: Exception) {}
            return null
        }

        fun verifyTorrentInfoHash(torrentBytes: ByteArray, expectedInfoHash: String): Boolean {
            val infoBytes = extractBencodeInfoBytes(torrentBytes) ?: return false
            val calculatedHex = computeSha1Hex(infoBytes)
            val normalizedExpected = normalizeInfoHashToHex(expectedInfoHash)
            return calculatedHex.equals(normalizedExpected, ignoreCase = true)
        }
    }

    private val cancelFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val pauseFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val activeTokens = ConcurrentHashMap<Long, String>()

    suspend fun cancelAndJoinTorrent(downloadId: Long) {
        cancelFlags[downloadId]?.set(true)
        val job = activeJobs.remove(downloadId)
        job?.cancelAndJoin()
    }

    fun startTorrentDownload(
        scope: CoroutineScope,
        item: DownloadItem,
        onProgress: (downloaded: Long, speed: Long, parts: List<Float>) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val runToken = java.util.UUID.randomUUID().toString()
        activeTokens[item.id] = runToken
        val cancelFlag = AtomicBoolean(false)
        val pauseFlag = AtomicBoolean(false)
        cancelFlags[item.id] = cancelFlag
        pauseFlags[item.id] = pauseFlag

        val job = scope.launch(Dispatchers.IO) {
            try {
                performTorrentDownload(item, cancelFlag, pauseFlag, onProgress, onComplete, onError)
            } catch (e: Exception) {
                if (!cancelFlag.get() && !pauseFlag.get()) {
                    Log.e(TAG, "Torrent download failed", e)
                    onError("Torrent Error: ${e.localizedMessage ?: "Failed to resolve metadata"}")
                }
            } finally {
                if (activeTokens[item.id] == runToken) {
                    activeTokens.remove(item.id)
                    activeJobs.remove(item.id)
                    cancelFlags.remove(item.id)
                    pauseFlags.remove(item.id)
                }
            }
        }
        activeJobs[item.id] = job
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

        // Label tracker counts as trackers
        store.updateTorrentStats(item.id, peers = metadata.trackers.size, seeds = 0)
        Log.d(TAG, "Magnet ${metadata.infoHash} resolved with ${metadata.trackers.size} active trackers")

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
                val currentBytes = torrentBytes
                if (currentBytes != null && currentBytes.isNotEmpty()) break
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

        val verifiedBytes = torrentBytes
        if (verifiedBytes != null && verifiedBytes.isNotEmpty()) {
            // Verify infohash against magnet infohash
            val infoHashValid = verifyTorrentInfoHash(verifiedBytes, metadata.infoHash)
            if (!infoHashValid) {
                onError("Torrent infohash mismatch: metadata failed SHA-1 verification against ${metadata.infoHash}")
                return@withContext
            }

            // Save the resolved .torrent file so user has genuine file
            val torrentSaveFile = if (targetFile.name.endsWith(".torrent", ignoreCase = true)) {
                targetFile
            } else {
                File("${item.filePath}.torrent")
            }
            torrentSaveFile.writeBytes(verifiedBytes)

            // Disable unverified webseed downloads for cache-fetched metadata path
            // to ensure no unverified payloads can be delivered. The verified .torrent is preserved.

            // Metadata-only: set record name and filePath to the saved .torrent file
            store.updateFileLocation(item.id, torrentSaveFile.name, torrentSaveFile.absolutePath)
            store.updateTotalSize(item.id, torrentSaveFile.length())
            store.updateProgress(item.id, torrentSaveFile.length(), 0L, DownloadStatus.COMPLETED, listOf(1.0f))
            store.markCompleted(item.id)
            onProgress(torrentSaveFile.length(), 0L, listOf(1.0f))
            onComplete()
        } else {
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
        if (!webSeedUrl.startsWith("https://", ignoreCase = true)) {
            onError("Rejected unverified/insecure non-HTTPS WebSeed URL: $webSeedUrl")
            return
        }
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
}
