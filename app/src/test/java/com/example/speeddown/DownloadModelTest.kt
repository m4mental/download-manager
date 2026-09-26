package com.example.speeddown

import com.example.speeddown.data.DownloadItem
import com.example.speeddown.data.DownloadStatus
import com.example.speeddown.engine.AdBlockEngine
import com.example.speeddown.engine.TorrentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
