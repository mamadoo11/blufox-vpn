package com.blufox.vpn

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

data class VMessProfile(val name: String, val configJson: String)

object VMessConfigBuilder {
    fun fromShareLink(raw: String): VMessProfile {
        require(raw.trim().startsWith("vmess://", true)) { "لینک باید با vmess:// شروع شود." }
        val encoded = raw.trim().substringAfter("://").substringBefore("#")
        val normalized = encoded.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val decoded = String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8)
        val data = JSONObject(decoded)
        val host = data.optString("add").ifBlank { data.optString("host") }
        val port = data.optString("port").toIntOrNull() ?: -1
        val uuid = data.optString("id")
        require(host.isNotBlank() && port in 1..65535) { "آدرس یا پورت VMess معتبر نیست." }
        require(uuid.isNotBlank()) { "شناسه VMess داخل لینک وجود ندارد." }

        val outbound = JSONObject()
            .put("type", "vmess").put("tag", "proxy")
            .put("server", host).put("server_port", port)
            .put("uuid", uuid)
            .put("security", data.optString("scy").ifBlank { "auto" })
            .put("alter_id", data.optInt("aid", 0))

        val tlsEnabled = data.optString("tls").equals("tls", true)
        if (tlsEnabled) {
            val tls = JSONObject().put("enabled", true)
                .put("server_name", data.optString("sni").ifBlank { data.optString("host").ifBlank { host } })
                .put("insecure", false)
            val fp = data.optString("fp")
            if (fp.isNotBlank()) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
            outbound.put("tls", tls)
        }

        when (data.optString("net").lowercase().ifBlank { "tcp" }) {
            "tcp", "raw" -> Unit
            "ws" -> {
                val headers = JSONObject()
                data.optString("host").takeIf { it.isNotBlank() }?.let { headers.put("Host", it) }
                val transport = JSONObject().put("type", "ws")
                data.optString("path").takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
                if (headers.length() > 0) transport.put("headers", headers)
                outbound.put("transport", transport)
            }
            "grpc" -> {
                val transport = JSONObject().put("type", "grpc")
                data.optString("path").takeIf { it.isNotBlank() }?.let { transport.put("service_name", it) }
                outbound.put("transport", transport)
            }
            else -> throw IllegalArgumentException("نوع انتقال VMess این لینک پشتیبانی نمی‌شود.")
        }

        val tun = SingBoxConfig.tunInbound()
        val dns = SingBoxConfig.dns("proxy")
        val route = SingBoxConfig.route("proxy")
        val config = JSONObject().put("log", JSONObject().put("level", "info"))
            .put("dns", dns).put("inbounds", JSONArray().put(tun))
            .put("outbounds", JSONArray().put(outbound).put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", route)
        val name = data.optString("ps").ifBlank { host }
        return VMessProfile(name, config.toString())
    }
}