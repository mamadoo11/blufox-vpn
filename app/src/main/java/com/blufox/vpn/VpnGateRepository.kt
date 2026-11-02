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
    val configBase64: String
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
    private const val API = "https://www.vpngate.net/api/iphone/"

    suspend fun fetchServers(limit: Int = 40): List<VpnGateServer> = withContext(Dispatchers.IO) {
        val connection = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 18_000
            readTimeout = 25_000
            useCaches = false
            setRequestProperty("User-Agent", "BluFoxVPN/0.3 Android")
            setRequestProperty("Accept", "text/csv,*/*")
        }
        try {
            val rows = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = mutableListOf<VpnGateServer>()
                var line = reader.readLine()
                while (line != null && result.size < 250) {
                    if (line.isNotBlank() && !line.startsWith("*") && !line.startsWith("#")) {
                        val cells = parseCsvLine(line)
                        if (cells.size >= 15) {
                            val encoded = cells[14].trim().trim('"')
                            val ip = cells[1].trim()
                            val country = cells[5].trim()
                            val code = cells[6].trim()
                            val ping = cells[3].toLongOrNull() ?: Long.MAX_VALUE
                            val speed = cells[4].toLongOrNull() ?: 0L
                            val sessions = cells[7].toIntOrNull() ?: 0
                            if (ip.isNotBlank() && country.isNotBlank() && encoded.isNotBlank()) {
                                result.add(
                                    VpnGateServer(
                                        hostName = cells[0].trim(),
                                        ip = ip,
                                        country = country,
                                        countryCode = code,
                                        pingMs = ping,
                                        speedBps = speed,
                                        sessions = sessions,
                                        configBase64 = encoded
                                    )
                                )
                            }
                        }
                    }
                    line = reader.readLine()
                }
                result
            }
            rows.sortedWith(compareBy<VpnGateServer> { it.pingMs }.thenByDescending { it.speedBps })
                .take(limit)
        } finally {
            connection.disconnect()
        }
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
