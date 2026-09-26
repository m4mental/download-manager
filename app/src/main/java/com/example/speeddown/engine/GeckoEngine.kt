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

    @Synchronized
    fun getOrCreateRuntime(context: Context): GeckoRuntime {
        if (runtime == null) {
            val contentBlocking = ContentBlocking.Settings.Builder()
                .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY)
                .build()

            val settings = GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(true)
                .debugLogging(true)
                .consoleOutput(true)
                .contentBlocking(contentBlocking)
                .build()

            runtime = GeckoRuntime.create(context.applicationContext, settings)
            installBuiltInExtensions(context.applicationContext)
        }
        return runtime!!
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
                            isUBlockActive = true
                            extensionId = ext.id
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
                        Log.i(TAG, ">>> uBlock Origin INSTALLED & ACTIVE: ${ext?.id}")
                        isUBlockActive = true
                        extensionId = ext?.id ?: "uBlock0@raymondhill.net"
                    },
                    { err ->
                        Log.e(TAG, ">>> ensureBuiltIn failed: ${err?.message}, trying installBuiltIn...", err)
                        rt.webExtensionController
                            .installBuiltIn("resource://android/assets/extensions/ublock/")
                            .accept(
                                { ext2 ->
                                    Log.i(TAG, ">>> uBlock Origin installBuiltIn SUCCESS: ${ext2?.id}")
                                    isUBlockActive = true
                                    extensionId = ext2?.id ?: "uBlock0@raymondhill.net"
                                },
                                { err2 ->
                                    Log.e(TAG, ">>> installBuiltIn also failed: ${err2?.message}", err2)
                                }
                            )
                    }
                )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during extension install", e)
        }
    }
}
