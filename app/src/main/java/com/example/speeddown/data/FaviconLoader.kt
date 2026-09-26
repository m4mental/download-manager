package com.example.speeddown.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object FaviconLoader {
    private val memoryCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(1024)) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun extractDomain(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: url
            host.removePrefix("www.").lowercase().trim()
        } catch (_: Exception) {
            url.substringBefore("/").removePrefix("www.").lowercase().trim()
        }
    }

    suspend fun getFavicon(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        val domain = extractDomain(url)
        if (domain.isBlank() || domain.startsWith("speeddown") || domain.startsWith("about:")) {
            return@withContext null
        }

        // 1. Check in-memory cache
        memoryCache.get(domain)?.let { return@withContext it }

        // 2. Check disk cache
        val diskCacheDir = File(context.cacheDir, "favicons").apply { if (!exists()) mkdirs() }
        val diskFile = File(diskCacheDir, "${domain.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.png")
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (bmp != null) {
                    memoryCache.put(domain, bmp)
                    return@withContext bmp
                }
            } catch (_: Exception) {}
        }

        // 3. Candidate endpoints to fetch high-resolution favicon
        val candidateUrls = listOf(
            "https://www.google.com/s2/favicons?domain=$domain&sz=128",
            "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://$domain&size=128",
            "https://icons.duckduckgo.com/ip3/$domain.ico",
            "https://$domain/favicon.ico"
        )

        for (favUrl in candidateUrls) {
            try {
                val request = Request.Builder()
                    .url(favUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null && bmp.width > 8 && bmp.height > 8) {
                            memoryCache.put(domain, bmp)
                            try {
                                FileOutputStream(diskFile).use { out ->
                                    bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                                }
                            } catch (_: Exception) {}
                            return@withContext bmp
                        }
                    }
                }
            } catch (_: Exception) {
                // Continue to next candidate
            }
        }

        null
    }
}
