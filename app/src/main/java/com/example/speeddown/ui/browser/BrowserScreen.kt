package com.example.speeddown.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.speeddown.data.BrowserSettings
import com.example.speeddown.data.BrowserSettingsStore
import com.example.speeddown.data.getHomeUrl
import com.example.speeddown.data.getSearchUrl
import com.example.speeddown.engine.AdBlockEngine
import com.example.speeddown.engine.GeckoEngine
import com.example.speeddown.engine.GeckoTabSession
import com.example.speeddown.engine.HlsStreamVariant
import com.example.speeddown.engine.SecureDnsHelper
import org.mozilla.geckoview.GeckoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SniffedMedia(
    val url: String,
    val fileName: String,
    val type: String,
    val timestamp: Long = System.currentTimeMillis()
)

class BrowserTabItem(
    val id: String = UUID.randomUUID().toString(),
    initialUrl: String = "speeddown://home",
    val isIncognito: Boolean = false
) {
    var title by mutableStateOf(if (isIncognito) "Incognito Tab" else "SpeedDown Home")
    var url by mutableStateOf(initialUrl)
    var webProgress by mutableStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var isDesktopMode by mutableStateOf(false)
    var webView: WebView? = null
    var geckoTabSession: GeckoTabSession? = null
}

private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

private val Purple = Color(0xFF7C3AED)
private val Green = Color(0xFF16A34A)
private val Blue = Color(0xFF2563EB)
private val Amber = Color(0xFFF59E0B)
private val detectedUrlsCache = ConcurrentHashMap.newKeySet<String>()
private val mainHandler = Handler(Looper.getMainLooper())

object BrowserSessionManager {
    val tabs = mutableStateListOf<BrowserTabItem>()
    var activeTabId by mutableStateOf("")
    private var isInitialized = false

    private const val PREFS_NAME = "speeddown_browser_tabs_session"
    private const val KEY_TABS_JSON = "tabs_json"
    private const val KEY_ACTIVE_TAB_ID = "active_tab_id"

    fun getOrCreateTabs(
        context: Context,
        initialUrl: String = "speeddown://home",
        defaultDesktop: Boolean = false
    ): MutableList<BrowserTabItem> {
        if (!isInitialized || tabs.isEmpty()) {
            val restored = restoreSession(context, defaultDesktop)
            tabs.clear()
            if (restored.isNotEmpty()) {
                tabs.addAll(restored)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val savedActiveId = prefs.getString(KEY_ACTIVE_TAB_ID, "") ?: ""
                activeTabId = if (tabs.any { it.id == savedActiveId }) savedActiveId else tabs.first().id

                // If an explicit non-home URL was requested (e.g. from an intent) that isn't already open
                if (initialUrl != "speeddown://home" && initialUrl.isNotBlank() && tabs.none { it.url == initialUrl }) {
                    val newTab = BrowserTabItem(initialUrl = initialUrl).apply {
                        isDesktopMode = defaultDesktop
                    }
                    tabs.add(newTab)
                    activeTabId = newTab.id
                }
            } else {
                val firstTab = BrowserTabItem(initialUrl = initialUrl).apply {
                    isDesktopMode = defaultDesktop
                }
                tabs.add(firstTab)
                activeTabId = firstTab.id
            }
            isInitialized = true
        }
        return tabs
    }

    fun saveSession(context: Context) {
        try {
            val nonIncognitoTabs = tabs.filter { !it.isIncognito }
            if (nonIncognitoTabs.isEmpty()) return

            val arr = org.json.JSONArray()
            nonIncognitoTabs.forEach { tab ->
                val currentUrl = if (tab.url.isNotBlank()) tab.url else (tab.webView?.url ?: "speeddown://home")
                val obj = org.json.JSONObject().apply {
                    put("id", tab.id)
                    put("url", currentUrl)
                    put("title", tab.title)
                    put("isDesktopMode", tab.isDesktopMode)
                }
                arr.put(obj)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TABS_JSON, arr.toString())
                .putString(KEY_ACTIVE_TAB_ID, activeTabId)
                .apply()
        } catch (_: Exception) {}
    }

    private fun restoreSession(context: Context, defaultDesktop: Boolean): List<BrowserTabItem> {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_TABS_JSON, null) ?: return emptyList()
            val arr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<BrowserTabItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                val url = obj.optString("url", "speeddown://home")
                val title = obj.optString("title", "SpeedDown Home")
                val isDesktop = obj.optBoolean("isDesktopMode", defaultDesktop)

                val tab = BrowserTabItem(id = id, initialUrl = url).apply {
                    this.title = title
                    this.url = url
                    this.isDesktopMode = isDesktop
                }
                list.add(tab)
            }
            return list
        } catch (_: Exception) {
            return emptyList()
        }
    }

    fun removeTab(context: Context?, tab: BrowserTabItem) {
        val index = tabs.indexOf(tab)
        try {
            tab.geckoTabSession?.close()
        } catch (_: Exception) {}
        tab.geckoTabSession = null
        tab.webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
        }
        tab.webView = null
        tabs.remove(tab)
        if (tabs.isEmpty()) {
            val newTab = BrowserTabItem(initialUrl = "speeddown://home")
            tabs.add(newTab)
            activeTabId = newTab.id
        } else if (activeTabId == tab.id) {
            val nextIndex = (index - 1).coerceAtLeast(0)
            activeTabId = tabs[nextIndex].id
        }
        if (context != null) {
            saveSession(context)
        }
    }

    fun clearAll(context: Context?) {
        tabs.forEach { tab ->
            try {
                tab.geckoTabSession?.close()
            } catch (_: Exception) {}
            tab.geckoTabSession = null
            tab.webView?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.destroy()
            }
            tab.webView = null
        }
        tabs.clear()
        isInitialized = false
        if (context != null) {
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            } catch (_: Exception) {}
        }
    }
}

