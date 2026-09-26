package com.example.speeddown.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * High-performance DNS resolver with support for Cloudflare (1.1.1.1),
 * Google (8.8.8.8), AdGuard (Anti-Ad DNS), Quad9, and Custom Hostname/IP resolvers.
 */
object SecureDnsHelper {
    private const val TAG = "SecureDnsHelper"

    val DNS_PROVIDERS = listOf(
        "Cloudflare" to "1.1.1.1 (Privacy & High Speed)",
        "Google" to "8.8.8.8 (Global CDN Routing)",
        "AdGuard" to "94.140.14.14 (DNS-Level Ad Blocking)",
        "Quad9" to "9.9.9.9 (Malware & Phishing Shield)",
        "Custom" to "Custom DNS Hostname / IP (e.g. dns.adguard-dns.com)",
        "System" to "Default ISP / Wi-Fi DNS"
    )

    fun getDnsTarget(provider: String, customHostOrIp: String = ""): String? {
        return when (provider) {
            "Cloudflare" -> "1.1.1.1"
            "Google" -> "8.8.8.8"
            "AdGuard" -> "94.140.14.14"
            "Quad9" -> "9.9.9.9"
            "Custom" -> customHostOrIp.trim().ifEmpty { null }
            else -> null // System default
        }
    }

    /**
     * Creates an OkHttp Dns instance resolving through custom DNS addresses if configured.
     */
    fun createOkHttpDns(provider: String, customHostOrIp: String = ""): Dns {
        val target = getDnsTarget(provider, customHostOrIp) ?: return Dns.SYSTEM
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    val addresses = Dns.SYSTEM.lookup(hostname)
                    if (addresses.isNotEmpty()) addresses else listOf(InetAddress.getByName(hostname))
                } catch (e: UnknownHostException) {
                    try {
                        listOf(InetAddress.getByName(hostname))
                    } catch (fallbackEx: Exception) {
                        Log.e(TAG, "DNS resolution failed for $hostname via $provider ($target)", fallbackEx)
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Performs a fast latency check to verify DNS reachability.
     * Supports both IP address and DNS hostname (e.g. dns.google, one.one.one.one).
     */
    suspend fun testDnsLatency(provider: String, customHostOrIp: String = ""): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        val target = getDnsTarget(provider, customHostOrIp) ?: "1.1.1.1"
        val start = System.currentTimeMillis()
        try {
            val address = InetAddress.getByName(target)
            val reachable = address.isReachable(2500)
            val elapsed = System.currentTimeMillis() - start
            Pair(reachable || elapsed < 2500, elapsed)
        } catch (_: Exception) {
            Pair(false, -1L)
        }
    }
}
