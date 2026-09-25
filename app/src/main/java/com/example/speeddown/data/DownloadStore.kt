package com.example.speeddown.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "downloads_store")

class DownloadStore private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: DownloadStore? = null

        fun getInstance(context: Context): DownloadStore {
            return instance ?: synchronized(this) {
                instance ?: DownloadStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val DOWNLOADS_KEY = stringPreferencesKey("downloads_list")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveMutex = Mutex()
    private val isLoaded = CompletableDeferred<Unit>()

    private val _downloadsState = MutableStateFlow<List<DownloadItem>>(emptyList())
    val allDownloads: Flow<List<DownloadItem>> = _downloadsState.asStateFlow()

    private var lastDiskSaveTime = 0L

    init {
        scope.launch {
            try {
                val prefs = context.dataStore.data.first()
                val raw = prefs[DOWNLOADS_KEY] ?: "[]"
                val list: List<DownloadItem> = json.decodeFromString(raw)
                _downloadsState.value = list
            } catch (_: Exception) {
                _downloadsState.value = emptyList()
            } finally {
                isLoaded.complete(Unit)
            }
        }
    }

    suspend fun ensureLoaded() {
        if (!isLoaded.isCompleted) {
            isLoaded.await()
        }
    }

    suspend fun getAll(): List<DownloadItem> {
        ensureLoaded()
        return _downloadsState.value
    }

    suspend fun getById(id: Long): DownloadItem? {
        ensureLoaded()
        return _downloadsState.value.firstOrNull { it.id == id }
    }

    suspend fun upsert(item: DownloadItem) {
        ensureLoaded()
        _downloadsState.update { current ->
            val list = current.toMutableList()
            val idx = list.indexOfFirst { it.id == item.id }
            if (idx >= 0) list[idx] = item else list.add(0, item)
            list
        }
        persistToDisk()
    }

    suspend fun remove(id: Long) {
        ensureLoaded()
        _downloadsState.update { current ->
            current.filterNot { it.id == id }
        }
        persistToDisk()
    }

    suspend fun clearByStatus(status: DownloadStatus) {
        ensureLoaded()
        _downloadsState.update { current ->
            current.filterNot { it.status == status }
        }
        persistToDisk()
    }

    suspend fun updateStatus(id: Long, status: DownloadStatus) {
        ensureLoaded()
        _downloadsState.update { current ->
            current.map {
                if (it.id == id) it.copy(status = status, speed = 0L) else it
            }
        }
        persistToDisk()
    }

    suspend fun updateProgress(id: Long, downloaded: Long, speed: Long, status: DownloadStatus) {
        _downloadsState.update { current ->
            current.map {
                if (it.id == id) {
                    if (it.status == DownloadStatus.PAUSED ||
                        it.status == DownloadStatus.CANCELLED ||
                        it.status == DownloadStatus.COMPLETED ||
                        it.status == DownloadStatus.FAILED
                    ) {
                        // Never allow late in-flight progress callbacks to overwrite paused or terminal status!
                        it.copy(downloadedSize = downloaded, speed = 0L)
                    } else {
                        it.copy(downloadedSize = downloaded, speed = speed, status = status)
                    }
                } else it
            }
        }

        // Throttle disk write for progress to avoid I/O bottlenecks
        val now = System.currentTimeMillis()
        if (now - lastDiskSaveTime > 2500L) {
            lastDiskSaveTime = now
            scope.launch { persistToDisk() }
        }
    }

    suspend fun updateTotalSize(id: Long, totalSize: Long) {
        ensureLoaded()
        _downloadsState.update { current ->
            current.map {
                if (it.id == id) it.copy(totalSize = totalSize) else it
            }
        }
        persistToDisk()
    }

    suspend fun updateError(id: Long, status: DownloadStatus, error: String) {
        ensureLoaded()
        _downloadsState.update { current ->
            current.map {
                if (it.id == id) it.copy(status = status, errorMessage = error, speed = 0L) else it
            }
        }
        persistToDisk()
    }

    suspend fun markCompleted(id: Long) {
        ensureLoaded()
        _downloadsState.update { current ->
            current.map {
                if (it.id == id) it.copy(
                    status = DownloadStatus.COMPLETED,
                    completedAt = System.currentTimeMillis(),
                    speed = 0L
                ) else it
            }
        }
        persistToDisk()
    }

    private suspend fun persistToDisk() {
        saveMutex.withLock {
            try {
                val list = _downloadsState.value
                val raw = json.encodeToString(list)
                context.dataStore.edit { prefs ->
                    prefs[DOWNLOADS_KEY] = raw
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
