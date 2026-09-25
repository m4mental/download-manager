package com.example.speeddown

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.speeddown.data.DownloadItem
import com.example.speeddown.data.DownloadSettings
import com.example.speeddown.data.DownloadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = DownloadRepository(application)

    val downloads = repo.allDownloads.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val settings = repo.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DownloadSettings()
    )

    private val _incomingShareUrl = MutableStateFlow<String?>(null)
    val incomingShareUrl = _incomingShareUrl.asStateFlow()

    fun setIncomingShareUrl(url: String) {
        _incomingShareUrl.value = url
    }

    fun onShareUrlHandled() {
        _incomingShareUrl.value = null
    }

    fun updateSettings(newSettings: DownloadSettings) {
        viewModelScope.launch { repo.updateSettings(newSettings) }
    }

    fun addDownload(url: String, fileName: String, threads: Int) {
        viewModelScope.launch { repo.addDownload(url = url, fileName = fileName, threads = threads) }
    }

    fun addBatchDownloads(urls: List<String>, threads: Int) {
        viewModelScope.launch { repo.addBatchDownloads(urls, threads) }
    }

    suspend fun calculateChecksums(filePath: String): Pair<String, String> {
        return repo.calculateChecksums(filePath)
    }

    fun pause(item: DownloadItem) {
        viewModelScope.launch { repo.pauseDownload(item) }
    }

    fun resume(item: DownloadItem) {
        viewModelScope.launch { repo.resumeDownload(item) }
    }

    /** Fixed: passes full item so QUEUED items can be cancelled directly */
    fun cancel(item: DownloadItem) {
        viewModelScope.launch { repo.cancelDownload(item) }
    }

    fun open(item: DownloadItem) = repo.openFile(item)

    fun isNothingPlayerInstalled(): Boolean = repo.isNothingPlayerInstalled()

    fun openInNothingPlayer(item: DownloadItem): Boolean = repo.openInNothingPlayer(item)

    fun launchNothingPlayer(): Boolean = repo.launchNothingPlayerApp()

    fun delete(item: DownloadItem, deleteFile: Boolean = true) {
        viewModelScope.launch { repo.deleteDownload(item, deleteFile) }
    }

    fun clearCompleted() {
        viewModelScope.launch { repo.clearCompleted() }
    }
}