data class PendingBrowserDownload(
    val url: String,
    val initialFileName: String,
    val initialThreads: Int = 16
)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    initialUrl: String = "speeddown://home",
    onClose: () -> Unit,
    onNavigateToDownloads: () -> Unit = onClose,
    onStartDownload: (url: String, fileName: String, threads: Int) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val detectedMedia = remember { mutableStateListOf<SniffedMedia>() }

    // Initialize uBlock Engine with asset database
    LaunchedEffect(Unit) {
        AdBlockEngine.init(context)
    }

    val browserSettingsStore = remember { BrowserSettingsStore.getInstance(context) }
    val browserSettings by browserSettingsStore.settings.collectAsState(initial = BrowserSettings())
    val shortcuts by browserSettingsStore.shortcuts.collectAsState(initial = emptyList())

    // Persistent multi-tab session across screen navigation and app restarts
    val tabs = BrowserSessionManager.getOrCreateTabs(context, initialUrl, browserSettings.defaultDesktopMode)
    var activeTabId by remember { mutableStateOf(BrowserSessionManager.activeTabId.ifBlank { tabs.first().id }) }
    LaunchedEffect(activeTabId) {
        BrowserSessionManager.activeTabId = activeTabId
        BrowserSessionManager.saveSession(context)
    }
    val currentTab = tabs.find { it.id == activeTabId } ?: tabs.first()
    val coroutineScope = rememberCoroutineScope()

    var inputUrl by remember(activeTabId, currentTab.url) {
        mutableStateOf(if (currentTab.url == "speeddown://home" || currentTab.url == "about:blank") "" else currentTab.url)
    }
    var showSnifferSheet by remember { mutableStateOf(false) }
    var showTabsSheet by remember { mutableStateOf(false) }
    var showAdBlockDialog by remember { mutableStateOf(false) }
    var showBrowserSettings by remember { mutableStateOf(false) }
    var showZoomDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var adBlockEnabled by remember { mutableStateOf(AdBlockEngine.isEnabled) }
    var blockedCountState by remember { mutableIntStateOf(AdBlockEngine.blockedAdsCount) }
    var customFullscreenView by remember { mutableStateOf<View?>(null) }
    var customFullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var pendingDownload by remember { mutableStateOf<PendingBrowserDownload?>(null) }

    // HLS Multi-Quality Picker State
    var hlsVariantsToPick by remember { mutableStateOf<List<HlsStreamVariant>?>(null) }
    var hlsTargetMedia by remember { mutableStateOf<SniffedMedia?>(null) }
    var isResolvingHls by remember { mutableStateOf(false) }

    // In-App Video Preview Dialog State
    var previewMedia by remember { mutableStateOf<SniffedMedia?>(null) }

    // Predictive Back Gesture & Hierarchical In-App Navigation
    BackHandler(enabled = true) {
        if (pendingDownload != null) {
            pendingDownload = null
        } else if (showMoreMenu) {
            showMoreMenu = false
        } else if (showBrowserSettings) {
            showBrowserSettings = false
        } else if (showTabsSheet) {
            showTabsSheet = false
        } else if (showSnifferSheet) {
            showSnifferSheet = false
        } else if (showAdBlockDialog) {
            showAdBlockDialog = false
        } else if (showZoomDialog) {
            showZoomDialog = false
        } else if (previewMedia != null) {
            previewMedia = null
        } else if (hlsVariantsToPick != null) {
            hlsVariantsToPick = null
        } else if (customFullscreenView != null) {
            customFullscreenCallback?.onCustomViewHidden()
            customFullscreenView = null
            customFullscreenCallback = null
        } else if (currentTab.canGoBack) {
            currentTab.geckoTabSession?.goBack() ?: currentTab.webView?.goBack()
        } else {
            // Reached beginning of browsing history on this tab: return smoothly to main app, keeping website open in the tab!
            onClose()
        }
    }

    if (showBrowserSettings) {
        BrowserSettingsScreen(
            currentSettings = browserSettings,
            onSaveSettings = { updated ->
                coroutineScope.launch {
                    browserSettingsStore.updateSettings(updated)
                }
            },
            onClearData = { clearCache, clearCookies, clearHistory, clearStorage ->
                coroutineScope.launch(Dispatchers.Main) {
                    if (clearCookies) {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                    }
                    if (clearCache) {
                        tabs.forEach { it.webView?.clearCache(true) }
                    }
                    if (clearHistory) {
                        tabs.forEach { it.webView?.clearHistory() }
                    }
                    if (clearStorage) {
                        WebStorage.getInstance().deleteAllData()
                    }
                    Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                }
            },
            onResetShortcuts = {
                coroutineScope.launch {
                    browserSettingsStore.resetShortcutsToDefault()
                }
                Toast.makeText(context, "Speed dial shortcuts reset to defaults", Toast.LENGTH_SHORT).show()
            },
            onBack = { showBrowserSettings = false }
        )
        return
    }

    // Live sync browser settings to all open webviews
    LaunchedEffect(browserSettings) {
        tabs.forEach { tab ->
            tab.webView?.settings?.let { s ->
                s.textZoom = browserSettings.textZoom
                s.javaScriptEnabled = browserSettings.javaScriptEnabled
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    s.forceDark = if (browserSettings.forceDarkMode) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                }
            }
            tab.webView?.let { wv ->
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, browserSettings.acceptThirdPartyCookies && !tab.isIncognito)
            }
        }
    }

    // Detach WebViews cleanly when leaving BrowserScreen so they can safely reattach later without being destroyed
    DisposableEffect(Unit) {
        onDispose {
            BrowserSessionManager.saveSession(context)
            tabs.forEach { tab ->
                tab.webView?.let { wv ->
                    (wv.parent as? ViewGroup)?.removeView(wv)
                }
            }
        }
    }

    // Keep inputUrl synced when activeTab changes
    LaunchedEffect(activeTabId) {
        inputUrl = if (currentTab.url == "speeddown://home") "" else currentTab.url
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val isHome = currentTab.url == "speeddown://home" || currentTab.url.isEmpty() || currentTab.url == "about:blank"
                    if (isHome) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "SpeedDown Browser",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        // Spacious, clean, modern address bar with Security Lock indicator
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(22.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = Purple.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(22.dp)
                                )
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val isHttps = currentTab.url.startsWith("https://", ignoreCase = true)
                                val isHttp = currentTab.url.startsWith("http://", ignoreCase = true)
                                Icon(
                                    imageVector = if (isHttps) Icons.Filled.Lock else if (isHttp) Icons.Filled.LockOpen else Icons.Filled.Search,
                                    contentDescription = if (isHttps) "Secure HTTPS" else if (isHttp) "Not Secure HTTP" else "Search",
                                    tint = if (isHttps) Green else if (isHttp) Amber else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )

                                Spacer(Modifier.width(8.dp))

                                BasicTextField(
                                    value = inputUrl,
                                    onValueChange = { inputUrl = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Normal
                                    ),
                                    cursorBrush = SolidColor(Purple),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = {
                                        focusManager.clearFocus()
                                        var target = inputUrl.trim()
                                        if (!target.startsWith("http://") && !target.startsWith("https://")) {
                                            target = if (target.contains(".") && !target.contains(" ")) {
                                                if (browserSettings.httpsOnly) "https://$target" else "http://$target"
                                            } else {
                                                browserSettings.getSearchUrl(target)
                                            }
                                        } else if (browserSettings.httpsOnly && target.startsWith("http://", ignoreCase = true)) {
                                            target = "https://" + target.substring(7)
                                        }
                                        currentTab.url = target
                                        inputUrl = target
                                        currentTab.geckoTabSession?.loadUri(target) ?: currentTab.webView?.loadUrl(target)
                                    }),
                                    modifier = Modifier.weight(1f),
                                    decorationBox = { innerTextField ->
                                        if (inputUrl.isEmpty()) {
                                            Text(
                                                "Search or enter URL...",
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        }
                                        innerTextField()
                                    }
                                )

                                val isBookmarked = shortcuts.any { it.url.equals(currentTab.url, ignoreCase = true) }
                                if (currentTab.url != "speeddown://home" && currentTab.url.startsWith("http")) {
                                    IconButton(
                                        onClick = {
                                            if (isBookmarked) {
                                                val existing = shortcuts.find { it.url.equals(currentTab.url, ignoreCase = true) }
                                                if (existing != null) {
                                                    coroutineScope.launch {
                                                        browserSettingsStore.removeShortcut(existing.id)
                                                    }
                                                    Toast.makeText(context, "Removed from Shortcuts", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                val title = currentTab.title.ifBlank { "Saved Site" }
                                                coroutineScope.launch {
                                                    browserSettingsStore.addShortcut(title, currentTab.url)
                                                }
                                                Toast.makeText(context, "Added to Home Shortcuts!", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isBookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
                                            contentDescription = "Bookmark",
                                            tint = if (isBookmarked) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                if (inputUrl.isNotBlank()) {
                                    IconButton(
                                        onClick = { inputUrl = "" },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Clear",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateToDownloads) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Downloads")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            blockedCountState = AdBlockEngine.blockedAdsCount
                            showAdBlockDialog = true
                        }
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            Icon(
                                Icons.Filled.Security,
                                contentDescription = "uBlock Origin",
                                tint = if (adBlockEnabled && GeckoEngine.isUBlockActive) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            if (adBlockEnabled) {
                                Box(
                                    modifier = Modifier
                                        .size(15.dp)
                                        .clip(CircleShape)
                                        .background(if (blockedCountState > 0) Purple else Green),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (blockedCountState > 0) {
                                            if (blockedCountState > 99) "99+" else "$blockedCountState"
                                        } else "✓",
                                        color = Color.White,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (currentTab.isIncognito) Color(0xFF1E1B2E) else MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Back
                    IconButton(
                        onClick = {
                            if (currentTab.canGoBack) {
                                currentTab.geckoTabSession?.goBack() ?: currentTab.webView?.goBack()
                            } else if (currentTab.url != "speeddown://home") {
                                currentTab.url = "speeddown://home"
                                currentTab.title = "SpeedDown Home"
                                inputUrl = ""
                                currentTab.geckoTabSession?.loadUri("about:blank") ?: currentTab.webView?.loadUrl("about:blank")
                            }
                        },
                        enabled = currentTab.canGoBack || (currentTab.url != "speeddown://home" && currentTab.url.isNotBlank()),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 2. Forward
                    IconButton(
                        onClick = { currentTab.geckoTabSession?.goForward() ?: currentTab.webView?.goForward() },
                        enabled = currentTab.canGoForward,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Forward",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 3. Home
                    IconButton(
                        onClick = {
                            currentTab.url = "speeddown://home"
                            currentTab.title = "SpeedDown Home"
                            inputUrl = ""
                            currentTab.geckoTabSession?.loadUri("about:blank") ?: currentTab.webView?.loadUrl("about:blank")
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Filled.Home,
                            contentDescription = "Home",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 4. New Tab
                    IconButton(
                        onClick = {
                            val newTab = BrowserTabItem(
                                initialUrl = browserSettings.getHomeUrl(),
                                isIncognito = false
                            ).apply {
                                isDesktopMode = browserSettings.defaultDesktopMode
                            }
                            tabs.add(newTab)
                            activeTabId = newTab.id
                            inputUrl = if (newTab.url == "speeddown://home") "" else newTab.url
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "New Tab",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 5. Tab Switcher Badge
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable { showTabsSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .border(
                                    1.6.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                    RoundedCornerShape(7.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${tabs.size}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // 6. 3-Dot More Menu (with Video Capture Sniffer inside!)
                    Box(
                        modifier = Modifier.size(44.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "More Options",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(22.dp)
                                )
                                if (detectedMedia.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .size(15.dp)
                                            .clip(CircleShape)
                                            .background(Green),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (detectedMedia.size > 9) "9+" else "${detectedMedia.size}",
                                            fontSize = 9.sp,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            // Downloads Manager Navigation
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Downloads", fontWeight = FontWeight.Bold)
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.DownloadForOffline,
                                        contentDescription = "Downloads",
                                        tint = Purple
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    onNavigateToDownloads()
                                }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // Video Capture / Media Sniffer shifted inside 3-dot menu!
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            if (detectedMedia.isNotEmpty()) "Captured Media (${detectedMedia.size})" else "Media Sniffer",
                                            fontWeight = if (detectedMedia.isNotEmpty()) FontWeight.Bold else FontWeight.Medium,
                                            color = if (detectedMedia.isNotEmpty()) Green else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.width(16.dp))
                                        if (detectedMedia.isNotEmpty()) {
                                            Surface(
                                                color = Green,
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text(
                                                    text = "${detectedMedia.size} Ready",
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        } else {
                                            Text(
                                                "0 found",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Download,
                                        contentDescription = null,
                                        tint = if (detectedMedia.isNotEmpty()) Green else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    showSnifferSheet = true
                                }
                            )

                            // Reload Page
                            DropdownMenuItem(
                                text = { Text("Reload Page", fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = Purple)
                                },
                                onClick = {
                                    showMoreMenu = false
                                    currentTab.geckoTabSession?.reload() ?: currentTab.webView?.reload()
                                }
                            )

                            // Zoom Controls
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Zoom Webpage", fontWeight = FontWeight.Medium)
                                        Spacer(Modifier.width(16.dp))
                                        Text(
                                            "${browserSettings.textZoom}%",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.ZoomIn, contentDescription = null, tint = Blue)
                                },
                                onClick = {
                                    showMoreMenu = false
                                    showZoomDialog = true
                                }
                            )

                            // uBlock Origin Extension
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("uBlock Origin", fontWeight = FontWeight.Bold)
                                            Text(
                                                "Built-in WebExtension",
                                                fontSize = 11.sp,
                                                color = if (adBlockEnabled) Green else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(Modifier.width(16.dp))
                                        if (adBlockEnabled && blockedCountState > 0) {
                                            Surface(
                                                color = Purple,
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text(
                                                    text = "$blockedCountState",
                                                    color = Color.White,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Security,
                                        contentDescription = null,
                                        tint = if (adBlockEnabled) Green else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    blockedCountState = AdBlockEngine.blockedAdsCount
                                    showAdBlockDialog = true
                                }
                            )

                            // Desktop Site Switch
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Desktop Site", fontWeight = FontWeight.Medium)
                                        Spacer(Modifier.width(16.dp))
                                        Checkbox(
                                            checked = currentTab.isDesktopMode,
                                            onCheckedChange = null,
                                            colors = CheckboxDefaults.colors(checkedColor = Purple),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        if (currentTab.isDesktopMode) Icons.Filled.DesktopMac else Icons.Filled.Smartphone,
                                        contentDescription = null,
                                        tint = if (currentTab.isDesktopMode) Purple else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    currentTab.isDesktopMode = !currentTab.isDesktopMode
                                    currentTab.geckoTabSession?.setDesktop(currentTab.isDesktopMode)
                                    currentTab.webView?.settings?.userAgentString = if (currentTab.isDesktopMode) DESKTOP_UA else MOBILE_UA
                                    currentTab.webView?.settings?.useWideViewPort = true
                                    currentTab.webView?.settings?.loadWithOverviewMode = true
                                    currentTab.webView?.reload()
                                }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // Browser Settings
                            DropdownMenuItem(
                                text = { Text("Browser Settings", fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                                },
                                onClick = {
                                    showMoreMenu = false
                                    showBrowserSettings = true
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (currentTab.webProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { currentTab.webProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = Purple,
                    trackColor = Color.Transparent
                )
            }

            val isHomePage = currentTab.url == "speeddown://home" || currentTab.url.isEmpty() || currentTab.url == "about:blank"
            if (isHomePage) {
                BrowserHomeScreen(
                    settings = browserSettings,
                    shortcuts = shortcuts,
                    onNavigate = { targetUrl ->
                        var target = targetUrl.trim()
                        if (!target.startsWith("http://") && !target.startsWith("https://")) {
                            target = if (target.contains(".") && !target.contains(" ")) {
                                if (browserSettings.httpsOnly) "https://$target" else "http://$target"
                            } else {
                                browserSettings.getSearchUrl(target)
                            }
                        } else if (browserSettings.httpsOnly && target.startsWith("http://", ignoreCase = true)) {
                            target = "https://" + target.substring(7)
                        }
                        currentTab.url = target
                        inputUrl = target
                        currentTab.geckoTabSession?.loadUri(target) ?: currentTab.webView?.loadUrl(target)
                    },
                    onOpenSettings = { showBrowserSettings = true },
                    onAddShortcut = { title, url ->
                        coroutineScope.launch {
                            browserSettingsStore.addShortcut(title, url)
                        }
                        Toast.makeText(context, "Added shortcut: $title", Toast.LENGTH_SHORT).show()
                    },
                    onRemoveShortcut = { id ->
                        coroutineScope.launch {
                            browserSettingsStore.removeShortcut(id)
                        }
                        Toast.makeText(context, "Shortcut removed", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Mozilla GeckoView Engine with built-in uBlock Origin Extension
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        GeckoView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { gView ->
                        if (currentTab.geckoTabSession == null) {
                            currentTab.geckoTabSession = GeckoTabSession(
                                context = context,
                                isIncognito = currentTab.isIncognito,
                                isDesktopMode = currentTab.isDesktopMode,
                                onTitleChanged = { newTitle ->
                                    currentTab.title = newTitle
                                },
                                onUrlChanged = { newUrl ->
                                    currentTab.url = newUrl
                                    if (currentTab.id == activeTabId) {
                                        inputUrl = newUrl
                                    }
                                },
                                onProgressChanged = { progress ->
                                    currentTab.webProgress = progress
                                },
                                onCanGoBackChanged = { canBack ->
                                    currentTab.canGoBack = canBack
                                },
                                onCanGoForwardChanged = { canForward ->
                                    currentTab.canGoForward = canForward
                                },
                                onNewTabRequested = { targetUrl ->
                                    val newTab = BrowserTabItem(
                                        initialUrl = targetUrl,
                                        isIncognito = currentTab.isIncognito
                                    ).apply {
                                        isDesktopMode = browserSettings.defaultDesktopMode
                                    }
                                    tabs.add(newTab)
                                    activeTabId = newTab.id
                                    inputUrl = targetUrl
                                    Toast.makeText(context, "Opened in new tab", Toast.LENGTH_SHORT).show()
                                },
                                onMediaSniffed = { mediaUrl ->
                                    sniffMediaUrl(mediaUrl, detectedMedia)
                                },
                                onDownloadRequested = { url, fileName, threads ->
                                    pendingDownload = PendingBrowserDownload(url, fileName, threads)
                                },
                                onAdBlocked = {
                                    blockedCountState = AdBlockEngine.blockedAdsCount
                                }
                            )
                            if (currentTab.url.startsWith("http://") || currentTab.url.startsWith("https://")) {
                                currentTab.geckoTabSession?.loadUri(currentTab.url)
                            }
                        }

                        val activeSession = currentTab.geckoTabSession?.session
                        if (activeSession != null && gView.session !== activeSession) {
                            gView.setSession(activeSession)
                        }
                    }
                )
            }
        }
    }

    // Interactive Download Configuration Dialog (Configure Filename and Threads before download starts)
    pendingDownload?.let { download ->
        BrowserDownloadConfigDialog(
            url = download.url,
            initialFileName = download.initialFileName,
            defaultThreads = download.initialThreads,
            onDismiss = { pendingDownload = null },
            onConfirm = { editedFileName, selectedThreads ->
                pendingDownload = null
                onStartDownload(download.url, editedFileName, selectedThreads)
            }
        )
    }

    // Tabs Management Bottom Sheet
    if (showTabsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTabsSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tabs (${tabs.size})", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = {
                                val newTab = BrowserTabItem(
                                    initialUrl = browserSettings.getHomeUrl(),
                                    isIncognito = false
                                ).apply {
                                    isDesktopMode = browserSettings.defaultDesktopMode
                                }
                                tabs.add(newTab)
                                activeTabId = newTab.id
                                inputUrl = if (newTab.url == "speeddown://home") "" else newTab.url
                                showTabsSheet = false
                            }
                        ) {
                            Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("New Tab", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        TextButton(
                            onClick = {
                                val newTab = BrowserTabItem(
                                    initialUrl = browserSettings.getHomeUrl(),
                                    isIncognito = true
                                ).apply {
                                    isDesktopMode = browserSettings.defaultDesktopMode
                                }
                                tabs.add(newTab)
                                activeTabId = newTab.id
                                inputUrl = if (newTab.url == "speeddown://home") "" else newTab.url
                                showTabsSheet = false
                            }
                        ) {
                            Icon(Icons.Filled.VpnKey, null, modifier = Modifier.size(16.dp), tint = Amber)
                            Spacer(Modifier.width(2.dp))
                            Text("Incognito", fontWeight = FontWeight.Bold, color = Amber, fontSize = 12.sp)
                        }

                        if (tabs.size > 1) {
                            TextButton(
                                onClick = {
                                    tabs.filter { it.id != activeTabId }.forEach {
                                        try {
                                            it.geckoTabSession?.close()
                                        } catch (_: Exception) {}
                                        it.geckoTabSession = null
                                        it.webView?.let { wv ->
                                            (wv.parent as? ViewGroup)?.removeView(wv)
                                            wv.stopLoading()
                                            wv.clearHistory()
                                            wv.removeAllViews()
                                            wv.destroy()
                                        }
                                        it.webView = null
                                    }
                                    tabs.removeAll { it.id != activeTabId }
                                }
                            ) {
                                Text("Close Others", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        val isSelected = tab.id == activeTabId
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) Purple.copy(0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(0.4f),
                            border = if (isSelected) BorderStroke(1.8.dp, Purple) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                                .clickable {
                                    activeTabId = tab.id
                                    inputUrl = tab.url
                                    showTabsSheet = false
                                }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = tab.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            try {
                                                tab.geckoTabSession?.close()
                                            } catch (_: Exception) {}
                                            tab.geckoTabSession = null
                                            tab.webView?.let { wv ->
                                                (wv.parent as? ViewGroup)?.removeView(wv)
                                                wv.stopLoading()
                                                wv.clearHistory()
                                                wv.removeAllViews()
                                                wv.destroy()
                                            }
                                            tab.webView = null
                                            val index = tabs.indexOf(tab)
                                            tabs.remove(tab)
                                            if (tabs.isEmpty()) {
                                                val fresh = BrowserTabItem(initialUrl = browserSettings.getHomeUrl())
                                                tabs.add(fresh)
                                                activeTabId = fresh.id
                                                inputUrl = ""
                                            } else if (activeTabId == tab.id) {
                                                val next = (index - 1).coerceAtLeast(0)
                                                activeTabId = tabs[next].id
                                                inputUrl = if (tabs[next].url == "speeddown://home") "" else tabs[next].url
                                            }
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Filled.Close, "Close Tab", modifier = Modifier.size(16.dp))
                                    }
                                }

                                Text(
                                    text = tab.url.replace("https://", "").replace("http://", ""),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (tab.isIncognito) {
                                        Text("🕶️ Incognito", fontSize = 9.sp, color = Amber, fontWeight = FontWeight.Bold)
                                    }
                                    if (tab.isDesktopMode) {
                                        Text("🖥️ Desktop", fontSize = 9.sp, color = Blue, fontWeight = FontWeight.Bold)
                                    }
                                    if (isSelected) {
                                        Text("● Active", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Purple)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // uBlock Shield Dialog
    if (showAdBlockDialog) {
        AlertDialog(
            onDismissRequest = { showAdBlockDialog = false },
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (adBlockEnabled) Green.copy(0.15f) else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Security,
                        null,
                        tint = if (adBlockEnabled) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("uBlock Origin", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Surface(
                        color = if (adBlockEnabled && GeckoEngine.isUBlockActive) Green.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (adBlockEnabled && GeckoEngine.isUBlockActive) "BUILT-IN ACTIVE" else if (adBlockEnabled) "INITIALIZING" else "PAUSED",
                            color = if (adBlockEnabled && GeckoEngine.isUBlockActive) Green else Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Engine Protection", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                "Mozilla GeckoView + uBlock Origin v1.75",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = adBlockEnabled,
                            onCheckedChange = {
                                adBlockEnabled = it
                                AdBlockEngine.isEnabled = it
                            }
                        )
                    }

                    HorizontalDivider()

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Ads & Rogue Popups Blocked", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "$blockedCountState Blocked",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 22.sp,
                                        color = Green
                                    )
                                }
                                if (blockedCountState > 0) {
                                    TextButton(
                                        onClick = {
                                            AdBlockEngine.resetCount()
                                            blockedCountState = 0
                                        }
                                    ) {
                                        Text("Reset", fontSize = 12.sp, color = Purple)
                                    }
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🛡️ uBlock WebExtension: Active in All Web Frames", fontSize = 12.sp)
                        Text("🚫 EasyList + EasyPrivacy: Banners & Trackers blocked", fontSize = 12.sp)
                        Text("🛑 Fake Link & Rogue Redirects: Blocked at engine level", fontSize = 12.sp)
                        Text("⚡ Multi-Hop Safe: Genuine countdown & downloads flow naturally", fontSize = 12.sp)
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Green.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Green, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Extension: ${GeckoEngine.extensionId ?: "uBlock0@raymondhill.net"}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAdBlockDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Text("Done")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    AdBlockEngine.resetCount()
                    blockedCountState = 0
                }) {
                    Text("Reset Count")
                }
            }
        )
    }

    // Webpage Zoom Controls Dialog
    if (showZoomDialog) {
        var currentZoom by remember { mutableIntStateOf(currentTab.webView?.settings?.textZoom ?: browserSettings.textZoom) }
        AlertDialog(
            onDismissRequest = { showZoomDialog = false },
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Purple.copy(0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.ZoomIn, null, tint = Purple, modifier = Modifier.size(28.dp))
                }
            },
            title = {
                Text("Webpage Zoom Controls", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "$currentZoom%",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Purple
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val next = (currentZoom - 10).coerceAtLeast(50)
                                currentZoom = next
                                currentTab.webView?.settings?.textZoom = next
                                currentTab.webView?.zoomOut()
                                coroutineScope.launch {
                                    browserSettingsStore.updateSettings(browserSettings.copy(textZoom = next))
                                }
                            },
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Filled.Remove, "Zoom Out")
                        }

                        Button(
                            onClick = {
                                currentZoom = 100
                                currentTab.webView?.settings?.textZoom = 100
                                coroutineScope.launch {
                                    browserSettingsStore.updateSettings(browserSettings.copy(textZoom = 100))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("100% (Reset)", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = {
                                val next = (currentZoom + 10).coerceAtMost(300)
                                currentZoom = next
                                currentTab.webView?.settings?.textZoom = next
                                currentTab.webView?.zoomIn()
                                coroutineScope.launch {
                                    browserSettingsStore.updateSettings(browserSettings.copy(textZoom = next))
                                }
                            },
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Filled.Add, "Zoom In")
                        }
                    }

                    Slider(
                        value = currentZoom.toFloat(),
                        onValueChange = {
                            val v = it.toInt()
                            currentZoom = v
                            currentTab.webView?.settings?.textZoom = v
                        },
                        onValueChangeFinished = {
                            coroutineScope.launch {
                                browserSettingsStore.updateSettings(browserSettings.copy(textZoom = currentZoom))
                            }
                        },
                        valueRange = 50f..250f,
                        steps = 19,
                        colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple)
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.TouchApp, null, tint = Purple, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Tip: You can also pinch-to-zoom anywhere on any webpage with two fingers.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showZoomDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Text("Done")
                }
            }
        )
    }

    // Sniffer Media Bottom Sheet
    if (showSnifferSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSnifferSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Green.copy(0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Download, null, tint = Green, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("Captured Downloads", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    TextButton(onClick = {
                        detectedMedia.clear()
                        detectedUrlsCache.clear()
                    }) {
                        Text("Clear All", color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (detectedMedia.isEmpty()) {
                    Text(
                        "No downloadable media captured yet. Browse any site with videos or download links!",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(detectedMedia, key = { it.url }) { media ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(0.4f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            if (media.type.contains("Video")) Icons.Filled.PlayCircle
                                            else Icons.AutoMirrored.Filled.InsertDriveFile,
                                            null,
                                            tint = Purple,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                media.fileName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                media.type,
                                                fontSize = 11.sp,
                                                color = Purple
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(6.dp))

                                    // In-App Mini Video Preview
                                    if (media.type.contains("Video")) {
                                        OutlinedButton(
                                            onClick = { previewMedia = media },
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Filled.Visibility, null, modifier = Modifier.size(14.dp), tint = Purple)
                                            Spacer(Modifier.width(2.dp))
                                            Text("Preview", fontSize = 11.sp, color = Purple, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(Modifier.width(6.dp))
                                    }

                                    // Direct Play in Nothing Player / External Player
                                    if (media.type.contains("Video") || media.type.contains("Audio")) {
                                        OutlinedButton(
                                            onClick = {
                                                try {
                                                    val playIntent = Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(Uri.parse(media.url), if (media.type.contains("Video")) "video/*" else "audio/*")
                                                        setPackage("com.nothing.player")
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(playIntent)
                                                } catch (_: Exception) {
                                                    try {
                                                        val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                                                            setDataAndType(Uri.parse(media.url), if (media.type.contains("Video")) "video/*" else "audio/*")
                                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        }
                                                        context.startActivity(fallbackIntent)
                                                    } catch (_: Exception) {}
                                                }
                                                showSnifferSheet = false
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(14.dp), tint = Green)
                                            Spacer(Modifier.width(2.dp))
                                            Text("Play", fontSize = 11.sp, color = Green, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(Modifier.width(6.dp))
                                    }

                                    Button(
                                        onClick = {
                                            if (media.url.contains(".m3u8", ignoreCase = true)) {
                                                isResolvingHls = true
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val client = OkHttpClient.Builder()
                                                        .dns(SecureDnsHelper.createOkHttpDns(browserSettings.dnsProvider, browserSettings.customDnsIp))
                                                        .connectTimeout(8, TimeUnit.SECONDS)
                                                        .readTimeout(10, TimeUnit.SECONDS)
                                                        .build()
                                                    val hlsParser = com.example.speeddown.engine.HlsDownloader(client, com.example.speeddown.data.DownloadStore.getInstance(context))
                                                    val variants = hlsParser.parseVariantStreams(media.url)
                                                    withContext(Dispatchers.Main) {
                                                        isResolvingHls = false
                                                        if (variants.size > 1) {
                                                            hlsVariantsToPick = variants
                                                            hlsTargetMedia = media
                                                        } else {
                                                            showSnifferSheet = false
                                                            pendingDownload = PendingBrowserDownload(media.url, media.fileName, 32)
                                                        }
                                                    }
                                                }
                                            } else {
                                                showSnifferSheet = false
                                                pendingDownload = PendingBrowserDownload(media.url, media.fileName, 32)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Purple),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("Download", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // In-App Video Preview Dialog
    previewMedia?.let { pMedia ->
        AlertDialog(
            onDismissRequest = { previewMedia = null },
            title = {
                Text(
                    pMedia.fileName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                val mediaController = android.widget.MediaController(ctx)
                                mediaController.setAnchorView(this)
                                setMediaController(mediaController)
                                setVideoURI(Uri.parse(pMedia.url))
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val url = pMedia.url
                        val name = pMedia.fileName
                        previewMedia = null
                        showSnifferSheet = false
                        pendingDownload = PendingBrowserDownload(url, name, 32)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { previewMedia = null }) {
                    Text("Close")
                }
            }
        )
    }

    // HLS Stream Quality Picker Dialog
    if (hlsVariantsToPick != null && hlsTargetMedia != null) {
        AlertDialog(
            onDismissRequest = {
                hlsVariantsToPick = null
                hlsTargetMedia = null
            },
            icon = {
                Icon(Icons.Filled.HighQuality, null, tint = Purple, modifier = Modifier.size(32.dp))
            },
            title = {
                Text("Select Video Quality", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = hlsTargetMedia?.fileName ?: "HLS Video Stream",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(hlsVariantsToPick ?: emptyList()) { variant ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, Purple.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val target = hlsTargetMedia
                                        hlsVariantsToPick = null
                                        hlsTargetMedia = null
                                        showSnifferSheet = false
                                        if (target != null) {
                                            val cleanName = target.fileName.substringBeforeLast(".")
                                            val qualitySuffix = variant.label.replace(" ", "_")
                                            pendingDownload = PendingBrowserDownload(variant.url, "${cleanName}_$qualitySuffix.mp4", 32)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(variant.label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(variant.resolution, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Purple.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            if (variant.bandwidth > 0) "${variant.bandwidth / 1000} kbps" else "Stream",
                                            color = Purple,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = hlsTargetMedia
                    hlsVariantsToPick = null
                    hlsTargetMedia = null
                    showSnifferSheet = false
                    if (target != null) {
                        onStartDownload(target.url, target.fileName, 32)
                    }
                }) {
                    Text("Auto / Best Quality")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    hlsVariantsToPick = null
                    hlsTargetMedia = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Fullscreen HTML5 Video View (Overlays entire screen when video enters fullscreen)
    customFullscreenView?.let { fView ->
        BackHandler {
            customFullscreenCallback?.onCustomViewHidden()
            customFullscreenView = null
            customFullscreenCallback = null
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = {
                    (fView.parent as? ViewGroup)?.removeView(fView)
                    fView
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createTabWebView(
    context: Context,
    tab: BrowserTabItem,
    browserSettings: BrowserSettings,
    detectedMedia: MutableList<SniffedMedia>,
    onUrlChanged: (String) -> Unit,
    onAdBlocked: () -> Unit,
    onOpenNewTab: (String) -> Unit,
    onShowCustomView: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideCustomView: () -> Unit,
    onRequestDownloadConfig: (url: String, fileName: String, threads: Int) -> Unit
): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        settings.apply {
            javaScriptEnabled = browserSettings.javaScriptEnabled
            textZoom = browserSettings.textZoom
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                forceDark = if (browserSettings.forceDarkMode) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            }
            domStorageEnabled = !tab.isIncognito
            databaseEnabled = !tab.isIncognito
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = !tab.isIncognito
            allowContentAccess = true
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val isConnected = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val network = cm?.activeNetwork
                    val caps = cm?.getNetworkCapabilities(network)
                    caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                } else {
                    @Suppress("DEPRECATION")
                    cm?.activeNetworkInfo?.isConnected == true
                }
            } catch (_: Exception) { true }
            cacheMode = if (tab.isIncognito) {
                WebSettings.LOAD_NO_CACHE
            } else if (isConnected) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
            setSupportMultipleWindows(true) // Enable support for multiple windows / new tabs
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = if (tab.isDesktopMode) DESKTOP_UA else MOBILE_UA
        }

        // Enable third-party cookies if configured and not incognito
        val currentWebView = this
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(!tab.isIncognito)
        cookieManager.setAcceptThirdPartyCookies(currentWebView, browserSettings.acceptThirdPartyCookies && !tab.isIncognito)

        // Native Download Listener for binary MIME types, Content-Disposition attachments, and server downloads
        setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            val guessedName = try {
                val guessed = URLUtil.guessFileName(url, contentDisposition, mimetype)
                if (guessed.isNotBlank() && !guessed.endsWith(".bin")) {
                    guessed
                } else {
                    val fromUrl = url.substringAfterLast("/").substringBefore("?").substringBefore("#")
                    if (fromUrl.isNotBlank() && fromUrl.contains(".")) {
                        java.net.URLDecoder.decode(fromUrl, "UTF-8")
                    } else {
                        guessed
                    }
                }
            } catch (_: Exception) {
                "download_${System.currentTimeMillis()}"
            }

            sniffMediaUrl(url, detectedMedia)
            mainHandler.post {
                onRequestDownloadConfig(url, guessedName, 16)
            }
        }

        // Bridge for media sniffer
        addJavascriptInterface(object {
            @JavascriptInterface
            fun onMediaFound(streamUrl: String, title: String) {
                sniffMediaUrl(streamUrl, detectedMedia)
            }
        }, "SpeedDownBridge")

        // Bridge for uBlock Origin defusers in web context
        addJavascriptInterface(object {
            @JavascriptInterface
            fun isAdUrl(url: String): Boolean {
                return AdBlockEngine.isAd(url) || AdBlockEngine.isRogueRedirect(url)
            }

            @JavascriptInterface
            fun notifyAdBlocked() {
                AdBlockEngine.recordBlock()
                mainHandler.post { onAdBlocked() }
            }
        }, "SpeedDownAdBlock")

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    tab.url = it
                    onUrlChanged(it)
                    BrowserSessionManager.saveSession(context)
                }
                tab.canGoBack = canGoBack()
                tab.canGoForward = canGoForward()

                // Privacy: Do Not Track & Global Privacy Control signals
                if (browserSettings.doNotTrack) {
                    view?.evaluateJavascript(
                        "try { Object.defineProperty(navigator, 'doNotTrack', { value: '1', configurable: true }); Object.defineProperty(navigator, 'globalPrivacyControl', { value: true, configurable: true }); } catch(e){}",
                        null
                    )
                }

                // Inject uBlock Origin defusers immediately at page start before site scripts run
                if (AdBlockEngine.isEnabled) {
                    view?.evaluateJavascript(AdBlockEngine.UBLOCK_SCRIPTLET_DEFUSER_JS, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tab.title = view?.title ?: "Page"
                tab.canGoBack = canGoBack()
                tab.canGoForward = canGoForward()
                url?.let { if (it.isNotBlank()) tab.url = it }
                BrowserSessionManager.saveSession(context)

                // Re-inject uBlock defusers and cosmetic CSS ad filter
                if (AdBlockEngine.isEnabled) {
                    view?.evaluateJavascript(AdBlockEngine.UBLOCK_SCRIPTLET_DEFUSER_JS, null)
                    view?.evaluateJavascript(AdBlockEngine.COSMETIC_AD_BLOCK_JS, null)
                }

                // Auto-sniff embedded HTML5 video/audio elements and source tags
                view?.evaluateJavascript(
                    """
                    (function() {
                        function inspect() {
                            var els = document.querySelectorAll('video, audio');
                            for (var i = 0; i < els.length; i++) {
                                var v = els[i];
                                if (v.src && v.src.indexOf('http') === 0) {
                                    window.SpeedDownBridge && window.SpeedDownBridge.onMediaFound(v.src, document.title || 'Video Stream');
                                }
                                var srcs = v.querySelectorAll('source');
                                for (var j = 0; j < srcs.length; j++) {
                                    if (srcs[j].src && srcs[j].src.indexOf('http') === 0) {
                                        window.SpeedDownBridge && window.SpeedDownBridge.onMediaFound(srcs[j].src, document.title || 'Video Stream');
                                    }
                                }
                            }
                        }
                        inspect();
                        setInterval(inspect, 2500);
                    })();
                    """.trimIndent(),
                    null
                )
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val reqUrl = request?.url?.toString() ?: return false

                // 0. HTTPS-Only Mode upgrade
                if (browserSettings.httpsOnly && reqUrl.startsWith("http://", ignoreCase = true)) {
                    val upgraded = "https://" + reqUrl.substring(7)
                    view?.loadUrl(upgraded)
                    return true
                }

                // 1. Allow internal scheme navigations for video players, blobs, and scripts
                if (reqUrl.startsWith("javascript:", ignoreCase = true) ||
                    reqUrl.startsWith("about:", ignoreCase = true) ||
                    reqUrl.startsWith("blob:", ignoreCase = true) ||
                    reqUrl.startsWith("data:", ignoreCase = true)
                ) {
                    return false
                }

                // 2. AGGRESSIVE BLOCK: Check if this destination URL is an ad or rogue redirect
                if (AdBlockEngine.isAd(reqUrl) || AdBlockEngine.isRogueRedirect(reqUrl)) {
                    AdBlockEngine.recordBlock()
                    mainHandler.post { onAdBlocked() }
                    return true // Handled: DROP popup/redirect completely!
                }

                // 3. Direct File Downloads (Archives, APKs, Documents, Torrents, Installers, etc.)
                if (isDownloadableFile(reqUrl)) {
                    val clean = reqUrl.substringBefore("?").substringBefore("#").lowercase()
                    val isMagnet = reqUrl.startsWith("magnet:", ignoreCase = true)
                    val guessedFileName = try {
                        if (isMagnet) {
                            Uri.parse(reqUrl).getQueryParameter("dn") ?: "Torrent_${System.currentTimeMillis()}"
                        } else {
                            val lastSegment = reqUrl.substringAfterLast("/").substringBefore("?").substringBefore("#")
                            if (lastSegment.isNotBlank() && lastSegment.contains(".")) {
                                java.net.URLDecoder.decode(lastSegment, "UTF-8")
                            } else {
                                "download_${System.currentTimeMillis()}"
                            }
                        }
                    } catch (_: Exception) {
                        "download_${System.currentTimeMillis()}"
                    }

                    sniffMediaUrl(reqUrl, detectedMedia)
                    mainHandler.post {
                        onRequestDownloadConfig(reqUrl, guessedFileName, 16)
                    }
                    return true
                }

                // 4. Handle Magnet scheme
                if (reqUrl.startsWith("magnet:", ignoreCase = true)) {
                    val dn = Uri.parse(reqUrl).getQueryParameter("dn") ?: "Torrent_${System.currentTimeMillis()}"
                    sniffMediaUrl(reqUrl, detectedMedia)
                    mainHandler.post {
                        onRequestDownloadConfig(reqUrl, dn, 16)
                    }
                    return true
                }

                // 5. Block rogue non-http/https schemes (intent://, market://, tel://, etc.)
                if (!reqUrl.startsWith("http://", ignoreCase = true) && !reqUrl.startsWith("https://", ignoreCase = true)) {
                    AdBlockEngine.recordBlock()
                    mainHandler.post { onAdBlocked() }
                    return true
                }

                // Redirects are left alone: Normal page navigations flow naturally!
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: ""

                // Intercept & block ad networks / trackers
                if (AdBlockEngine.isAd(reqUrl)) {
                    AdBlockEngine.recordBlock()
                    mainHandler.post { onAdBlocked() }
                    return AdBlockEngine.createEmptyResponse()
                }

                sniffMediaUrl(reqUrl, detectedMedia)
                return super.shouldInterceptRequest(view, request)
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // Block unsolicited popups without user gesture
                if (browserSettings.blockPopups && !isUserGesture) {
                    AdBlockEngine.recordBlock()
                    mainHandler.post { onAdBlocked() }
                    return false
                }

                // Transport message capture for target="_blank" or window.open
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val tempWv = WebView(context).apply {
                    webViewClient = object : WebViewClient() {
                        private var isHandled = false

                        private fun handleDestination(destUrl: String, v: WebView?) {
                            if (isHandled) return
                            isHandled = true
                            v?.stopLoading()
                            v?.destroy()

                            // Drop ad popups
                            if (AdBlockEngine.isAd(destUrl) || AdBlockEngine.isRogueRedirect(destUrl)) {
                                AdBlockEngine.recordBlock()
                                mainHandler.post { onAdBlocked() }
                                return
                            }
                            if (isDownloadableFile(destUrl)) {
                                sniffMediaUrl(destUrl, detectedMedia)
                                mainHandler.post {
                                    onRequestDownloadConfig(destUrl, "download_${System.currentTimeMillis()}", 16)
                                }
                                return
                            }
                            // Legitimate user link opening in a new window/tab
                            mainHandler.post {
                                onOpenNewTab(destUrl)
                            }
                        }

                        override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                            val destUrl = request?.url?.toString() ?: return false
                            handleDestination(destUrl, v)
                            return true
                        }

                        override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(v, url, favicon)
                            url?.let { dest ->
                                if (dest != "about:blank") {
                                    handleDestination(dest, v)
                                }
                            }
                        }
                    }
                }
                mainHandler.postDelayed({
                    try {
                        tempWv.stopLoading()
                        tempWv.destroy()
                    } catch (_: Exception) {}
                }, 8000)
                transport.webView = tempWv
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null && callback != null) {
                    onShowCustomView(view, callback)
                }
            }

            override fun onHideCustomView() {
                onHideCustomView()
            }

            override fun getDefaultVideoPoster(): Bitmap? {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                tab.webProgress = newProgress
                // Inject early at 25% and 60% progress before ad scripts set up overlays
                if (AdBlockEngine.isEnabled && (newProgress == 25 || newProgress == 60)) {
                    view?.evaluateJavascript(AdBlockEngine.UBLOCK_SCRIPTLET_DEFUSER_JS, null)
                }
            }
        }

        loadUrl(tab.url)
    }
}

private fun isDownloadableFile(url: String): Boolean {
    if (url.startsWith("magnet:?xt=", ignoreCase = true)) return true
    val clean = url.substringBefore("?").substringBefore("#").lowercase()
    return clean.endsWith(".zip") || clean.endsWith(".rar") || clean.endsWith(".7z") ||
            clean.endsWith(".tar") || clean.endsWith(".gz") || clean.endsWith(".bz2") || clean.endsWith(".xz") ||
            clean.endsWith(".apk") || clean.endsWith(".xapk") || clean.endsWith(".apks") ||
            clean.endsWith(".iso") || clean.endsWith(".img") || clean.endsWith(".dmg") ||
            clean.endsWith(".exe") || clean.endsWith(".msi") || clean.endsWith(".deb") || clean.endsWith(".rpm") ||
            clean.endsWith(".torrent") || clean.endsWith(".pdf") || clean.endsWith(".epub") ||
            clean.endsWith(".bin") || clean.endsWith(".doc") || clean.endsWith(".docx") ||
            clean.endsWith(".xls") || clean.endsWith(".xlsx") || clean.endsWith(".ppt") || clean.endsWith(".pptx") ||
            clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm") ||
            clean.endsWith(".avi") || clean.endsWith(".mov") || clean.endsWith(".m4v") ||
            clean.endsWith(".flv") || clean.endsWith(".3gp") || clean.endsWith(".wmv") ||
            clean.endsWith(".mp3") || clean.endsWith(".m4a") || clean.endsWith(".aac") ||
            clean.endsWith(".flac") || clean.endsWith(".wav") || clean.endsWith(".ogg")
}

private fun isDirectDownloadUrl(url: String): Boolean {
    val clean = url.substringBefore("?").substringBefore("#").lowercase()
    return clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm") ||
            clean.endsWith(".m3u8") || clean.endsWith(".mpd") || clean.endsWith(".mp3") ||
            isDownloadableFile(url)
}

private fun sniffMediaUrl(url: String, list: MutableList<SniffedMedia>) {
    if (url.isBlank()) return
    if (detectedUrlsCache.contains(url)) return

    val clean = url.substringBefore("?").substringBefore("#").lowercase()
    val mediaType = when {
        clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm") || url.contains("mime=video") -> "Video (MP4/MKV)"
        clean.endsWith(".m3u8") || url.contains(".m3u8") -> "Video Stream (HLS M3U8)"
        clean.endsWith(".mpd") || url.contains(".mpd") -> "Video Stream (DASH MPD)"
        clean.endsWith(".mp3") || clean.endsWith(".m4a") || clean.endsWith(".aac") || clean.endsWith(".flac") -> "Audio (MP3/M4A)"
        clean.endsWith(".zip") || clean.endsWith(".rar") || clean.endsWith(".7z") || clean.endsWith(".tar.gz") -> "Archive (ZIP/RAR)"
        clean.endsWith(".apk") || clean.endsWith(".xapk") -> "App Package (APK)"
        clean.endsWith(".pdf") || clean.endsWith(".epub") -> "Document (PDF)"
        clean.endsWith(".iso") || clean.endsWith(".img") -> "Disk Image (ISO)"
        clean.endsWith(".torrent") -> "BitTorrent (.torrent)"
        url.startsWith("magnet:?xt=urn:btih:", ignoreCase = true) -> "Magnet Torrent Link"
        else -> null
    } ?: return

    if (!detectedUrlsCache.add(url)) return

    val fileName = try {
        if (url.startsWith("magnet:", ignoreCase = true)) {
            val dn = android.net.Uri.parse(url).getQueryParameter("dn")
            dn ?: "torrent_${System.currentTimeMillis()}"
        } else {
            val raw = url.substringAfterLast("/").substringBefore("?").substringBefore("#")
            if (raw.isNotBlank()) java.net.URLDecoder.decode(raw, "UTF-8") else "media_${System.currentTimeMillis()}"
        }
    } catch (_: Exception) {
        "media_${System.currentTimeMillis()}"
    }

    // Strictly mutate Compose SnapshotStateList on Main Looper to guarantee ZERO ConcurrentModificationException!
    mainHandler.post {
        if (list.none { it.url == url }) {
            list.add(0, SniffedMedia(url = url, fileName = fileName, type = mediaType))
        }
    }
}

@Composable
fun BrowserDownloadConfigDialog(
    url: String,
    initialFileName: String,
    defaultThreads: Int = 16,
    onDismiss: () -> Unit,
    onConfirm: (fileName: String, threads: Int) -> Unit
) {
    var fileName by remember { mutableStateOf(initialFileName) }
    var threads by remember { mutableIntStateOf(defaultThreads.coerceIn(1, 100)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Purple.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = Purple,
                    modifier = Modifier.size(26.dp)
                )
            }
        },
        title = {
            Text(
                "Configure Download",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // File Name
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("File Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // URL preview
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Source URL",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            url,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Thread Allocation Slider & Counter (1 to 100 threads)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bolt, null, tint = Amber, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Parallel Threads",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Surface(
                            color = Purple.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                if (threads >= 100) "$threads Hyper 🚀" else "$threads Connections",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Purple,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Slider(
                        value = threads.toFloat(),
                        onValueChange = { threads = it.toInt() },
                        valueRange = 1f..100f,
                        steps = 98,
                        colors = SliderDefaults.colors(
                            thumbColor = Purple,
                            activeTrackColor = Purple
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1 (Safe)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("32 (Fast)", fontSize = 10.sp, color = Purple)
                        Text("64 (Ultra)", fontSize = 10.sp, color = Purple)
                        Text("100 (Hyper 🚀)", fontSize = 10.sp, color = Purple, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(2.dp))

                    // Quick thread preset chips matching main app (8, 16, 32, 64, 100)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(8, 16, 32, 64, 100).forEach { count ->
                            val isSelected = threads == count
                            Surface(
                                color = if (isSelected) Purple else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { threads = count }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (count == 100) "100 🚀" else "${count}T",
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = fileName.trim().ifBlank { initialFileName }
                    onConfirm(finalName, threads)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Purple),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Start Download", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp)
    )
}
