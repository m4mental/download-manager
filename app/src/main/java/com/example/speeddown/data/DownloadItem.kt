package com.example.speeddown.data

import kotlinx.serialization.Serializable

enum class DownloadStatus {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
}

@Serializable
data class DownloadItem(
    val id: Long = System.currentTimeMillis(),
    val url: String,
    val fileName: String,
    val filePath: String,
    val totalSize: Long = -1L,
    val downloadedSize: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val threads: Int = 4,
    val speed: Long = 0L,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val partProgress: List<Float> = emptyList(),
    val category: String = "Files",
    val isStreamable: Boolean = false,
    val isTorrent: Boolean = false,
    val isHls: Boolean = false,
    val torrentPeers: Int = 0,
    val torrentSeeds: Int = 0,
    val originalUrl: String? = null
) {
    val progress: Float
        get() = if (totalSize > 0) (downloadedSize.toFloat() / totalSize.toFloat()) else 0f

    val progressPercent: Int
        get() = (progress * 100).toInt()

    val etaSeconds: Long
        get() {
            if (speed <= 0 || totalSize <= 0) return -1
            val remaining = totalSize - downloadedSize
            return remaining / speed
        }
}
