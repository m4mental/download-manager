package com.example.speeddown.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebResponse

class GeckoTabSession(
    private val context: Context,
    val isIncognito: Boolean,
    var isDesktopMode: Boolean,
    private val onTitleChanged: (String) -> Unit,
    private val onUrlChanged: (String) -> Unit,
    private val onProgressChanged: (Int) -> Unit,
    private val onCanGoBackChanged: (Boolean) -> Unit,
    private val onCanGoForwardChanged: (Boolean) -> Unit,
    private val onNewTabRequested: (String) -> Unit,
    private val onMediaSniffed: (String) -> Unit,
    private val onDownloadRequested: (url: String, fileName: String, threads: Int) -> Unit,
    private val onAdBlocked: () -> Unit = {}
) {
    val session: GeckoSession
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(isIncognito)
            .userAgentMode(if (isDesktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .build()

        session = GeckoSession(settings)

        // Native Popup & Trap Interceptor Delegate
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onPopupPrompt(
                s: GeckoSession,
                prompt: GeckoSession.PromptDelegate.PopupPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val targetUri = prompt.targetUri ?: ""
                android.util.Log.d("UBLOCK_STATUS", "onPopupPrompt intercepted: targetUri='$targetUri'")

                // Block rogue popups, ad popunders, and blank popup traps
                if (targetUri.isBlank() || targetUri == "about:blank" ||
                    AdBlockEngine.isAd(targetUri) || AdBlockEngine.isRogueRedirect(targetUri)
                ) {
                    AdBlockEngine.recordBlock()
                    mainHandler.post { onAdBlocked() }
                    return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
                }

                // If downloadable file opened in popup, trigger download
                if (isDownloadableExtension(targetUri)) {
                    val guessed = targetUri.substringAfterLast("/").substringBefore("?").substringBefore("#")
                    onDownloadRequested(targetUri, guessed.ifBlank { "download_${System.currentTimeMillis()}" }, 16)
                    return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
                }

                // For genuine user link opened as popup, open cleanly in a new tab
                onNewTabRequested(targetUri)
                return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
            }

            override fun onAlertPrompt(
                s: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // Auto-dismiss scam alert boxes & fake virus warnings (e.g. "Your device has 13 viruses!")
                return GeckoResult.fromValue(prompt.dismiss())
            }

            override fun onBeforeUnloadPrompt(
                s: GeckoSession,
                prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // Auto-allow page departure to defuse hostage dialogs
                return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                s: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                url?.let { onUrlChanged(it) }
            }

            override fun onCanGoBack(s: GeckoSession, canGoBack: Boolean) {
                onCanGoBackChanged(canGoBack)
            }

            override fun onCanGoForward(s: GeckoSession, canGoForward: Boolean) {
                onCanGoForwardChanged(canGoForward)
            }

            override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (uri.isBlank() || uri == "about:blank") {
                    return null
                }

                // Block rogue popups, ad popunders, and app-store hijacking intents
                if (AdBlockEngine.isAd(uri) || AdBlockEngine.isRogueRedirect(uri)) {
                    android.util.Log.d("UBLOCK_STATUS", "Blocked rogue new session popup: $uri")
                    AdBlockEngine.recordBlock()
                    mainHandler.post { onAdBlocked() }
                    return null
                }

                // Check if target is a downloadable file
                if (isDownloadableExtension(uri)) {
                    val guessed = uri.substringAfterLast("/").substringBefore("?").substringBefore("#")
                    onDownloadRequested(uri, guessed.ifBlank { "download_${System.currentTimeMillis()}" }, 16)
                    return null
                }

                // Legitimate user link opened in new window/tab
                onNewTabRequested(uri)
                return null
            }

            override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                val targetUrl = request.uri

                // Auto-sniff streaming media URLs
                if (isMediaUrl(targetUrl)) {
                    onMediaSniffed(targetUrl)
                }

                // Check if target is a downloadable file
                if (isDownloadableExtension(targetUrl)) {
                    val guessed = targetUrl.substringAfterLast("/").substringBefore("?").substringBefore("#")
                    onDownloadRequested(targetUrl, guessed.ifBlank { "download_${System.currentTimeMillis()}" }, 16)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                // Block rogue ad URLs, trackers, and fake link redirects
                if (AdBlockEngine.isAd(targetUrl) || AdBlockEngine.isRogueRedirect(targetUrl)) {
                    android.util.Log.d("UBLOCK_STATUS", "Blocked fake link / rogue redirect: $targetUrl")
                    AdBlockEngine.recordBlock()
                    mainHandler.post { onAdBlocked() }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                // If link requests a new window (target="_blank"), route it to our new tab system
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    onNewTabRequested(targetUrl)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                // Normal page navigations and redirects flow naturally to reach genuine download links!
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(s: GeckoSession, progress: Int) {
                onProgressChanged(progress)
            }

            override fun onPageStart(s: GeckoSession, url: String) {
                onUrlChanged(url)
                onProgressChanged(10)
            }

            override fun onPageStop(s: GeckoSession, success: Boolean) {
                onProgressChanged(100)
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) {
                title?.let { onTitleChanged(it) }
            }

            override fun onExternalResponse(s: GeckoSession, response: WebResponse) {
                val downloadUrl = response.uri
                val disposition = response.headers["content-disposition"] ?: ""
                val guessed = if (disposition.contains("filename=")) {
                    disposition.substringAfter("filename=").trim('"', ' ', ';')
                } else {
                    downloadUrl.substringAfterLast("/").substringBefore("?").substringBefore("#")
                }
                val fileName = if (guessed.isNotBlank()) guessed else "download_${System.currentTimeMillis()}"
                onDownloadRequested(downloadUrl, fileName, 16)
            }
        }

        // Open session with the shared GeckoRuntime
        val runtime = GeckoEngine.getOrCreateRuntime(context)
        session.open(runtime)
    }

    fun loadUri(uri: String) {
        session.loadUri(uri)
    }

    fun goBack() {
        session.goBack()
    }

    fun goForward() {
        session.goForward()
    }

    fun reload() {
        session.reload()
    }

    fun close() {
        session.close()
    }

    fun setDesktop(desktop: Boolean) {
        isDesktopMode = desktop
        session.settings.userAgentMode = if (desktop) {
            GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
        session.reload()
    }

    private fun isMediaUrl(url: String): Boolean {
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        return clean.endsWith(".m3u8") || clean.endsWith(".mpd") ||
                clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm") ||
                clean.endsWith(".mp3") || clean.endsWith(".m4a") || clean.endsWith(".aac") ||
                clean.endsWith(".flac") || url.contains(".m3u8") || url.contains("mime=video")
    }

    private fun isDownloadableExtension(url: String): Boolean {
        if (url.startsWith("magnet:?xt=", ignoreCase = true)) return true
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        return clean.endsWith(".zip") || clean.endsWith(".rar") || clean.endsWith(".7z") ||
                clean.endsWith(".tar") || clean.endsWith(".gz") || clean.endsWith(".bz2") || clean.endsWith(".xz") ||
                clean.endsWith(".apk") || clean.endsWith(".xapk") || clean.endsWith(".iso") || clean.endsWith(".torrent") ||
                clean.endsWith(".exe") || clean.endsWith(".msi") || clean.endsWith(".deb") || clean.endsWith(".rpm") ||
                clean.endsWith(".pdf") || clean.endsWith(".epub") || clean.endsWith(".bin") ||
                clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm") ||
                clean.endsWith(".avi") || clean.endsWith(".mov") || clean.endsWith(".m4v") ||
                clean.endsWith(".flv") || clean.endsWith(".3gp") || clean.endsWith(".wmv") ||
                clean.endsWith(".mp3") || clean.endsWith(".m4a") || clean.endsWith(".aac") ||
                clean.endsWith(".flac") || clean.endsWith(".wav") || clean.endsWith(".ogg")
    }
}
