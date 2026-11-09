package com.blufox.vpn

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class VpnGateServer(
    val hostName: String,
    val ip: String,
    val country: String,
    val countryCode: String,
    val pingMs: Long,
    val speedBps: Long,
    val sessions: Int,
    val configBase64: String,
    val sourceLabel: String,
    val fetchedAt: Long
) {
    val displayName: String get() = "$country • $ip"
    val speedLabel: String get() = when {
        speedBps >= 1_000_000_000 -> String.format(Locale.US, "%.2f Gbps", speedBps / 1_000_000_000.0)
        speedBps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", speedBps / 1_000_000.0)
        speedBps >= 1_000 -> String.format(Locale.US, "%.0f Kbps", speedBps / 1_000.0)
        else -> "نامشخص"
    }
}

object VpnGateRepository {
    private data class Source(val label: String, val url: String)

    // The official API is preferred. Public mirrors are fallbacks only, not independent server operators.
    private val sources = listOf(
        Source("VPN Gate رسمی", "https://www.vpngate.net/api/iphone/"),
        Source("آینه عمومی VPN Gate", "https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@latest/data/vpngate.csv"),
        Source("GitHub Raw mirror", "https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/main/data/vpngate.csv")
    )

    suspend fun fetchServers(limit: Int = 40): List<VpnGateServer> = withContext(Dispatchers.IO) {
        require(limit in 1..100) { "تعداد سرورها نامعتبر است." }
        val failures = mutableListOf<String>()

        for (source in sources) {
            try {
                val found = downloadAndParse(source, limit)
                if (found.isNotEmpty()) return@withContext found
                failures += "${source.label}: فهرست خالی یا فاقد کانفیگ معتبر بود"
            } catch (error: Exception) {
                failures += "${source.label}: ${error.message ?: "خطای دریافت"}"
            }
        }

        throw IllegalStateException(
            "از هیچ‌کدام از منابع VPN Gate فهرست قابل استفاده دریافت نشد. اتصال اینترنت یا دسترسی به منابع را بررسی کن. " +
                failures.joinToString(" | ").take(700)
        )
    }

    private fun downloadAndParse(source: Source, limit: Int): List<VpnGateServer> {
        val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BluFoxVPN/0.4 Android")
            setRequestProperty("Accept", "text/csv,text/plain,*/*")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code" }
            val fetchedAt = System.currentTimeMillis()
            val rows = mutableListOf<VpnGateServer>()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var line = reader.readLine()
                var scanned = 0
                while (line != null && rows.size < 250 && scanned < 1500) {
                    scanned++
                    if (line.isNotBlank() && !line.startsWith("*") && !line.startsWith("#")) {
                        val cells = parseCsvLine(line)
                        if (cells.size >= 15 && !cells[0].equals("HostName", true)) {
                            val host = cells[0].trim()
                            val ip = cells[1].trim()
                            val country = cells[5].trim()
                            val code2 = cells[6].trim()
                            val encoded = cells[14].trim()
                            if (host.isNotBlank() && isIpAddress(ip) && country.isNotBlank() &&
                                hasUsableConfig(encoded)
                            ) {
                                rows += VpnGateServer(
                                    hostName = host,
                                    ip = ip,
                                    country = country,
                                    countryCode = code2,
                                    pingMs = cells[3].toLongOrNull() ?: Long.MAX_VALUE,
                                    speedBps = cells[4].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                                    sessions = cells[7].toIntOrNull()?.coerceAtLeast(0) ?: 0,
                                    configBase64 = encoded,
                                    sourceLabel = source.label,
                                    fetchedAt = fetchedAt
                                )
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
            return rows.distinctBy { it.ip + ":" + it.hostName }
                .sortedWith(compareBy<VpnGateServer> { it.pingMs }.thenByDescending { it.speedBps })
                .take(limit)
        } finally {
            connection.disconnect()
        }
    }

    private fun hasUsableConfig(encoded: String): Boolean {
        if (encoded.isBlank() || encoded.length < 100) return false
        return runCatching {
            val profile = decodeConfig(encoded)
            val hasRemote = profile.lineSequence().any { it.trim().startsWith("remote ", true) }
            val hasCa = profile.contains("<ca>", true) && profile.contains("</ca>", true)
            hasRemote && hasCa
        }.getOrDefault(false)
    }

    private fun isIpAddress(value: String): Boolean {
        if (value.isBlank() || value.length > 45) return false
        return value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) ||
            value.contains(":") && value.matches(Regex("[0-9a-fA-F:]+"))
    }

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    values.add(cell.toString())
                    cell.setLength(0)
                }
                else -> cell.append(char)
            }
            index++
        }
        values.add(cell.toString())
        return values
    }

    fun decodeConfig(encoded: String): String {
        val normalized = encoded.trim().replace("\\s".toRegex(), "")
        val bytes = try {
            Base64.decode(normalized, Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("فایل تنظیمات سرور خراب یا ناقص است.")
        }
        return bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }
}
