package com.blufox.vpn

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class FreeConfigProfile(
    val name: String,
    val protocol: String,
    val shareLink: String,
    val configJson: String,
    val source: String
)

object CloudFoxFreeConfigRepository {
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/mamadoo11/cloudfox-vpn/main/free-servers-manifest.json"
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val MAX_LIST_BYTES = 2 * 1024 * 1024
    private const val MAX_PROFILES = 100

    fun fetch(): List<FreeConfigProfile> {
        val manifestBody = requestText(MANIFEST_URL, MAX_MANIFEST_BYTES)
        val manifest = JSONObject(manifestBody)
        val lists = manifest.optJSONArray("lists") ?: JSONArray()
        val result = LinkedHashMap<String, FreeConfigProfile>()

        for (i in 0 until lists.length()) {
            val source = lists.optJSONObject(i)?.optString("source").orEmpty().trim()
            if (!isAllowedSource(source)) continue
            val body = runCatching { requestText(source, MAX_LIST_BYTES) }.getOrNull() ?: continue
            val decoded = decodeList(body)
            decoded.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
                .forEach { link ->
                    if (result.size >= MAX_PROFILES || result.containsKey(link)) return@forEach
                    val profile = runCatching { parseProfile(link, source) }.getOrNull()
                    if (profile != null) result[link] = profile
                }
            if (result.size >= MAX_PROFILES) break
        }
        return result.values.toList()
    }

    private fun isAllowedSource(raw: String): Boolean = runCatching {
        val uri = URL(raw)
        uri.protocol == "https" && uri.host.equals("raw.githubusercontent.com", ignoreCase = true)
    }.getOrDefault(false)

    private fun requestText(raw: String, maxBytes: Int): String {
        val url = URL(raw)
        require(url.protocol == "https") { "فقط منابع HTTPS پذیرفته می‌شوند." }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "BluFoxVPN/0.4 Android")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        try {
            val status = connection.responseCode
            require(status in 200..299) { "دریافت منبع ناموفق بود (HTTP $status)." }
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "حجم فهرست کانفیگ بیش از حد مجاز است." }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name()).trim()
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeList(raw: String): String {
        if (raw.contains("://")) return raw
        val compact = raw.filterNot { it.isWhitespace() }
        val bytes = runCatching { Base64.decode(compact, Base64.DEFAULT) }.getOrNull()
            ?: return raw
        val decoded = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        return if (decoded.contains("://")) decoded else raw
    }

    private fun parseProfile(link: String, source: String): FreeConfigProfile {
        val profile = when {
            link.startsWith("vless://", true) -> {
                val parsed = VlessConfigBuilder.fromShareLink(link)
                FreeConfigProfile(parsed.name, "VLESS", link, parsed.configJson, source)
            }
            link.startsWith("ss://", true) -> {
                val parsed = ShadowsocksConfigBuilder.fromShareLink(link)
                FreeConfigProfile(parsed.name, "Shadowsocks", link, parsed.configJson, source)
            }
            link.startsWith("trojan://", true) -> {
                val parsed = TrojanConfigBuilder.fromShareLink(link)
                FreeConfigProfile(parsed.name, "Trojan", link, parsed.configJson, source)
            }
            else -> throw IllegalArgumentException("پروتکل این کانفیگ فعلاً پشتیبانی نمی‌شود.")
        }
        return profile
    }
}
