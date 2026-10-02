package com.example.speeddown

import com.example.speeddown.data.DownloadItem
import com.example.speeddown.data.DownloadStatus
import com.example.speeddown.engine.AdBlockEngine
import com.example.speeddown.engine.HlsDownloader
import com.example.speeddown.engine.LocalStreamServer
import com.example.speeddown.engine.MediaMuxerEngine
import com.example.speeddown.engine.MultiThreadDownloader
import com.example.speeddown.engine.TorrentEngine
import com.example.speeddown.extractor.YouTubeExtractorEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadModelTest {

    @Test
    fun testDownloadItem_progressAndEtaCalculation() {
        val item = DownloadItem(
            id = 1L,
            url = "https://example.com/test.zip",
            fileName = "test.zip",
            filePath = "/storage/emulated/0/Download/test.zip",
            totalSize = 100_000_000L,
            downloadedSize = 50_000_000L,
            speed = 10_000_000L, // 10 MB/s
            status = DownloadStatus.DOWNLOADING
        )

        assertEquals(0.5f, item.progress, 0.001f)
        assertEquals(50, item.progressPercent)
        assertEquals(5L, item.etaSeconds) // 50MB remaining / 10MB/s = 5 seconds
    }

    @Test
    fun testDownloadItem_zeroSpeedOrTotalSize() {
        val zeroSpeedItem = DownloadItem(
            id = 2L,
            url = "https://example.com/file.mp4",
            fileName = "file.mp4",
            filePath = "/test/file.mp4",
            totalSize = 500L,
            downloadedSize = 100L,
            speed = 0L
        )
        assertEquals(-1L, zeroSpeedItem.etaSeconds)

        val unknownSizeItem = DownloadItem(
            id = 3L,
            url = "https://example.com/stream",
            fileName = "stream",
            filePath = "/test/stream",
            totalSize = -1L,
            downloadedSize = 100L,
            speed = 500L
        )
        assertEquals(0f, unknownSizeItem.progress, 0.001f)
        assertEquals(0, unknownSizeItem.progressPercent)
        assertEquals(-1L, unknownSizeItem.etaSeconds)

        // Fix to explicitly test total size 0
        val zeroTotalSizeItem = DownloadItem(
            id = 4L,
            url = "https://example.com/empty.bin",
            fileName = "empty.bin",
            filePath = "/test/empty.bin",
            totalSize = 0L,
            downloadedSize = 0L,
            speed = 1000L
        )
        assertEquals(0f, zeroTotalSizeItem.progress, 0.001f)
        assertEquals(0, zeroTotalSizeItem.progressPercent)
        assertEquals(-1L, zeroTotalSizeItem.etaSeconds)
    }

    @Test
    fun testContentRange_validationAnd200RestartDecisions() {
        // Parse Content-Range header
        val validRange = MultiThreadDownloader.parseContentRange("bytes 0-499/1000")
        assertNotNull(validRange)
        assertEquals(0L, validRange!!.start)
        assertEquals(499L, validRange.end)
        assertEquals(1000L, validRange.total)

        val wildcardTotal = MultiThreadDownloader.parseContentRange("bytes 100-200/*")
        assertNotNull(wildcardTotal)
        assertEquals(100L, wildcardTotal!!.start)
        assertEquals(200L, wildcardTotal.end)
        assertNull(wildcardTotal.total)

        assertNull(MultiThreadDownloader.parseContentRange("invalid-header"))
        assertNull(MultiThreadDownloader.parseContentRange(null))

        // Validate Content-Range against expected offsets
        assertTrue(MultiThreadDownloader.isValidContentRange("bytes 0-499/1000", expectedStart = 0L, expectedEnd = 499L))
        assertFalse(MultiThreadDownloader.isValidContentRange("bytes 0-499/1000", expectedStart = 100L, expectedEnd = 499L))
        assertFalse(MultiThreadDownloader.isValidContentRange("bytes 0-499/1000", expectedStart = 0L, expectedEnd = 500L))

        // 200 restart decisions
        assertTrue(MultiThreadDownloader.shouldRestartFromZeroOn200(useRange = true, statusCode = 200))
        assertFalse(MultiThreadDownloader.shouldRestartFromZeroOn200(useRange = false, statusCode = 200))
        assertFalse(MultiThreadDownloader.shouldRestartFromZeroOn200(useRange = true, statusCode = 206))

        // Part discard and restart decisions on validator/layout changes
        assertTrue(MultiThreadDownloader.shouldDiscardPartsAndRestart(
            savedValidator = "\"etag1\"", newValidator = "\"etag2\"",
            savedLength = 1000L, newLength = 1000L,
            savedThreads = 4, newThreads = 4
        ))
        assertTrue(MultiThreadDownloader.shouldDiscardPartsAndRestart(
            savedValidator = "\"etag1\"", newValidator = "\"etag1\"",
            savedLength = 1000L, newLength = 2000L,
            savedThreads = 4, newThreads = 4
        ))
        assertTrue(MultiThreadDownloader.shouldDiscardPartsAndRestart(
            savedValidator = "\"etag1\"", newValidator = "\"etag1\"",
            savedLength = 1000L, newLength = 1000L,
            savedThreads = 4, newThreads = 8
        ))
        assertFalse(MultiThreadDownloader.shouldDiscardPartsAndRestart(
            savedValidator = "\"etag1\"", newValidator = "\"etag1\"",
            savedLength = 1000L, newLength = 1000L,
            savedThreads = 4, newThreads = 4
        ))
    }

    @Test
    fun testHlsIv_paddingAndMediaSequenceDerivation() {
        // IV left-padding to 16 bytes
        val shortIv = byteArrayOf(0x01, 0x02, 0x03)
        val paddedIv = HlsDownloader.padIvTo16Bytes(shortIv)
        assertEquals(16, paddedIv.size)
        assertEquals(0.toByte(), paddedIv[0])
        assertEquals(0.toByte(), paddedIv[12])
        assertEquals(0x01.toByte(), paddedIv[13])
        assertEquals(0x02.toByte(), paddedIv[14])
        assertEquals(0x03.toByte(), paddedIv[15])

        val fullIv = ByteArray(16) { it.toByte() }
        assertArrayEquals(fullIv, HlsDownloader.padIvTo16Bytes(fullIv))

        // Parse hex IV with 0x prefix
        val hexParsed = HlsDownloader.parseHexIv("0x1A2B")
        assertEquals(16, hexParsed.size)
        assertEquals(0x1A.toByte(), hexParsed[14])
        assertEquals(0x2B.toByte(), hexParsed[15])

        // Media sequence IV derivation (big-endian 16 bytes with sequence number)
        val seqIv = HlsDownloader.createMediaSequenceIv(1L)
        assertEquals(16, seqIv.size)
        assertEquals(0.toByte(), seqIv[0])
        assertEquals(1.toByte(), seqIv[15])

        val seqIvLarge = HlsDownloader.createMediaSequenceIv(256L)
        assertEquals(1.toByte(), seqIvLarge[14])
        assertEquals(0.toByte(), seqIvLarge[15])

        // Unsupported playlist detection
        val unsupportedPlaylist = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\"\n#EXT-X-STREAM-INF\nvideo.m3u8"
        assertNotNull(HlsDownloader.checkUnsupportedPlaylistFeatures(unsupportedPlaylist))

        val standardPlaylist = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nseg1.ts"
        assertNull(HlsDownloader.checkUnsupportedPlaylistFeatures(standardPlaylist))
    }

    @Test
    fun testLocalServer_rangeParsingSuffixRangesAnd416() {
        val totalSize = 1000L

        // Standard ranges
        val standardRange = LocalStreamServer.parseHttpRange("bytes=0-499", totalSize).getOrThrow()!!
        assertEquals(0L, standardRange.start)
        assertEquals(499L, standardRange.end)

        val openEndedRange = LocalStreamServer.parseHttpRange("bytes=500-", totalSize).getOrThrow()!!
        assertEquals(500L, openEndedRange.start)
        assertEquals(999L, openEndedRange.end)

        // Suffix range: bytes=-N (final N bytes)
        val suffixRange = LocalStreamServer.parseHttpRange("bytes=-200", totalSize).getOrThrow()!!
        assertEquals(800L, suffixRange.start)
        assertEquals(999L, suffixRange.end)

        // Suffix range larger than file clamps start to 0
        val oversizedSuffix = LocalStreamServer.parseHttpRange("bytes=-1500", totalSize).getOrThrow()!!
        assertEquals(0L, oversizedSuffix.start)
        assertEquals(999L, oversizedSuffix.end)

        // 416 Unsatisfiable ranges
        assertTrue(LocalStreamServer.parseHttpRange("bytes=1500-2000", totalSize).isFailure)
        assertTrue(LocalStreamServer.parseHttpRange("bytes=600-400", totalSize).isFailure)
        assertTrue(LocalStreamServer.parseHttpRange("bytes=1000-1000", totalSize).isFailure)
        assertTrue(LocalStreamServer.parseHttpRange("bytes=-50", 0L).isFailure)
    }

    @Test
    fun testUniqueFileName_generation() {
        val dummyDir = File("non_existent_test_directory")
        val existingNames = setOf("movie.mp4", "movie (1).mp4", "archive.tar.gz")

        // Already taken names increment suffix
        val unique1 = DownloadRepository.generateUniqueFileName("movie.mp4", dummyDir, existingNames)
        assertEquals("movie (2).mp4", unique1)

        // Available name stays as-is
        val unique2 = DownloadRepository.generateUniqueFileName("song.mp3", dummyDir, existingNames)
        assertEquals("song.mp3", unique2)

        // Test with disk collision using a temp directory
        val tempDir = Files.createTempDirectory("speeddown_test").toFile()
        try {
            val f1 = File(tempDir, "document.pdf")
            f1.createNewFile()
            val uniqueDisk = DownloadRepository.generateUniqueFileName("document.pdf", tempDir, emptySet())
            assertEquals("document (1).pdf", uniqueDisk)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOrphanPart_matchingAndProtection() {
        // Numbered part filename pattern
        assertTrue(DownloadRepository.isExactNumberedPartFileName("video.mp4.part0"))
        assertTrue(DownloadRepository.isExactNumberedPartFileName("video.mp4.part12"))
        assertTrue(DownloadRepository.isExactNumberedPartFileName("archive.zip.part"))
        assertFalse(DownloadRepository.isExactNumberedPartFileName("video.part.mp4"))
        assertFalse(DownloadRepository.isExactNumberedPartFileName("video.partial"))
        assertFalse(DownloadRepository.isExactNumberedPartFileName("file.part_123"))

        // Protection check
        val base1 = File("/downloads/video.mp4").absolutePath
        val base2 = File("/downloads/audio.m4a").absolutePath
        val protectedBasePaths = setOf(base1, base2)
        assertTrue(DownloadRepository.isProtectedPartFile(File("/downloads/video.mp4.part"), protectedBasePaths))
        assertTrue(DownloadRepository.isProtectedPartFile(File("/downloads/video.mp4.part3"), protectedBasePaths))
        assertTrue(DownloadRepository.isProtectedPartFile(File("/downloads/video.mp4.video.tmp.part0"), protectedBasePaths))
        assertTrue(DownloadRepository.isProtectedPartFile(File("/downloads/video.mp4.audio.tmp.part2"), protectedBasePaths))
        assertTrue(DownloadRepository.isProtectedPartFile(File("/downloads/audio.m4a.part0"), protectedBasePaths))
        assertFalse(DownloadRepository.isProtectedPartFile(File("/downloads/orphan.mp4.part1"), protectedBasePaths))
        assertFalse(DownloadRepository.isProtectedPartFile(File("/downloads/orphan.mp4.video.tmp.part1"), protectedBasePaths))
    }

    @Test
    fun testFileProvider_pathContainment() {
        val tempRoot = Files.createTempDirectory("speeddown_root").toFile().canonicalFile
        val allowedRoots = listOf(tempRoot)

        try {
            val insideFile = File(tempRoot, "movie.mp4").canonicalFile
            assertTrue(DownloadRepository.isPathContainedInRoots(insideFile.absolutePath, allowedRoots))

            val subDirFile = File(File(tempRoot, "subdir"), "movie.mp4").canonicalFile
            assertTrue(DownloadRepository.isPathContainedInRoots(subDirFile.absolutePath, allowedRoots))

            // Path traversal attempt
            val escapedFile = File(tempRoot, "../outside_secret.txt").canonicalFile
            assertFalse(DownloadRepository.isPathContainedInRoots(escapedFile.absolutePath, allowedRoots))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testTorrentEngine_bencodeUrlListAndInfoHash() {
        // Bencode with single string url-list: d8:url-list24:https://example.com/filee
        val bencodeSingle = "d8:url-list24:https://example.com/filee".toByteArray(Charsets.US_ASCII)
        val urlsSingle = TorrentEngine.parseBencodeUrlList(bencodeSingle)
        assertEquals(listOf("https://example.com/file"), urlsSingle)

        // Bencode with list url-list: d8:url-listl25:https://example.com/seed125:https://example.com/seed2ee
        val bencodeList = "d8:url-listl25:https://example.com/seed125:https://example.com/seed2ee".toByteArray(Charsets.US_ASCII)
        val urlsList = TorrentEngine.parseBencodeUrlList(bencodeList)
        assertEquals(listOf("https://example.com/seed1", "https://example.com/seed2"), urlsList)

        // InfoHash verification: construct bencode dictionary with info dict
        // "d4:info4:spame" -> info value is "4:spam"
        val infoValue = "4:spam".toByteArray(Charsets.US_ASCII)
        val expectedSha1 = TorrentEngine.computeSha1Hex(infoValue)
        val mockTorrent = "d4:info4:spame".toByteArray(Charsets.US_ASCII)

        assertTrue(TorrentEngine.verifyTorrentInfoHash(mockTorrent, expectedSha1))
        assertFalse(TorrentEngine.verifyTorrentInfoHash(mockTorrent, "0000000000000000000000000000000000000000"))

        // InfoHash Base32 to Hex normalization
        val hex40 = "C12FE1C06BBA254A9DC9F519B335DE7ECE74FEDF"
        assertEquals(hex40, TorrentEngine.normalizeInfoHashToHex(hex40))
        assertEquals(hex40, TorrentEngine.normalizeInfoHashToHex("  ${hex40.lowercase()}  "))
    }

    @Test
    fun testYouTubeExtractorEngine_hostAndUrlValidation() {
        assertTrue(YouTubeExtractorEngine.isValidYouTubeHost("youtube.com"))
        assertTrue(YouTubeExtractorEngine.isValidYouTubeHost("www.youtube.com"))
        assertTrue(YouTubeExtractorEngine.isValidYouTubeHost("m.youtube.com"))
        assertTrue(YouTubeExtractorEngine.isValidYouTubeHost("music.youtube.com"))
        assertTrue(YouTubeExtractorEngine.isValidYouTubeHost("youtu.be"))

        // Malicious / non-YouTube hosts
        assertFalse(YouTubeExtractorEngine.isValidYouTubeHost("notyoutube.com"))
        assertFalse(YouTubeExtractorEngine.isValidYouTubeHost("youtube.com.attacker.com"))
        assertFalse(YouTubeExtractorEngine.isValidYouTubeHost("fake-youtube.com"))
        assertFalse(YouTubeExtractorEngine.isValidYouTubeHost(null))
        assertFalse(YouTubeExtractorEngine.isValidYouTubeHost(""))

        // URL check
        assertTrue(YouTubeExtractorEngine.isYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(YouTubeExtractorEngine.isYouTubeUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(YouTubeExtractorEngine.isYouTubeUrl("https://music.youtube.com/watch?v=xyz"))
        assertFalse(YouTubeExtractorEngine.isYouTubeUrl("https://example.com/watch?v=123"))
    }

    @Test
    fun testTorrentEngine_magnetAndTorrentDetection() {
        assertTrue(TorrentEngine.isMagnet("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74fedf&dn=Ubuntu"))
        assertTrue(TorrentEngine.isMagnet("  magnet:?xt=urn:btih:ABCDEF123456  "))
        assertFalse(TorrentEngine.isMagnet("https://example.com/file.zip"))

        assertTrue(TorrentEngine.isTorrentFile("https://example.com/archlinux.torrent"))
        assertTrue(TorrentEngine.isTorrentFile("https://example.com/file.torrent?token=abc#hash"))
        assertFalse(TorrentEngine.isTorrentFile("https://example.com/file.mp4"))
    }

    @Test
    fun testAdBlockEngine_heuristics() {
        assertTrue(AdBlockEngine.isAd("https://popads.net/serve.js"))
        assertTrue(AdBlockEngine.isAd("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(AdBlockEngine.isAd("https://cdn.deloplen.com/script.js"))
        assertFalse(AdBlockEngine.isAd("https://en.wikipedia.org/wiki/Kotlin"))
        assertFalse(AdBlockEngine.isAd("https://github.com/m4mental/download-manager"))

        assertTrue(AdBlockEngine.isRogueRedirect("intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end"))
        assertTrue(AdBlockEngine.isRogueRedirect("market://details?id=com.spam.app"))
        assertFalse(AdBlockEngine.isRogueRedirect("https://google.com"))
    }

    @Test
    fun testMultiThreadDownloader_selectRangeValidator() {
        // Strong ETag should be selected
        assertEquals("\"strong-etag-123\"", MultiThreadDownloader.selectRangeValidator("\"strong-etag-123\"", "Wed, 21 Oct 2015 07:28:00 GMT"))

        // Weak ETag must be rejected and fall back to Last-Modified
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", MultiThreadDownloader.selectRangeValidator("W/\"weak-etag-123\"", "Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", MultiThreadDownloader.selectRangeValidator("w/\"weak-etag-456\"", "Wed, 21 Oct 2015 07:28:00 GMT"))

        // Weak ETag with blank or null Last-Modified falls back to null
        assertNull(MultiThreadDownloader.selectRangeValidator("W/\"weak-etag-123\"", null))
        assertNull(MultiThreadDownloader.selectRangeValidator("W/\"weak-etag-123\"", "   "))

        // Null or blank ETag falls back to Last-Modified
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", MultiThreadDownloader.selectRangeValidator(null, "Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", MultiThreadDownloader.selectRangeValidator("", "Wed, 21 Oct 2015 07:28:00 GMT"))
    }

    @Test
    fun testTorrentEngine_parseBencodeUrlList_rejectsHttp() {
        // Construct bencode dict with list: 'https://secure.seed/' is 20 characters
        val bencodeList = "d8:url-listl21:http://insecure.seed/20:https://secure.seed/ee".toByteArray(Charsets.US_ASCII)
        val list = TorrentEngine.parseBencodeUrlList(bencodeList)
        assertEquals(1, list.size)
        assertEquals("https://secure.seed/", list[0])

        // Test single string url-list
        val bencodeSingle = "d8:url-list20:https://secure.seed/e".toByteArray(Charsets.US_ASCII)
        val singleList = TorrentEngine.parseBencodeUrlList(bencodeSingle)
        assertEquals(1, singleList.size)
        assertEquals("https://secure.seed/", singleList[0])

        // Single HTTP string should be rejected
        val bencodeHttpSingle = "d8:url-list21:http://insecure.seed/e".toByteArray(Charsets.US_ASCII)
        assertTrue(TorrentEngine.parseBencodeUrlList(bencodeHttpSingle).isEmpty())
    }

    @Test
    fun testMediaMuxerEngine_calculateBufferSize() {
        val defaultVideo = 2 * 1024 * 1024
        val budgetVideo = MediaMuxerEngine.MAX_VIDEO_BUFFER_BUDGET

        // Normal sample size: should be maxOf(default, sample, sample * 2) = 2 MB
        val normal = MediaMuxerEngine.calculateBufferSize(1024 * 1024, defaultVideo, budgetVideo)
        assertTrue(normal.isSuccess)
        assertEquals(2 * 1024 * 1024, normal.getOrThrow())

        // Large sample size fitting in budget: 20 MB * 2 = 40 MB coerced to 32 MB budget
        val large = MediaMuxerEngine.calculateBufferSize(20 * 1024 * 1024, defaultVideo, budgetVideo)
        assertTrue(large.isSuccess)
        assertEquals(budgetVideo, large.getOrThrow())
        assertTrue(large.getOrThrow() >= 20 * 1024 * 1024)

        // Sample size exceeding budget must fail, NOT truncate below required sample size
        val excessive = MediaMuxerEngine.calculateBufferSize(40 * 1024 * 1024, defaultVideo, budgetVideo)
        assertTrue(excessive.isFailure)
    }
}
