package com.blufox.vpn

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class TrojanProfile(val name: String, val configJson: String)

object TrojanConfigBuilder {
    fun fromShareLink(raw: String): TrojanProfile {
        val link = raw.trim()
        require(link.startsWith("trojan://", true)) { "لینک باید با trojan:// شروع شود." }
        val uri = Uri.parse(link)
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("آدرس سرور Trojan معتبر نیست.")
        val port = uri.port
        require(port in 1..65535) { "پورت Trojan معتبر نیست." }
        val password = Uri.decode(uri.encodedUserInfo.orEmpty()).takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("رمز عبور Trojan داخل لینک نیست.")
        fun q(key: String) = uri.getQueryParameter(key)?.takeIf { it.isNotBlank() }

        val security = q("security")?.lowercase() ?: "tls"
        require(security == "tls") { "این نوع امنیت Trojan هنوز پشتیبانی نمی‌شود." }
        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", q("sni") ?: q("peer") ?: host)
            .put("insecure", q("allowInsecure") == "1" || q("insecure") == "1")
        q("fp")?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
        q("alpn")?.let { value ->
            tls.put("alpn", JSONArray(value.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
        }

        val outbound = JSONObject()
            .put("type", "trojan")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("password", password)
            .put("tls", tls)

        when (q("type")?.lowercase() ?: "tcp") {
            "tcp", "raw" -> Unit
            "ws" -> {
                val headers = JSONObject()
                (q("host") ?: q("Host"))?.let { headers.put("Host", it) }
                val transport = JSONObject().put("type", "ws")
                q("path")?.let { transport.put("path", it) }
                if (headers.length() > 0) transport.put("headers", headers)
                outbound.put("transport", transport)
            }
            "grpc" -> {
                val transport = JSONObject().put("type", "grpc")
                (q("serviceName") ?: q("service_name"))?.let { transport.put("service_name", it) }
                outbound.put("transport", transport)
            }
            else -> throw IllegalArgumentException("انتقال Trojan در این کانفیگ پشتیبانی نمی‌شود.")
        }

        val tun = SingBoxConfig.tunInbound()
        val dns = SingBoxConfig.dns("proxy")
        val route = SingBoxConfig.route("proxy")
        val config = JSONObject()
            .put("log", JSONObject().put("level", "info"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", JSONArray().put(outbound).put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", route)

        val name = uri.fragment?.let { Uri.decode(it) }?.takeIf { it.isNotBlank() } ?: host
        return TrojanProfile(name, config.toString())
    }
}
