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
import java.net.URI
import java.net.URL
import java.time.Duration
import java.time.Instant

data class FreeProxyServer(
    val tag: String,
    val type: String,
    val host: String,
    val port: Int,
    val countryCode: String,
    val flagEmoji: String,
    val outboundJson: String,
    val upstreamUpdatedAt: String,
    val sourceLabel: String = "0xRadikal • Secure/Verified"
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

data class TunnelProbePlan(
    val configJson: String,
    val tags: List<String>,
    val groupTags: List<String>
)

object FreeProxyRepository {
    private const val INDEX = "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/index.json"
    private const val INDEX_MIRROR = "https://cdn.jsdelivr.net/gh/0xRadikal/Free-v2ray-Configs@main/index.json"
    private const val MAX_SERVERS = 100
    private const val MAX_SOURCE_AGE_SECONDS = 60 * 60L
    private const val MAX_FUTURE_SKEW_SECONDS = 5 * 60L
    private val supportedTypes = setOf("vless", "vmess", "trojan", "shadowsocks")

    @Volatile private var latestSourceConfig: String? = null

    @Volatile
    var lastUpdatedAt: String = ""
        private set

    @Volatile
    var secureCount: Int = 0
        private set

    suspend fun fetchServers(limit: Int = MAX_SERVERS): List<FreeProxyServer> = withContext(Dispatchers.IO) {
        require(limit in 1..MAX_SERVERS)
        latestSourceConfig = null
        lastUpdatedAt = ""
        secureCount = 0

        // The upstream index is the contract for URLs, counts and freshness.
        val indexRaw = runCatching { downloadText(INDEX, 100_000) }.getOrNull().orEmpty()
            .ifBlank { downloadText(INDEX_MIRROR, 100_000) }
        val index = JSONObject(indexRaw)
        val updatedAt = index.optString("updated_at").trim()
        require(updatedAt.isNotBlank()) { "زمان به‌روزرسانی منبع مشخص نیست؛ سرورها فعال نمی‌شوند." }
        val sourceInstant = runCatching { Instant.parse(updatedAt) }
            .getOrElse { throw IllegalStateException("زمان منبع معتبر نیست؛ سرورها فعال نمی‌شوند.", it) }
        val ageSeconds = Duration.between(sourceInstant, Instant.now()).seconds
        require(ageSeconds in -MAX_FUTURE_SKEW_SECONDS..MAX_SOURCE_AGE_SECONDS) {
            "اعتبارسنجی منبع بیش از ۶۰ دقیقه قدیمی است؛ برای جلوگیری از نمایش سرور منقضی، فهرست غیرفعال شد."
        }

        val secure = index.optJSONObject("cascade_categories")?.optJSONObject("secure")
            ?: throw IllegalStateException("اطلاعات دسته Verified در منبع پیدا نشد.")
        val secureTotal = secure.optInt("unique", 0)
        require(secureTotal > 0) { "منبع فعلی هیچ سرور Secure/Verified معتبری اعلام نکرده است." }

        val files = secure.optJSONObject("files")
            ?: throw IllegalStateException("نشانی فایل sing-box در index منبع پیدا نشد.")
        val primaryUrl = files.optString("singbox_json")
        val mirrorUrl = files.optString("singbox_json_mirror")
        require(primaryUrl.isNotBlank() || mirrorUrl.isNotBlank()) {
            "نشانی فهرست sing-box در منبع موجود نیست."
        }
        val raw = runCatching {
            downloadText(requireTrustedSourceUrl(primaryUrl), 2_000_000)
        }.getOrNull().orEmpty().ifBlank {
            downloadText(requireTrustedSourceUrl(mirrorUrl), 2_000_000)
        }

        val root = JSONObject(raw)
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
        val byTag = HashMap<String, JSONObject>()
        for (i in 0 until outbounds.length()) {
            val item = outbounds.optJSONObject(i) ?: continue
            val tag = item.optString("tag")
            if (tag.isNotBlank()) byTag[tag] = item
        }

        val autoGroup = (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "urltest" }
            ?: throw IllegalStateException("گروه سرورهای Verified در فایل sing-box پیدا نشد.")
        val autoTags = autoGroup.optJSONArray("outbounds")?.let { tags ->
            (0 until tags.length()).map { tags.optString(it) }
        }.orEmpty()

        val topMeta = index.optJSONObject("top100")
        val topPrimary = topMeta?.optString("url").orEmpty()
        val topMirror = topMeta?.optString("url_mirror").orEmpty()
        val topText = runCatching {
            downloadText(requireTrustedSourceUrl(topPrimary), 120_000)
        }.getOrNull().orEmpty().ifBlank {
            if (topMirror.isBlank()) "" else downloadText(requireTrustedSourceUrl(topMirror), 120_000)
        }
        val preferredTags = topText.lineSequence()
            .mapNotNull { extractTag(it) }
            .toList()
        val orderedTags = (if (preferredTags.isNotEmpty()) preferredTags else autoTags) + autoTags

        val result = mutableListOf<FreeProxyServer>()
        val seen = HashSet<String>()
        for (tag in orderedTags.distinct()) {
            if (result.size >= limit) break
            val item = byTag[tag] ?: continue
            val type = item.optString("type").lowercase()
            val host = item.optString("server")
            val port = item.optInt("server_port", 0)
            if (type !in supportedTypes || host.isBlank() || port !in 1..65535) continue
            // Skip chained profiles: their extra outbound dependencies are not copied into a single-server config.
            if (item.optString("detour").isNotBlank()) continue

            val identity = "$type|$host|$port|$tag"
            if (!seen.add(identity)) continue
            val prefix = tag.substringBefore(" | ")
            val countryCode = prefix.substringBefore(' ')
                .takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()
            val flagEmoji = prefix.substringAfter(' ', "").trim()
            result += FreeProxyServer(
                tag = tag,
                type = type,
                host = host,
                port = port,
                countryCode = countryCode,
                flagEmoji = flagEmoji,
                outboundJson = item.toString(),
                upstreamUpdatedAt = updatedAt
            )
        }
        require(result.isNotEmpty()) { "منبع فعلی هیچ سرور سازگار و Verified برای موتور sing-box منتشر نکرده است." }

        latestSourceConfig = raw
        lastUpdatedAt = updatedAt
        secureCount = secureTotal
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
        }.awaitAll().sortedWith(
            compareBy<ServerPingResult> { it.latencyMs == null }
                .thenBy { it.latencyMs ?: Long.MAX_VALUE }
        )
    }

    private fun requireFreshSourceConfig(): String {
        val config = latestSourceConfig
            ?: throw IllegalStateException("ابتدا فهرست تازه سرورها را دریافت کن.")
        val sourceInstant = runCatching { Instant.parse(lastUpdatedAt) }
            .getOrElse { throw IllegalStateException("زمان به‌روزرسانی منبع معتبر نیست؛ سرورها فعال نمی‌شوند.", it) }
        val ageSeconds = Duration.between(sourceInstant, Instant.now()).seconds
        require(ageSeconds in -MAX_FUTURE_SKEW_SECONDS..MAX_SOURCE_AGE_SECONDS) {
            "اعتبار منبع بیش از ۶۰ دقیقه قدیمی است؛ فهرست را دوباره به‌روزرسانی کن."
        }
        return config
    }

    fun buildSingleServerConfig(server: FreeProxyServer): String {
        val sourceConfig = requireFreshSourceConfig()
        val root = JSONObject(sourceConfig)
        val inbounds = root.optJSONArray("inbounds") ?: JSONArray()
        val tun = (0 until inbounds.length())
            .mapNotNull { inbounds.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "tun" }
            ?: throw IllegalStateException("ورودی TUN در پروفایل منبع پیدا نشد.")
        root.put("inbounds", JSONArray().put(tun))

        val originalOutbounds = root.optJSONArray("outbounds") ?: JSONArray()
        val selected = (0 until originalOutbounds.length())
            .mapNotNull { originalOutbounds.optJSONObject(it) }
            .firstOrNull { it.optString("tag") == server.tag }
            ?: throw IllegalStateException("این سرور دیگر در آخرین نسخه Verified وجود ندارد؛ فهرست را به‌روزرسانی کن.")
        require(selected.optString("type").lowercase() == server.type &&
            selected.optString("server") == server.host &&
            selected.optInt("server_port") == server.port) {
            "مشخصات سرور با نسخه تازه منبع یکسان نیست؛ دوباره فهرست را دریافت کن."
        }
        require(selected.optString("detour").isBlank()) {
            "این سرور به outbound دیگری وابسته است و به‌صورت مستقل قابل اتصال نیست."
        }
        val selectedTag = selected.optString("tag")
        require(selectedTag.isNotBlank()) { "برچسب سرور نامعتبر است." }

        val removedGroupTags = (0 until originalOutbounds.length())
            .mapNotNull { originalOutbounds.optJSONObject(it) }
            .filter { it.optString("type") == "selector" || it.optString("type") == "urltest" }
            .map { it.optString("tag") }
            .filter { it.isNotBlank() }
            .toSet()

        val direct = (0 until originalOutbounds.length())
            .mapNotNull { originalOutbounds.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "direct" }
            ?: JSONObject().put("type", "direct").put("tag", "direct")

        root.optJSONObject("dns")?.optJSONArray("servers")?.let { dnsServers ->
            for (i in 0 until dnsServers.length()) {
                val dnsServer = dnsServers.optJSONObject(i) ?: continue
                if (dnsServer.optString("detour") in removedGroupTags) {
                    dnsServer.put("detour", selectedTag)
                }
            }
        }

        val route = root.optJSONObject("route") ?: JSONObject()
        route.optJSONArray("rules")?.let { rules ->
            for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i) ?: continue
                if (rule.optString("outbound") in removedGroupTags) {
                    rule.put("outbound", selectedTag)
                }
            }
        }
        route.put("final", selectedTag)
        root.put("route", route)
        root.put("outbounds", JSONArray().put(selected).put(direct))
        return root.toString()
    }


    /**
     * Builds a validated-source template for sequential, single-outbound Android TUN checks.
     * The service substitutes one candidate tag at a time and closes each TUN before continuing.
     */
    fun buildTunnelProbePlan(servers: List<FreeProxyServer>): TunnelProbePlan {
        require(servers.isNotEmpty()) { "فهرست سرورها برای آزمایش خالی است." }
        require(servers.size <= MAX_SERVERS) { "تعداد سرورها از سقف مجاز بیشتر است." }
        val sourceConfig = requireFreshSourceConfig()
        val root = JSONObject(sourceConfig)
        val originalOutbounds = root.optJSONArray("outbounds") ?: JSONArray()
        val sourceByTag = HashMap<String, JSONObject>()
        val groupTags = HashSet<String>()
        val retainedSystemOutbounds = mutableListOf<JSONObject>()

        for (i in 0 until originalOutbounds.length()) {
            val item = originalOutbounds.optJSONObject(i) ?: continue
            val tag = item.optString("tag")
            if (tag.isNotBlank()) sourceByTag[tag] = item
            when (item.optString("type")) {
                "selector", "urltest" -> if (tag.isNotBlank()) groupTags.add(tag)
                "direct", "block", "dns" -> retainedSystemOutbounds.add(item)
            }
        }

        val selectedOutbounds = mutableListOf<JSONObject>()
        val tags = mutableListOf<String>()
        for (server in servers.distinctBy { it.tag }) {
            val outbound = sourceByTag[server.tag]
                ?: throw IllegalStateException("سرور " + server.tag + " دیگر در منبع تازه وجود ندارد.")
            require(outbound.optString("type").equals(server.type, true) &&
                outbound.optString("server") == server.host &&
                outbound.optInt("server_port") == server.port) {
                "مشخصات سرور " + server.tag + " با منبع تازه یکسان نیست."
            }
            require(outbound.optString("detour").isBlank()) {
                "سرور وابسته به outbound دیگر برای آزمون مستقل مناسب نیست: " + server.tag
            }
            selectedOutbounds.add(outbound)
            tags.add(server.tag)
        }
        val outbounds = JSONArray()
        selectedOutbounds.forEach { outbounds.put(it) }
        retainedSystemOutbounds.distinctBy { it.optString("tag") }.forEach { outbounds.put(it) }
        root.put("outbounds", outbounds)

        val inbounds = root.optJSONArray("inbounds") ?: JSONArray()
        val tun = (0 until inbounds.length())
            .mapNotNull { inbounds.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "tun" }
            ?: throw IllegalStateException("ورودی TUN در پروفایل منبع پیدا نشد.")
        root.put("inbounds", JSONArray().put(tun))

        return TunnelProbePlan(root.toString(), tags, groupTags.toList())
    }

    private fun requireTrustedSourceUrl(raw: String): String {
        require(raw.isNotBlank()) { "نشانی فایل منبع خالی است." }
        val uri = URI(raw)
        require(uri.scheme == "https" &&
            uri.host in setOf("raw.githubusercontent.com", "cdn.jsdelivr.net")) {
            "نشانی فایل منبع قابل اعتماد نیست."
        }
        return raw
    }

    private fun extractTag(rawLine: String): String? {
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#")) return null
        val fragment = line.substringAfter("#", "")
        if (fragment.isNotBlank()) {
            return runCatching { java.net.URLDecoder.decode(fragment, "UTF-8") }
                .getOrNull()?.takeIf { it.isNotBlank() }
        }
        if (line.startsWith("vmess://", true)) {
            val encoded = line.substringAfter("://").substringBefore("#").trim()
            val normalized = encoded + "=".repeat((4 - encoded.length % 4) % 4)
            val json = runCatching {
                String(java.util.Base64.getDecoder().decode(normalized), Charsets.UTF_8)
            }.getOrNull() ?: return null
            val vmess = runCatching { JSONObject(json) }.getOrNull() ?: return null
            return vmess.optString("ps").ifBlank { vmess.optString("name") }
                .takeIf { it.isNotBlank() }
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
            setRequestProperty("User-Agent", "BluFoxVPN/0.6 Android")
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
