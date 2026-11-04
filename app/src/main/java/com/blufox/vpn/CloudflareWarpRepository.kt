package com.blufox.vpn

import android.util.Base64
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.Instant

object CloudflareWarpRepository {
    private const val API_VERSION = "v0a2158"
    private const val CLIENT_VERSION = "a-7.21-0721"
    private const val USER_AGENT = "okhttp/3.12.1"
    private val apiHosts = listOf(
        "https://api.devices.cloudflare.com",
        "https://api.cloudflareclient.com"
    )

    @Volatile private var inMemoryConfig: String? = null

    fun getOrRegister(): String {
        inMemoryConfig?.let { return it }
        synchronized(this) {
            inMemoryConfig?.let { return it }
            val generated = registerNewDevice()
            inMemoryConfig = generated
            return generated
        }
    }

    private fun registerNewDevice(): String {
        // The private key is generated locally. Only the public key is sent to Cloudflare.
        val privateParams = X25519PrivateKeyParameters(SecureRandom())
        val publicParams = privateParams.generatePublicKey()
        val privateKey = Base64.encodeToString(privateParams.encoded, Base64.NO_WRAP)
        val publicKey = Base64.encodeToString(publicParams.encoded, Base64.NO_WRAP)

        val requestBody = JSONObject()
            .put("key", publicKey)
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", Instant.now().toString())
            .put("model", "PC")
            .put("type", "Android")
            .put("locale", "en_US")
            .toString()

        var responseBody: String? = null
        var lastError: Exception? = null
        for (host in apiHosts) {
            try {
                responseBody = post("$host/$API_VERSION/reg", requestBody)
                break
            } catch (error: Exception) {
                lastError = error
            }
        }
        val body = responseBody ?: throw IllegalStateException(
            lastError?.message ?: "ارتباط با سرویس ثبت WARP برقرار نشد."
        )

        val root = JSONObject(body)
        val config = root.optJSONObject("config")
            ?: throw IllegalStateException("پاسخ Cloudflare فاقد تنظیمات WARP است.")
        val peers = config.optJSONArray("peers")
            ?: throw IllegalStateException("اطلاعات سرور WARP در پاسخ وجود ندارد.")
        val peer = peers.optJSONObject(0)
            ?: throw IllegalStateException("کلید عمومی سرور WARP دریافت نشد.")
        val peerPublicKey = peer.optString("public_key")
        require(peerPublicKey.isNotBlank()) { "کلید عمومی سرور WARP خالی است." }

        val endpointObject = peer.optJSONObject("endpoint")
        val endpointHost = endpointObject?.optString("host").orEmpty()
            .ifBlank { "engage.cloudflareclient.com:2408" }
        val endpoint = parseEndpoint(endpointHost)

        val addresses = config.optJSONObject("interface")?.optJSONObject("addresses")
            ?: throw IllegalStateException("آدرس شبکه WARP دریافت نشد.")
        val v4 = addresses.optString("v4")
        val v6 = addresses.optString("v6")
        require(v4.isNotBlank()) { "آدرس IPv4 حساب WARP خالی است." }

        val interfaceAddresses = JSONArray()
            .put(if (v4.contains("/")) v4 else "$v4/32")
        if (v6.isNotBlank()) interfaceAddresses.put(if (v6.contains("/")) v6 else "$v6/128")

        val peerConfig = JSONObject()
            .put("address", endpoint.first)
            .put("port", endpoint.second)
            .put("public_key", peerPublicKey)
            .put("allowed_ips", JSONArray().put("0.0.0.0/0").put("::/0"))
            .put("persistent_keepalive_interval", 25)

        val clientId = config.optString("client_id")
        if (clientId.isNotBlank()) {
            val reserved = runCatching {
                Base64.decode(clientId, Base64.DEFAULT).take(3).map { it.toInt() and 0xff }
            }.getOrDefault(emptyList())
            if (reserved.size == 3) peerConfig.put("reserved", JSONArray(reserved))
        }

        val wireguardEndpoint = JSONObject()
            .put("type", "wireguard")
            .put("tag", "warp")
            .put("address", interfaceAddresses)
            .put("private_key", privateKey)
            .put("mtu", 1280)
            .put("peers", JSONArray().put(peerConfig))

        val dns = JSONObject()
            .put("servers", JSONArray().put(
                JSONObject()
                    .put("tag", "warp-dns")
                    .put("address", "https://1.1.1.1/dns-query")
                    .put("detour", "warp")
            ))
            .put("final", "warp-dns")
            .put("strategy", "prefer_ipv4")

        return JSONObject()
            .put("log", JSONObject().put("level", "info"))
            .put("dns", dns)
            .put("endpoints", JSONArray().put(wireguardEndpoint))
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject().put("auto_detect_interface", true).put("final", "warp"))
            .toString()
    }

    private fun parseEndpoint(raw: String): Pair<String, Int> {
        val value = raw.trim()
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            require(end > 1) { "آدرس IPv6 سرور WARP نامعتبر است." }
            val host = value.substring(1, end)
            val port = value.substring(end + 1).removePrefix(":").toIntOrNull() ?: 2408
            require(port in 1..65535)
            return host to port
        }
        val split = value.lastIndexOf(':')
        if (split <= 0) return value to 2408
        val host = value.substring(0, split)
        val port = value.substring(split + 1).toIntOrNull() ?: 2408
        require(port in 1..65535) { "پورت WARP نامعتبر است." }
        return host to port
    }

    private fun post(url: String, body: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("CF-Client-Version", CLIENT_VERSION)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(status == 200) { "ثبت WARP ناموفق بود (HTTP $status)." }
            return response
        } finally {
            connection.disconnect()
        }
    }
}
