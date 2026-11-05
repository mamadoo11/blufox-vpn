package com.blufox.vpn

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class CloudFoxPublicConfig(
    val id: String,
    val name: String,
    val protocol: String,
    val shareLink: String,
    val sourceGroup: Int
)

object CloudFoxPublicRepository {
    private const val MANIFEST = "https://raw.githubusercontent.com/mamadoo11/cloudfox-vpn/main/free-servers-manifest.json"
    private const val MAX_BYTES = 3 * 1024 * 1024

    fun fetchGroups(): List<List<CloudFoxPublicConfig>> {
        val manifestText = getText(MANIFEST)
        val lists = JSONObject(manifestText).optJSONArray("lists")
            ?: throw IllegalStateException("فهرست منابع عمومی CloudFox معتبر نیست.")
        require(lists.length() > 0) { "هیچ منبع عمومی در CloudFox معرفی نشده است." }

        return (0 until lists.length()).map { index ->
            val source = lists.optJSONObject(index)?.optString("source").orEmpty()
            require(source.startsWith("https://raw.githubusercontent.com/")) {
                "نشانی منبع عمومی معتبر نیست."
            }
            val body = getText(source)
            val decoded = decodePayload(body)
            val entries = decoded.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("//") }
                .mapNotNull { raw ->
                    val protocol = detectProtocol(raw) ?: return@mapNotNull null
                    val name = raw.substringAfterLast('#', "").takeIf { it.isNotBlank() }
                        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                        ?: "$protocol • ${index + 1}"
                    CloudFoxPublicConfig(
                        id = raw.hashCode().toUInt().toString(16),
                        name = name,
                        protocol = protocol,
                        shareLink = raw,
                        sourceGroup = index + 1
                    )
                }.toList()
            entries.distinctBy { it.shareLink }
        }
    }

    private fun detectProtocol(link: String): String? = when {
        link.startsWith("vless://", true) -> "VLESS"
        link.startsWith("vmess://", true) -> "VMess"
        link.startsWith("ss://", true) -> "Shadowsocks"
        link.startsWith("trojan://", true) -> "Trojan"
        link.startsWith("ssconf://", true) -> "Outline"
        else -> null
    }

    private fun decodePayload(raw: String): String {
        val value = raw.trim().removePrefix("\uFEFF")
        if (value.startsWith("vless://", true) || value.startsWith("vmess://", true) ||
            value.startsWith("ss://", true) || value.startsWith("trojan://", true) ||
            value.startsWith("ssconf://", true)) return value

        val compact = value.replace(Regex("\\s+"), "")
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        val bytes = runCatching {
            Base64.decode(padded, Base64.DEFAULT)
        }.recoverCatching {
            Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
        }.getOrNull() ?: return value
        val decoded = String(bytes, StandardCharsets.UTF_8).trim()
        return if (decoded.contains("://")) decoded else value
    }

    private fun getText(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "BluFoxVPN/0.4 Android")
        }
        try {
            val status = connection.responseCode
            require(status in 200..299) { "دریافت فهرست عمومی ناموفق بود (HTTP $status)." }
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "حجم فهرست عمومی بیش از حد مجاز است." }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString("UTF-8")
        } finally {
            connection.disconnect()
        }
    }
}