package com.blufox.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

data class FreeProxyServer(
    val tag: String,
    val type: String,
    val host: String,
    val port: Int,
    val outboundJson: String,
    val upstreamUpdatedAt: String,
    val sourceLabel: String = "0xRadikal • verified"
) {
    val displayName: String get() = tag
}

data class ServerPingResult(
    val server: FreeProxyServer,
    val latencyMs: Long?,
    val error: String? = null
) {
    val reachable: Boolean get() = latencyMs != null
}

object FreeProxyRepository {
    private const val PRIMARY = "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/singbox.json"
    private const val MIRROR = "https://cdn.jsdelivr.net/gh/0xRadikal/Free-v2ray-Configs@main/verified/singbox.json"
    private const val INDEX = "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/index.json"
    private const val TOP100 = "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/top100.txt"
    private const val MAX_SERVERS = 100
    private val supportedTypes = setOf("vless", "vmess", "trojan", "shadowsocks")
    @Volatile private var latestSourceConfig: String? = null
    @Volatile var lastUpdatedAt: String = ""
        private set

    suspend fun fetchServers(limit: Int = MAX_SERVERS): List<FreeProxyServer> = withContext(Dispatchers.IO) {
        require(limit in 1..MAX_SERVERS)
        val raw = runCatching { downloadText(PRIMARY, 2_000_000) }.getOrNull().orEmpty()
            .ifBlank { downloadText(MIRROR, 2_000_000) }
        require(raw.isNotBlank()) { "فهرست سرورهای جایگزین دریافت نشد." }
        latestSourceConfig = raw
        val root = JSONObject(raw)
        val updatedAt = runCatching { JSONObject(downloadText(INDEX, 100_000)).optString("updated_at") }.getOrDefault("")
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
        val byTag = HashMap<String, JSONObject>()
        for (i in 0 until outbounds.length()) {
            val item = outbounds.optJSONObject(i) ?: continue
            byTag[item.optString("tag")] = item
        }
        val autoGroup = (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "urltest" }
            ?: throw IllegalStateException("گروه سرورهای تأییدشده در منبع پیدا نشد.")
        lastUpdatedAt = updatedAt
        val tags = autoGroup.optJSONArray("outbounds") ?: JSONArray()
        val autoTags = (0 until tags.length()).map { tags.optString(it) }
        val preferredTags = runCatching { downloadText(TOP100, 120_000) }
            .getOrNull()
            ?.lineSequence()
            ?.mapNotNull { extractTag(it) }
            ?.toList()
            .orEmpty()
        val orderedTags = if (preferredTags.isNotEmpty()) preferredTags + autoTags else autoTags
        val result = mutableListOf<FreeProxyServer>()
        val seen = HashSet<String>()
        for (tag in orderedTags.distinct()) {
            if (result.size >= limit) break
            val item = byTag[tag] ?: continue
            val type = item.optString("type").lowercase()
            val host = item.optString("server")
            val port = item.optInt("server_port", 0)
            if (type !in supportedTypes || host.isBlank() || port !in 1..65535) continue
            // Chained outbounds need their dependencies; skip them rather than creating a broken profile.
            if (item.has("detour") && item.optString("detour").isNotBlank()) continue
            val identity = "$type|$host|$port|$tag"
            if (!seen.add(identity)) continue
            result += FreeProxyServer(tag, type, host, port, item.toString(), updatedAt)
        }
        require(result.isNotEmpty()) { "منبع فعلی سرور سازگار با موتور sing-box منتشر نکرده است." }
        result
    }

    suspend fun pingAll(
        servers: List<FreeProxyServer>,
        timeoutMs: Int = 3500
    ): List<ServerPingResult> = coroutineScope {
        val gate = Semaphore(10)
        servers.map { server ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val started = System.nanoTime()
                    try {
                        Socket().use { socket ->
                            socket.tcpNoDelay = true
                            socket.connect(InetSocketAddress(server.host, server.port), timeoutMs)
                        }
                        ServerPingResult(server, (System.nanoTime() - started) / 1_000_000)
                    } catch (error: Exception) {
                        ServerPingResult(server, null, error.message ?: "دسترسی TCP ناموفق")
                    }
                }
            }
        }.awaitAll().sortedWith(compareBy<ServerPingResult> { it.latencyMs == null }.thenBy { it.latencyMs ?: Long.MAX_VALUE })
    }

    fun buildSingleServerConfig(server: FreeProxyServer): String {
        val sourceConfig = latestSourceConfig
            ?: throw IllegalStateException("ابتدا فهرست سرورها را به‌روزرسانی کن.")
        val root = JSONObject(sourceConfig)
        val inbounds = root.optJSONArray("inbounds") ?: JSONArray()
        val tun = (0 until inbounds.length())
            .mapNotNull { inbounds.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "tun" }
            ?: throw IllegalStateException("ورودی TUN در پروفایل منبع پیدا نشد.")
        root.put("inbounds", JSONArray().put(tun))

        val originalOutbounds = root.optJSONArray("outbounds") ?: JSONArray()
        val direct = (0 until originalOutbounds.length())
            .mapNotNull { originalOutbounds.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "direct" }
            ?: JSONObject().put("type", "direct").put("tag", "direct")
        val selected = JSONObject(server.outboundJson)
        val selectedTag = selected.optString("tag")
        require(selectedTag.isNotBlank()) { "برچسب سرور نامعتبر است." }

        val dns = root.optJSONObject("dns")
        dns?.optJSONArray("servers")?.let { dnsServers ->
            for (i in 0 until dnsServers.length()) {
                val dnsServer = dnsServers.optJSONObject(i) ?: continue
                if (dnsServer.optString("detour") == "🚀 @Raydikalx") {
                    dnsServer.put("detour", selectedTag)
                }
            }
        }
        val route = root.optJSONObject("route") ?: JSONObject()
        route.put("final", selectedTag)
        root.put("route", route)
        root.put("outbounds", JSONArray().put(selected).put(direct))
        return root.toString()
    }

    private fun extractTag(rawLine: String): String? {
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#")) return null
        val fragment = line.substringAfter("#", "")
        if (fragment.isNotBlank()) {
            return runCatching { java.net.URLDecoder.decode(fragment, "UTF-8") }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        if (line.startsWith("vmess://", true)) {
            val encoded = line.substringAfter("://").substringBefore("#").trim()
            val normalized = encoded + "=".repeat((4 - encoded.length % 4) % 4)
            val json = runCatching {
                String(java.util.Base64.getDecoder().decode(normalized), Charsets.UTF_8)
            }.getOrNull() ?: return null
            val vmess = runCatching { JSONObject(json) }.getOrNull() ?: return null
            return vmess.optString("ps").ifBlank { vmess.optString("name") }.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun downloadText(address: String, maxBytes: Int): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            requestMethod = "GET"
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BluFoxVPN/0.5 Android")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maxBytes) { "حجم فهرست بیش از حد مجاز است." }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }
}
