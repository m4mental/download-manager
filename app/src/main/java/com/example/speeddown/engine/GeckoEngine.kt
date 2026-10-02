package com.example.speeddown.engine

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

object GeckoEngine {
    private const val TAG = "UBLOCK_STATUS"
    private var runtime: GeckoRuntime? = null

    var isUBlockActive by mutableStateOf(false)
        private set

    var extensionId: String? = null
        private set

    var installErrorMessage by mutableStateOf<String?>(null)
        private set

    @Synchronized
    fun getOrCreateRuntime(context: Context): GeckoRuntime {
        if (runtime == null) {
            val isDebuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

            val browserSettings = runCatching {
                kotlinx.coroutines.runBlocking {
                    com.example.speeddown.data.BrowserSettingsStore.getInstance(context).getSnapshot()
                }
            }.getOrNull()

            val cookieBehavior = if (browserSettings?.acceptThirdPartyCookies == false) {
                ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
            } else {
                ContentBlocking.CookieBehavior.ACCEPT_ALL
            }

            val contentBlocking = ContentBlocking.Settings.Builder()
                .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                .cookieBehavior(cookieBehavior)
                .build()

            val settingsBuilder = GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(isDebuggable)
                .debugLogging(isDebuggable)
                .consoleOutput(isDebuggable)
                .contentBlocking(contentBlocking)

            if (browserSettings?.httpsOnly == true) {
                settingsBuilder.allowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY)
            } else {
                settingsBuilder.allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
            }

            if (browserSettings?.dnsProvider?.equals("System", ignoreCase = true) == true) {
                settingsBuilder.trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
            } else {
                val dohUri = when (browserSettings?.dnsProvider?.lowercase()) {
                    "cloudflare" -> "https://cloudflare-dns.com/dns-query"
                    "google" -> "https://dns.google/dns-query"
                    "adguard" -> "https://dns.adguard-dns.com/dns-query"
                    "quad9" -> "https://dns.quad9.net/dns-query"
                    "custom" -> browserSettings.customDnsHost.let {
                        if (it.startsWith("http")) it else "https://$it/dns-query"
                    }
                    else -> "https://cloudflare-dns.com/dns-query"
                }
                settingsBuilder.trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_ONLY)
                settingsBuilder.trustedRecursiveResolverUri(dohUri)
            }

