package com.example.speeddown.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.speeddown.data.DownloadItem
import com.example.speeddown.data.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-performance SQLite database helper for SpeedDown.
 * Replaces full DataStore JSON blob serialization with atomic, indexed
 * database transactions, enabling instant search, filtering, and lag-free UI updates.
 */
class DownloadDatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "speeddown_downloads.db"
        const val DATABASE_VERSION = 1
        const val TABLE_DOWNLOADS = "downloads"

        const val COL_ID = "id"
        const val COL_URL = "url"
        const val COL_FILE_NAME = "fileName"
        const val COL_FILE_PATH = "filePath"
        const val COL_TOTAL_SIZE = "totalSize"
        const val COL_DOWNLOADED_SIZE = "downloadedSize"
        const val COL_STATUS = "status"
        const val COL_THREADS = "threads"
        const val COL_SPEED = "speed"
        const val COL_ERROR_MESSAGE = "errorMessage"
        const val COL_CREATED_AT = "createdAt"
        const val COL_COMPLETED_AT = "completedAt"
        const val COL_PART_PROGRESS = "partProgress"
        const val COL_CATEGORY = "category"
        const val COL_IS_STREAMABLE = "isStreamable"
        const val COL_IS_TORRENT = "isTorrent"
        const val COL_IS_HLS = "isHls"
        const val COL_TORRENT_PEERS = "torrentPeers"
        const val COL_TORRENT_SEEDS = "torrentSeeds"
        const val COL_ORIGINAL_URL = "originalUrl"

        @Volatile
        private var instance: DownloadDatabaseHelper? = null

        fun getInstance(context: Context): DownloadDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: DownloadDatabaseHelper(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createSql = """
            CREATE TABLE IF NOT EXISTS $TABLE_DOWNLOADS (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_URL TEXT NOT NULL,
                $COL_FILE_NAME TEXT NOT NULL,
                $COL_FILE_PATH TEXT NOT NULL,
                $COL_TOTAL_SIZE INTEGER NOT NULL DEFAULT -1,
                $COL_DOWNLOADED_SIZE INTEGER NOT NULL DEFAULT 0,
                $COL_STATUS TEXT NOT NULL DEFAULT 'QUEUED',
                $COL_THREADS INTEGER NOT NULL DEFAULT 4,
                $COL_SPEED INTEGER NOT NULL DEFAULT 0,
                $COL_ERROR_MESSAGE TEXT,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_COMPLETED_AT INTEGER,
                $COL_PART_PROGRESS TEXT,
                $COL_CATEGORY TEXT NOT NULL DEFAULT 'Files',
                $COL_IS_STREAMABLE INTEGER NOT NULL DEFAULT 0,
                $COL_IS_TORRENT INTEGER NOT NULL DEFAULT 0,
                $COL_IS_HLS INTEGER NOT NULL DEFAULT 0,
                $COL_TORRENT_PEERS INTEGER NOT NULL DEFAULT 0,
                $COL_TORRENT_SEEDS INTEGER NOT NULL DEFAULT 0,
                $COL_ORIGINAL_URL TEXT
            );
        """.trimIndent()
        db.execSQL(createSql)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_downloads_status ON $TABLE_DOWNLOADS($COL_STATUS);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_downloads_created ON $TABLE_DOWNLOADS($COL_CREATED_AT DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_downloads_category ON $TABLE_DOWNLOADS($COL_CATEGORY);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema migrations
    }

    suspend fun getAllDownloads(): List<DownloadItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<DownloadItem>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DOWNLOADS,
            null,
            null,
            null,
            null,
            null,
            "$COL_CREATED_AT DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToDownloadItem(it))
            }
        }
        list
    }

    suspend fun getDownloadById(id: Long): DownloadItem? = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DOWNLOADS,
            null,
            "$COL_ID = ?",
            arrayOf(id.toString()),
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) cursorToDownloadItem(it) else null
        }
    }

    suspend fun upsertDownload(item: DownloadItem) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = downloadItemToContentValues(item)
        db.insertWithOnConflict(TABLE_DOWNLOADS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun updateProgress(
        id: Long,
        downloaded: Long,
        speed: Long,
        status: DownloadStatus,
        partProgress: List<Float>
    ) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_DOWNLOADED_SIZE, downloaded)
            put(COL_SPEED, speed)
            put(COL_STATUS, status.name)
            if (partProgress.isNotEmpty()) {
                put(COL_PART_PROGRESS, partProgress.joinToString(","))
            }
        }
        db.update(
            TABLE_DOWNLOADS,
            values,
            "$COL_ID = ? AND $COL_STATUS NOT IN ('PAUSED', 'CANCELLED', 'COMPLETED', 'FAILED')",
            arrayOf(id.toString())
        )
    }

    suspend fun updateStatus(id: Long, status: DownloadStatus) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_STATUS, status.name)
            put(COL_SPEED, 0L)
        }
        db.update(TABLE_DOWNLOADS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    suspend fun updateTotalSize(id: Long, totalSize: Long) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TOTAL_SIZE, totalSize)
        }
        db.update(TABLE_DOWNLOADS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    suspend fun updateError(id: Long, status: DownloadStatus, error: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_STATUS, status.name)
            put(COL_ERROR_MESSAGE, error)
            put(COL_SPEED, 0L)
        }
        db.update(TABLE_DOWNLOADS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    suspend fun markCompleted(id: Long) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_STATUS, DownloadStatus.COMPLETED.name)
            put(COL_COMPLETED_AT, System.currentTimeMillis())
            put(COL_SPEED, 0L)
        }
        db.update(TABLE_DOWNLOADS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    suspend fun updateUrl(id: Long, newUrl: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val item = getDownloadById(id) ?: return@withContext
        val values = ContentValues().apply {
            put(COL_ORIGINAL_URL, item.originalUrl ?: item.url)
            put(COL_URL, newUrl)
            putNull(COL_ERROR_MESSAGE)
        }
        db.update(TABLE_DOWNLOADS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    suspend fun updateTorrentStats(id: Long, peers: Int, seeds: Int) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TORRENT_PEERS, peers)
            put(COL_TORRENT_SEEDS, seeds)
        }
        db.update(TABLE_DOWNLOADS, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    suspend fun deleteDownload(id: Long) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete(TABLE_DOWNLOADS, "$COL_ID = ?", arrayOf(id.toString()))
    }

    suspend fun clearByStatus(status: DownloadStatus) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.delete(TABLE_DOWNLOADS, "$COL_STATUS = ?", arrayOf(status.name))
    }

    private fun cursorToDownloadItem(c: Cursor): DownloadItem {
        val rawParts = c.getString(c.getColumnIndexOrThrow(COL_PART_PROGRESS)) ?: ""
        val parts = if (rawParts.isBlank()) emptyList()
        else rawParts.split(',').mapNotNull { it.toFloatOrNull() }

        val statusStr = c.getString(c.getColumnIndexOrThrow(COL_STATUS))
        val status = runCatching { DownloadStatus.valueOf(statusStr) }.getOrDefault(DownloadStatus.QUEUED)

        return DownloadItem(
            id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
            url = c.getString(c.getColumnIndexOrThrow(COL_URL)),
            fileName = c.getString(c.getColumnIndexOrThrow(COL_FILE_NAME)),
            filePath = c.getString(c.getColumnIndexOrThrow(COL_FILE_PATH)),
            totalSize = c.getLong(c.getColumnIndexOrThrow(COL_TOTAL_SIZE)),
            downloadedSize = c.getLong(c.getColumnIndexOrThrow(COL_DOWNLOADED_SIZE)),
            status = status,
            threads = c.getInt(c.getColumnIndexOrThrow(COL_THREADS)),
            speed = c.getLong(c.getColumnIndexOrThrow(COL_SPEED)),
            errorMessage = c.getString(c.getColumnIndexOrThrow(COL_ERROR_MESSAGE)),
            createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT)),
            completedAt = if (c.isNull(c.getColumnIndexOrThrow(COL_COMPLETED_AT))) null
                         else c.getLong(c.getColumnIndexOrThrow(COL_COMPLETED_AT)),
            partProgress = parts,
            category = c.getString(c.getColumnIndexOrThrow(COL_CATEGORY)) ?: "Files",
            isStreamable = c.getInt(c.getColumnIndexOrThrow(COL_IS_STREAMABLE)) == 1,
            isTorrent = c.getInt(c.getColumnIndexOrThrow(COL_IS_TORRENT)) == 1,
            isHls = c.getInt(c.getColumnIndexOrThrow(COL_IS_HLS)) == 1,
            torrentPeers = c.getInt(c.getColumnIndexOrThrow(COL_TORRENT_PEERS)),
            torrentSeeds = c.getInt(c.getColumnIndexOrThrow(COL_TORRENT_SEEDS)),
            originalUrl = c.getString(c.getColumnIndexOrThrow(COL_ORIGINAL_URL))
        )
    }

    private fun downloadItemToContentValues(item: DownloadItem): ContentValues {
        return ContentValues().apply {
            put(COL_ID, item.id)
            put(COL_URL, item.url)
            put(COL_FILE_NAME, item.fileName)
            put(COL_FILE_PATH, item.filePath)
            put(COL_TOTAL_SIZE, item.totalSize)
            put(COL_DOWNLOADED_SIZE, item.downloadedSize)
            put(COL_STATUS, item.status.name)
            put(COL_THREADS, item.threads)
            put(COL_SPEED, item.speed)
            put(COL_ERROR_MESSAGE, item.errorMessage)
            put(COL_CREATED_AT, item.createdAt)
            put(COL_COMPLETED_AT, item.completedAt)
            put(COL_PART_PROGRESS, item.partProgress.joinToString(","))
            put(COL_CATEGORY, item.category)
            put(COL_IS_STREAMABLE, if (item.isStreamable) 1 else 0)
            put(COL_IS_TORRENT, if (item.isTorrent) 1 else 0)
            put(COL_IS_HLS, if (item.isHls) 1 else 0)
            put(COL_TORRENT_PEERS, item.torrentPeers)
            put(COL_TORRENT_SEEDS, item.torrentSeeds)
            put(COL_ORIGINAL_URL, item.originalUrl)
        }
    }
}
