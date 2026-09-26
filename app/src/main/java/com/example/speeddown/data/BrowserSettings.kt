package com.example.speeddown.data

import android.content.Context
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class BrowserShortcut(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val badge: String = "",
    val iconColorHex: String = "#7C3AED"
)

@Serializable
data class BrowserSettings(
    val searchEngine: String = "Google", // Google, DuckDuckGo, Brave, Bing, Yandex
    val dnsProvider: String = "Cloudflare", // System, Cloudflare, Google, AdGuard, Quad9, Custom
    val customDnsHost: String = "", // e.g. dns.adguard-dns.com, 1.1.1.1, one.one.one.one
    val customDnsIp: String = "",
    val httpsOnly: Boolean = true,
    val doNotTrack: Boolean = true,
    val acceptThirdPartyCookies: Boolean = true,
    val forceDarkMode: Boolean = false,
    val defaultDesktopMode: Boolean = false,
    val textZoom: Int = 100, // 50 to 200 %
    val javaScriptEnabled: Boolean = true,
    val blockPopups: Boolean = true
)

class BrowserSettingsStore private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: BrowserSettingsStore? = null

        fun getInstance(context: Context): BrowserSettingsStore {
            return instance ?: synchronized(this) {
                instance ?: BrowserSettingsStore(context.applicationContext).also { instance = it }
            }
        }

        val DEFAULT_SHORTCUTS = listOf(
            BrowserShortcut(title = "Google", url = "https://www.google.com", badge = "G", iconColorHex = "#4285F4"),
            BrowserShortcut(title = "YouTube", url = "https://www.youtube.com", badge = "YT", iconColorHex = "#EF4444"),
            BrowserShortcut(title = "Reddit", url = "https://www.reddit.com", badge = "R", iconColorHex = "#F97316"),
            BrowserShortcut(title = "Wikipedia", url = "https://www.wikipedia.org", badge = "W", iconColorHex = "#64748B"),
            BrowserShortcut(title = "GitHub", url = "https://github.com", badge = "GH", iconColorHex = "#8B5CF6"),
            BrowserShortcut(title = "Archive.org", url = "https://archive.org", badge = "IA", iconColorHex = "#0284C7"),
            BrowserShortcut(title = "1337x", url = "https://1337x.to", badge = "13", iconColorHex = "#DC2626"),
            BrowserShortcut(title = "YTS", url = "https://yts.mx", badge = "YTS", iconColorHex = "#16A34A")
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val SEARCH_ENGINE_KEY = stringPreferencesKey("browser_search_engine")
    private val DNS_PROVIDER_KEY = stringPreferencesKey("browser_dns_provider")
    private val CUSTOM_DNS_HOST_KEY = stringPreferencesKey("browser_custom_dns_host")
    private val CUSTOM_DNS_IP_KEY = stringPreferencesKey("browser_custom_dns_ip")
    private val HTTPS_ONLY_KEY = booleanPreferencesKey("browser_https_only")
    private val DO_NOT_TRACK_KEY = booleanPreferencesKey("browser_do_not_track")
    private val THIRD_PARTY_COOKIES_KEY = booleanPreferencesKey("browser_third_party_cookies")
    private val FORCE_DARK_KEY = booleanPreferencesKey("browser_force_dark")
    private val DEFAULT_DESKTOP_KEY = booleanPreferencesKey("browser_default_desktop")
    private val TEXT_ZOOM_KEY = intPreferencesKey("browser_text_zoom")
    private val JAVASCRIPT_KEY = booleanPreferencesKey("browser_javascript")
    private val BLOCK_POPUPS_KEY = booleanPreferencesKey("browser_block_popups")
    private val SHORTCUTS_KEY = stringPreferencesKey("browser_shortcuts_json")

    val settings: Flow<BrowserSettings> = context.dataStore.data.map { prefs ->
        BrowserSettings(
            searchEngine = prefs[SEARCH_ENGINE_KEY] ?: "Google",
            dnsProvider = prefs[DNS_PROVIDER_KEY] ?: "Cloudflare",
            customDnsHost = prefs[CUSTOM_DNS_HOST_KEY] ?: "",
            customDnsIp = prefs[CUSTOM_DNS_IP_KEY] ?: "",
            httpsOnly = prefs[HTTPS_ONLY_KEY] ?: true,
            doNotTrack = prefs[DO_NOT_TRACK_KEY] ?: true,
            acceptThirdPartyCookies = prefs[THIRD_PARTY_COOKIES_KEY] ?: true,
            forceDarkMode = prefs[FORCE_DARK_KEY] ?: false,
            defaultDesktopMode = prefs[DEFAULT_DESKTOP_KEY] ?: false,
            textZoom = prefs[TEXT_ZOOM_KEY] ?: 100,
            javaScriptEnabled = prefs[JAVASCRIPT_KEY] ?: true,
            blockPopups = prefs[BLOCK_POPUPS_KEY] ?: true
        )
    }

    val shortcuts: Flow<List<BrowserShortcut>> = context.dataStore.data.map { prefs ->
        val raw = prefs[SHORTCUTS_KEY]
        if (raw.isNullOrBlank()) {
            DEFAULT_SHORTCUTS
        } else {
            try {
                json.decodeFromString<List<BrowserShortcut>>(raw)
            } catch (_: Exception) {
                DEFAULT_SHORTCUTS
            }
        }
    }

    suspend fun getSnapshot(): BrowserSettings {
        val prefs = context.dataStore.data.first()
        return BrowserSettings(
            searchEngine = prefs[SEARCH_ENGINE_KEY] ?: "Google",
            dnsProvider = prefs[DNS_PROVIDER_KEY] ?: "Cloudflare",
            customDnsHost = prefs[CUSTOM_DNS_HOST_KEY] ?: "",
            customDnsIp = prefs[CUSTOM_DNS_IP_KEY] ?: "",
            httpsOnly = prefs[HTTPS_ONLY_KEY] ?: true,
            doNotTrack = prefs[DO_NOT_TRACK_KEY] ?: true,
            acceptThirdPartyCookies = prefs[THIRD_PARTY_COOKIES_KEY] ?: true,
            forceDarkMode = prefs[FORCE_DARK_KEY] ?: false,
            defaultDesktopMode = prefs[DEFAULT_DESKTOP_KEY] ?: false,
            textZoom = prefs[TEXT_ZOOM_KEY] ?: 100,
            javaScriptEnabled = prefs[JAVASCRIPT_KEY] ?: true,
            blockPopups = prefs[BLOCK_POPUPS_KEY] ?: true
        )
    }

    suspend fun updateSettings(newSettings: BrowserSettings) {
        context.dataStore.edit { prefs ->
            prefs[SEARCH_ENGINE_KEY] = newSettings.searchEngine
            prefs[DNS_PROVIDER_KEY] = newSettings.dnsProvider
            prefs[CUSTOM_DNS_HOST_KEY] = newSettings.customDnsHost
            prefs[CUSTOM_DNS_IP_KEY] = newSettings.customDnsIp
            prefs[HTTPS_ONLY_KEY] = newSettings.httpsOnly
            prefs[DO_NOT_TRACK_KEY] = newSettings.doNotTrack
            prefs[THIRD_PARTY_COOKIES_KEY] = newSettings.acceptThirdPartyCookies
            prefs[FORCE_DARK_KEY] = newSettings.forceDarkMode
            prefs[DEFAULT_DESKTOP_KEY] = newSettings.defaultDesktopMode
            prefs[TEXT_ZOOM_KEY] = newSettings.textZoom
            prefs[JAVASCRIPT_KEY] = newSettings.javaScriptEnabled
            prefs[BLOCK_POPUPS_KEY] = newSettings.blockPopups
        }
    }

    suspend fun addShortcut(shortcut: BrowserShortcut) {
        val current = shortcuts.first().toMutableList()
        // Prevent duplicate URLs
        current.removeAll { it.url.equals(shortcut.url, ignoreCase = true) }
        current.add(0, shortcut)
        context.dataStore.edit { prefs ->
            prefs[SHORTCUTS_KEY] = json.encodeToString(current)
        }
    }

    suspend fun addShortcut(title: String, url: String) {
        val badge = title.trim().take(2).uppercase().ifEmpty { "W" }
        addShortcut(BrowserShortcut(title = title, url = url, badge = badge))
    }

    suspend fun removeShortcut(id: String) {
        val current = shortcuts.first().toMutableList()
        current.removeAll { it.id == id }
        context.dataStore.edit { prefs ->
            prefs[SHORTCUTS_KEY] = json.encodeToString(current)
        }
    }

    suspend fun resetShortcutsToDefault() {
        context.dataStore.edit { prefs ->
            prefs[SHORTCUTS_KEY] = json.encodeToString(DEFAULT_SHORTCUTS)
        }
    }
}

fun BrowserSettings.getSearchUrl(query: String): String {
    val encoded = java.net.URLEncoder.encode(query, "UTF-8")
    return when (searchEngine) {
        "DuckDuckGo" -> "https://duckduckgo.com/?q=$encoded"
        "Brave" -> "https://search.brave.com/search?q=$encoded"
        "Bing" -> "https://www.bing.com/search?q=$encoded"
        "Yandex" -> "https://yandex.com/search/?text=$encoded"
        else -> "https://www.google.com/search?q=$encoded"
    }
}

fun BrowserSettings.getHomeUrl(): String {
    return "speeddown://home"
}
