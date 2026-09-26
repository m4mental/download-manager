package com.example.speeddown.data.db

import com.example.speeddown.data.DownloadItem
import com.example.speeddown.data.DownloadStatus
import kotlinx.serialization.Serializable

/**
 * SQLite Database Entity representing a persisted download task.
 * Maps to the SQLite 'downloads' table with atomic column indexing.
 */
@Serializable
data class DownloadEntity(
    val id: Long,
    val url: String,
    val fileName: String,
    val filePath: String,
    val totalSize: Long = -1L,
    val downloadedSize: Long = 0L,
    val status: String = DownloadStatus.QUEUED.name,
    val threads: Int = 4,
    val speed: Long = 0L,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val partProgressCsv: String = "",
    val category: String = "Files",
    val isStreamable: Boolean = false,
    val isTorrent: Boolean = false,
    val isHls: Boolean = false,
    val torrentPeers: Int = 0,
    val torrentSeeds: Int = 0,
    val originalUrl: String? = null
) {
    fun toDownloadItem(): DownloadItem {
        val parts = if (partProgressCsv.isBlank()) emptyList()
        else partProgressCsv.split(',').mapNotNull { it.toFloatOrNull() }

        val downloadStatus = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED)

        return DownloadItem(
            id = id,
            url = url,
            fileName = fileName,
            filePath = filePath,
            totalSize = totalSize,
            downloadedSize = downloadedSize,
            status = downloadStatus,
            threads = threads,
            speed = speed,
            errorMessage = errorMessage,
            createdAt = createdAt,
            completedAt = completedAt,
            partProgress = parts,
            category = category,
            isStreamable = isStreamable,
            isTorrent = isTorrent,
            isHls = isHls,
            torrentPeers = torrentPeers,
            torrentSeeds = torrentSeeds,
            originalUrl = originalUrl
        )
    }

    companion object {
        fun fromDownloadItem(item: DownloadItem): DownloadEntity {
            return DownloadEntity(
                id = item.id,
                url = item.url,
                fileName = item.fileName,
                filePath = item.filePath,
                totalSize = item.totalSize,
                downloadedSize = item.downloadedSize,
                status = item.status.name,
                threads = item.threads,
                speed = item.speed,
                errorMessage = item.errorMessage,
                createdAt = item.createdAt,
                completedAt = item.completedAt,
                partProgressCsv = item.partProgress.joinToString(","),
                category = item.category,
                isStreamable = item.isStreamable,
                isTorrent = item.isTorrent,
                isHls = item.isHls,
                torrentPeers = item.torrentPeers,
                torrentSeeds = item.torrentSeeds,
                originalUrl = item.originalUrl
            )
        }
    }
}
