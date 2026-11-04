package com.blufox.vpn

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Resolves Outline dynamic access keys (ssconf://) hosted over HTTPS.
 * Supports the documented raw ss:// response and the simple JSON format.
 */
object DynamicAccessKeyResolver {

    fun resolve(rawKey: String): ShadowsocksProfile {
        val key = rawKey.trim()
        require(key.startsWith("ssconf://", ignoreCase = true)) {
            "لینک باید با ssconf:// شروع شود."
        }

        val withoutScheme = key.substringAfter("://")
        val fragment = withoutScheme.substringAfter("#", "")
        val fetchUrl = "https://" + withoutScheme.substringBefore("#")
        val parsedUrl = URL(fetchUrl)
        require(parsedUrl.protocol == "https") {
            "کلید پویا فقط از طریق HTTPS دریافت می‌شود."
        }

        val connection = (parsedUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }

        val body = try {
            val status = connection.responseCode
            require(status in 200..299) {
                "دریافت کلید پویا ناموفق بود (HTTP $status)."
            }
            val stream = connection.inputStream
            val bytes = stream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 1_048_576) { "حجم تنظیمات سرور بیش از حد مجاز است." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            String(bytes, StandardCharsets.UTF_8).trim()
        } finally {
            connection.disconnect()
        }

        require(body.isNotBlank()) { "پاسخ کلید پویا خالی است." }
        val name = Uri.decode(fragment).takeIf { it.isNotBlank() }

        if (body.startsWith("ss://", ignoreCase = true)) {
            val profile = ShadowsocksConfigBuilder.fromShareLink(body)
            return profile.copy(name = name ?: profile.name)
        }

        val json = runCatching { JSONObject(body) }.getOrElse {
            throw IllegalArgumentException(
                "فرمت پاسخ کلید پویا پشتیبانی نمی‌شود؛ فعلاً پاسخ ss:// یا JSON ساده قابل قبول است."
            )
        }

        val host = json.optString("server").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("فیلد server در تنظیمات کلید پویا وجود ندارد.")
        val port = json.optInt("server_port", -1)
        require(port in 1..65535) { "پورت سرور در کلید پویا معتبر نیست." }
        val password = json.optString("password").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("فیلد password در تنظیمات کلید پویا وجود ندارد.")
        val method = json.optString("method").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("فیلد method در تنظیمات کلید پویا وجود ندارد.")

        val userInfo = Base64.encodeToString(
            "$method:$password".toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val safeHost = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        val staticLink = "ss://$userInfo@$safeHost:$port"
        val profile = ShadowsocksConfigBuilder.fromShareLink(staticLink)
        return profile.copy(name = name ?: host)
    }
}