            runtime = GeckoRuntime.create(context.applicationContext, settingsBuilder.build())
            installBuiltInExtensions(context.applicationContext)
        }
        return runtime!!
    }

    private var desiredUBlockEnabled: Boolean = true
    private var operationSequence: Long = 0L

    fun updateRuntimeSettings(settings: com.example.speeddown.data.BrowserSettings) {
        val rt = runtime ?: return
        try {
            val cookieBehavior = if (!settings.acceptThirdPartyCookies) {
                ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
            } else {
                ContentBlocking.CookieBehavior.ACCEPT_ALL
            }
            rt.settings.contentBlocking.setCookieBehavior(cookieBehavior)

            val insecurePolicy = if (settings.httpsOnly) {
                GeckoRuntimeSettings.HTTPS_ONLY
            } else {
                GeckoRuntimeSettings.ALLOW_ALL
            }
            rt.settings.setAllowInsecureConnections(insecurePolicy)

            if (settings.dnsProvider.equals("System", ignoreCase = true)) {
                rt.settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
            } else {
                val dohUri = when (settings.dnsProvider.lowercase()) {
                    "cloudflare" -> "https://cloudflare-dns.com/dns-query"
                    "google" -> "https://dns.google/dns-query"
                    "adguard" -> "https://dns.adguard-dns.com/dns-query"
                    "quad9" -> "https://dns.quad9.net/dns-query"
                    "custom" -> settings.customDnsHost.let {
                        if (it.startsWith("http")) it else "https://$it/dns-query"
                    }
                    else -> "https://cloudflare-dns.com/dns-query"
                }
                rt.settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_ONLY)
                rt.settings.setTrustedRecursiveResolverUri(dohUri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Gecko runtime settings", e)
        }
    }

    @Synchronized
    fun setUBlockEnabled(enabled: Boolean, onComplete: ((Boolean) -> Unit)? = null) {
        desiredUBlockEnabled = enabled
        AdBlockEngine.isEnabled = enabled
        val rt = runtime
        if (rt == null) {
            isUBlockActive = false
            onComplete?.invoke(false)
            return
        }

        val opId = synchronized(this) { ++operationSequence }
        rt.webExtensionController.list().accept(
            { list ->
                val ext = list?.find { it.id == "uBlock0@raymondhill.net" }
                if (ext != null) {
                    val action = if (enabled) {
                        rt.webExtensionController.enable(ext, org.mozilla.geckoview.WebExtensionController.EnableSource.USER)
                    } else {
                        rt.webExtensionController.disable(ext, org.mozilla.geckoview.WebExtensionController.EnableSource.USER)
                    }
                    action.accept(
                        { _ ->
                            synchronized(GeckoEngine) {
                                if (opId == operationSequence) {
                                    isUBlockActive = enabled
                                    installErrorMessage = null
                                }
                            }
                            onComplete?.invoke(true)
                        },
                        { err ->
                            Log.e(TAG, "Failed to set uBlock state to $enabled", err)
                            synchronized(GeckoEngine) {
                                if (opId == operationSequence) {
                                    installErrorMessage = "Failed to toggle uBlock: ${err?.message}"
                                }
                            }
                            onComplete?.invoke(false)
                        }
                    )
                } else {
                    synchronized(GeckoEngine) {
                        if (opId == operationSequence) {
                            isUBlockActive = false
                            if (installErrorMessage == null) {
                                installErrorMessage = "uBlock Origin extension not found in extension list"
                            }
                        }
                    }
                    onComplete?.invoke(false)
                }
            },
            { err ->
                Log.e(TAG, "Failed to list extensions", err)
                synchronized(GeckoEngine) {
                    if (opId == operationSequence) {
                        installErrorMessage = "Failed to list extensions: ${err?.message}"
                    }
                }
                onComplete?.invoke(false)
            }
        )
    }

    private fun applyInstalledExtensionState(
        rt: GeckoRuntime,
        ext: org.mozilla.geckoview.WebExtension,
        source: String
    ) {
        extensionId = ext.id
        val targetState = synchronized(this) { desiredUBlockEnabled }
        val opId = synchronized(this) { ++operationSequence }
        val action = if (targetState) {
            rt.webExtensionController.enable(ext, org.mozilla.geckoview.WebExtensionController.EnableSource.USER)
        } else {
            rt.webExtensionController.disable(ext, org.mozilla.geckoview.WebExtensionController.EnableSource.USER)
        }
        action.accept(
            { _ ->
                synchronized(GeckoEngine) {
                    if (opId == operationSequence) {
                        isUBlockActive = targetState
                        installErrorMessage = null
                    }
                }
            },
            { err ->
                Log.e(TAG, "Failed to apply protection state ($targetState) after $source", err)
                synchronized(GeckoEngine) {
                    if (opId == operationSequence) {
                        installErrorMessage = "uBlock state update failed: ${err?.message}"
                    }
                }
            }
        )
    }

    private fun installBuiltInExtensions(context: Context) {
        val rt = runtime ?: return
        try {
            Log.d(TAG, "Checking installed WebExtensions...")
            rt.webExtensionController.list().accept(
                { list ->
                    Log.d(TAG, "Installed extensions count: ${list?.size ?: 0}")
                    list?.forEach { ext ->
                        Log.d(TAG, "Installed extension found: ${ext.id}")
                        if (ext.id == "uBlock0@raymondhill.net") {
                            applyInstalledExtensionState(rt, ext, "list")
                        }
                    }
                },
                { err ->
                    Log.e(TAG, "Error listing extensions: ${err?.message}", err)
                }
            )

            Log.d(TAG, "Ensuring built-in uBlock Origin extension...")
            rt.webExtensionController
                .ensureBuiltIn("resource://android/assets/extensions/ublock/", "uBlock0@raymondhill.net")
                .accept(
                    { ext ->
                        if (ext != null) {
                            Log.i(TAG, ">>> uBlock Origin INSTALLED: ${ext.id}")
                            applyInstalledExtensionState(rt, ext, "ensureBuiltIn")
                        } else {
                            installErrorMessage = "uBlock extension is null after ensureBuiltIn"
                        }
                    },
                    { err ->
                        Log.e(TAG, ">>> ensureBuiltIn failed: ${err?.message}, trying installBuiltIn...", err)
                        rt.webExtensionController
                            .installBuiltIn("resource://android/assets/extensions/ublock/")
                            .accept(
                                { ext2 ->
                                    if (ext2 != null) {
                                        Log.i(TAG, ">>> uBlock Origin installBuiltIn SUCCESS: ${ext2.id}")
                                        applyInstalledExtensionState(rt, ext2, "installBuiltIn")
                                    } else {
                                        installErrorMessage = "uBlock extension is null after installBuiltIn"
                                    }
                                },
                                { err2 ->
                                    Log.e(TAG, ">>> installBuiltIn also failed: ${err2?.message}", err2)
                                    installErrorMessage = "uBlock installation failed: ${err2?.message}"
                                }
                            )
                    }
                )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during extension install", e)
            installErrorMessage = "Extension error: ${e.message}"
        }
    }
}
