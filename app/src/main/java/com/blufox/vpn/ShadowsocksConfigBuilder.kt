package com.blufox.vpn

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ShadowsocksProfile(val name: String, val configJson: String)

/**
 * Converts standard Outline/SS share links into a sing-box configuration.
 * Supports SIP002 links and legacy base64-encoded links.
 * Plugin-based links are rejected explicitly rather than silently misconfigured.
 */
object ShadowsocksConfigBuilder {

    fun fromShareLink(raw: String): ShadowsocksProfile {
        val link = raw.trim()
        require(link.startsWith("ss://", ignoreCase = true)) {
            "لینک باید با ss:// شروع شود."
        }

        val withoutScheme = link.substringAfter("://")
        val fragmentSplit = withoutScheme.split("#", limit = 2)
        val beforeFragment = fragmentSplit[0]
        val displayName = fragmentSplit.getOrNull(1)
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            ?.takeIf { it.isNotBlank() }

        val querySplit = beforeFragment.split("?", limit = 2)
        val authority = querySplit[0].substringBefore("/")
        val query = querySplit.getOrNull(1).orEmpty()
        val plugin = Uri.parse("https://placeholder/?$query").getQueryParameter("plugin")
        require(plugin.isNullOrBlank()) {
            "این لینک از افزونه Shadowsocks استفاده می‌کند؛ فعلاً لینک‌های دارای plugin پشتیبانی نمی‌شوند."
        }

        val credentialsAndServer = if (authority.contains("@")) {
            val separator = authority.lastIndexOf('@')
            val encodedCredentials = authority.substring(0, separator)
            val serverPart = authority.substring(separator + 1)
            val credentials = decodeCredentials(encodedCredentials)
            credentials to serverPart
        } else {
            // Legacy format: ss://BASE64(method:password@host:port)
            val decoded = decodeBase64(authority)
                ?: throw IllegalArgumentException("ساختار لینک Outline/SS معتبر نیست.")
            val separator = decoded.lastIndexOf('@')
            require(separator > 0 && separator < decoded.lastIndex) {
                "اطلاعات سرور داخل لینک SS ناقص است."
            }
            decoded.substring(0, separator) to decoded.substring(separator + 1)
        }

        val credentialParts = credentialsAndServer.first.split(":", limit = 2)
        require(credentialParts.size == 2 && credentialParts[0].isNotBlank() && credentialParts[1].isNotBlank()) {
            "روش رمزنگاری یا رمز عبور لینک SS معتبر نیست."
        }
        val method = credentialParts[0].lowercase()
        val password = credentialParts[1]

        val serverUri = try {
            URI("ss://${credentialsAndServer.second}")
        } catch (_: Exception) {
            throw IllegalArgumentException("آدرس سرور در لینک SS معتبر نیست.")
        }
        val host = serverUri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("نام میزبان سرور در لینک SS پیدا نشد.")
        val port = serverUri.port
        require(port in 1..65535) { "پورت سرور باید بین 1 تا 65535 باشد." }

        val outbound = JSONObject()
            .put("type", "shadowsocks")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("method", method)
            .put("password", password)

        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "mixed")
            .put("sniff", true)

        val dns = JSONObject()
            .put(
                "servers",
                JSONArray().put(
                    JSONObject()
                        .put("tag", "remote-dns")
                        .put("address", "https://1.1.1.1/dns-query")
                        .put("detour", "proxy")
                )
            )
            .put("final", "remote-dns")
            .put("strategy", "prefer_ipv4")

        val route = JSONObject()
            .put("auto_detect_interface", true)
            .put(
                "rules",
                JSONArray().put(
                    JSONObject().put("protocol", "dns").put("action", "hijack-dns")
                )
            )
            .put("final", "proxy")

        val config = JSONObject()
            .put("log", JSONObject().put("level", "info"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun))
            .put(
                "outbounds",
                JSONArray()
                    .put(outbound)
                    .put(JSONObject().put("type", "direct").put("tag", "direct"))
            )
            .put("route", route)

        return ShadowsocksProfile(displayName ?: host, config.toString())
    }

    private fun decodeCredentials(value: String): String {
        val decodedPercent = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        return if (decodedPercent.contains(":")) decodedPercent
        else decodeBase64(decodedPercent)
            ?: throw IllegalArgumentException("اطلاعات رمزنگاری لینک SS قابل خواندن نیست.")
    }

    private fun decodeBase64(value: String): String? {
        val normalized = value.trim()
            .replace('-', '+')
            .replace('_', '/')
            .replace(Regex("\\s"), "")
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching {
            String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8)
        }.getOrNull()
    }
}
