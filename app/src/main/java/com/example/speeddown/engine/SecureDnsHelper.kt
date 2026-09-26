package com.example.speeddown.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * High-performance true DNS resolver with official OkHttp DNS-over-HTTPS (DoH)
 * support for Cloudflare (1.1.1.1), Google (8.8.8.8), AdGuard (Anti-Ad DNS), Quad9,
 * and Custom Hostname/IP resolvers.
 *
 * Implements RFC 8484 wireformat DNS-over-HTTPS with bootstrap IPs, UDP fallback,
 * and smart caching.
 */
object SecureDnsHelper {
    private const val TAG = "SecureDnsHelper"

    val DNS_PROVIDERS = listOf(
        "Cloudflare" to "1.1.1.1 (Privacy & High Speed DoH)",
        "Google" to "8.8.8.8 (Global CDN Routing DoH)",
        "AdGuard" to "94.140.14.14 (DNS-Level Ad Blocking DoH)",
        "Quad9" to "9.9.9.9 (Malware & Phishing Shield DoH)",
        "Custom" to "Custom DNS Hostname / IP (e.g. dns.adguard-dns.com)",
        "System" to "Default ISP / Wi-Fi DNS"
    )

    private val dohCache = ConcurrentHashMap<String, DnsOverHttps>()
    private val localDnsCache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes TTL

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

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
     * Builds or retrieves an official OkHttp DnsOverHttps instance for the provider.
     */
    fun getDnsOverHttpsInstance(provider: String, customHostOrIp: String = ""): DnsOverHttps? {
        val cacheKey = "$provider:$customHostOrIp"
        dohCache[cacheKey]?.let { return it }

        val (dohUrlStr, bootstrapIps) = when (provider) {
            "Cloudflare" -> "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1")
            "Google" -> "https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4")
            "AdGuard" -> "https://dns.adguard-dns.com/dns-query" to listOf("94.140.14.14", "94.140.15.15")
            "Quad9" -> "https://dns.quad9.net/dns-query" to listOf("9.9.9.9", "149.112.112.112")
            "Custom" -> {
                val input = customHostOrIp.trim()
                if (input.startsWith("https://")) {
                    input to emptyList()
                } else if (input.isNotEmpty()) {
                    "https://$input/dns-query" to (if (isIpAddress(input)) listOf(input) else emptyList())
                } else {
                    return null
                }
            }
            else -> return null
        }

        return try {
            val builder = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(dohUrlStr.toHttpUrl())
                .includeIPv6(true)
                .post(true)

            if (bootstrapIps.isNotEmpty()) {
                val hosts = bootstrapIps.mapNotNull {
                    try { InetAddress.getByName(it) } catch (_: Exception) { null }
                }
                if (hosts.isNotEmpty()) {
                    builder.bootstrapDnsHosts(hosts)
                }
            }

            val doh = builder.build()
            dohCache[cacheKey] = doh
            doh
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create DnsOverHttps for $provider: ${e.message}")
            null
        }
    }

    /**
     * Creates an OkHttp Dns instance that truly queries the selected DNS server
     * via official DnsOverHttps, with direct UDP socket query fallback and caching.
     */
    fun createOkHttpDns(provider: String, customHostOrIp: String = ""): Dns {
        if (provider == "System" && customHostOrIp.isBlank()) {
            return Dns.SYSTEM
        }

        val target = getDnsTarget(provider, customHostOrIp) ?: return Dns.SYSTEM
        val dohResolver = getDnsOverHttpsInstance(provider, customHostOrIp)

        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                // If hostname is already an IP address
                if (isIpAddress(hostname)) {
                    return listOf(InetAddress.getByName(hostname))
                }

                val cacheKey = "$provider:$hostname"
                val cached = localDnsCache[cacheKey]
                if (cached != null && (System.currentTimeMillis() - cached.first) < CACHE_TTL_MS) {
                    return cached.second
                }

                // 1. Primary: Official OkHttp DNS-over-HTTPS (DoH)
                if (dohResolver != null) {
                    try {
                        val addresses = dohResolver.lookup(hostname)
                        if (addresses.isNotEmpty()) {
                            localDnsCache[cacheKey] = System.currentTimeMillis() to addresses
                            return addresses
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "DoH lookup failed for $hostname via $provider, attempting UDP fallback: ${e.message}")
                    }
                }

                // 2. Secondary: Direct UDP Socket query to target DNS server (port 53)
                try {
                    val resolved = queryUdpDns(hostname, target)
                    if (resolved.isNotEmpty()) {
                        localDnsCache[cacheKey] = System.currentTimeMillis() to resolved
                        return resolved
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "UDP DNS query failed for $hostname via $target: ${e.message}")
                }

                // 3. Fallback: System DNS
                return try {
                    val fallback = Dns.SYSTEM.lookup(hostname)
                    if (fallback.isNotEmpty()) {
                        localDnsCache[cacheKey] = System.currentTimeMillis() to fallback
                    }
                    fallback
                } catch (e: UnknownHostException) {
                    throw e
                }
            }
        }
    }

    private fun isIpAddress(host: String): Boolean {
        return host.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}\$")) || host.contains(":")
    }

    /**
     * Sends a raw DNS A-record query over UDP to the specified target DNS server.
     */
    private fun queryUdpDns(hostname: String, dnsServerIp: String): List<InetAddress> {
        val resolvedDnsIp = try {
            if (isIpAddress(dnsServerIp)) dnsServerIp
            else Dns.SYSTEM.lookup(dnsServerIp).firstOrNull()?.hostAddress ?: dnsServerIp
        } catch (_: Exception) {
            dnsServerIp
        }

        val socket = DatagramSocket()
        socket.soTimeout = 2500 // 2.5s timeout

        return try {
            val txId = Random.nextInt(1, 65535).toShort()
            val queryBytes = buildDnsQuery(hostname, txId)
            val address = InetAddress.getByName(resolvedDnsIp)
            val packet = DatagramPacket(queryBytes, queryBytes.size, address, 53)
            socket.send(packet)

            val buffer = ByteArray(1024)
            val responsePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(responsePacket)

            parseDnsResponse(responsePacket.data, responsePacket.length, hostname)
        } finally {
            socket.close()
        }
    }

    private fun buildDnsQuery(hostname: String, transactionId: Short): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeShort(transactionId.toInt())
        dos.writeShort(0x0100) // Standard query, recursion desired
        dos.writeShort(1)      // QDCOUNT (1 question)
        dos.writeShort(0)      // ANCOUNT
        dos.writeShort(0)      // NSCOUNT
        dos.writeShort(0)      // ARCOUNT

        // QNAME: length-prefixed labels
        val labels = hostname.split('.')
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0) // Null root label terminator

        dos.writeShort(1) // QTYPE = 1 (A record)
        dos.writeShort(1) // QCLASS = 1 (IN)
        dos.flush()
        return baos.toByteArray()
    }

    private fun parseDnsResponse(data: ByteArray, length: Int, hostname: String): List<InetAddress> {
        val dis = DataInputStream(ByteArrayInputStream(data, 0, length))
        dis.readUnsignedShort() // txId
        dis.readUnsignedShort() // flags
        val qdCount = dis.readUnsignedShort()
        val anCount = dis.readUnsignedShort()
        dis.readUnsignedShort() // nsCount
        dis.readUnsignedShort() // arCount

        if (anCount == 0) return emptyList()

        // Skip Question section
        for (q in 0 until qdCount) {
            skipDnsName(dis)
            dis.readUnsignedShort() // qtype
            dis.readUnsignedShort() // qclass
        }

        val results = mutableListOf<InetAddress>()
        // Parse Answer section
        for (a in 0 until anCount) {
            skipDnsName(dis)
            val type = dis.readUnsignedShort()
            dis.readUnsignedShort() // clazz
            dis.readInt()           // ttl
            val rdLength = dis.readUnsignedShort()

            if (type == 1 && rdLength == 4) { // IPv4 A record
                val ipBytes = ByteArray(4)
                dis.readFully(ipBytes)
                try {
                    results.add(InetAddress.getByAddress(hostname, ipBytes))
                } catch (_: Exception) {}
            } else if (type == 28 && rdLength == 16) { // IPv6 AAAA record
                val ipBytes = ByteArray(16)
                dis.readFully(ipBytes)
                try {
                    results.add(InetAddress.getByAddress(hostname, ipBytes))
                } catch (_: Exception) {}
            } else {
                dis.skipBytes(rdLength)
            }
        }
        return results
    }

    private fun skipDnsName(dis: DataInputStream) {
        while (true) {
            val len = dis.readUnsignedByte()
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                // Pointer (2 bytes total: 1 already read, read next)
                dis.readUnsignedByte()
                break
            } else {
                dis.skipBytes(len)
            }
        }
    }

    /**
     * Performs a fast latency check to verify DNS reachability.
     */
    suspend fun testDnsLatency(provider: String, customHostOrIp: String = ""): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        val target = getDnsTarget(provider, customHostOrIp) ?: "1.1.1.1"
        val start = System.currentTimeMillis()
        try {
            val address = InetAddress.getByName(target)
            val reachable = address.isReachable(2000)
            val elapsed = System.currentTimeMillis() - start
            Pair(reachable || elapsed < 2000, elapsed)
        } catch (_: Exception) {
            Pair(false, -1L)
        }
    }
}
