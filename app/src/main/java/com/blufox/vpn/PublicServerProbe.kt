package com.blufox.vpn

import android.util.Base64
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder

data class ServerProbeResult(
    val reachable: Boolean,
    val latencyMs: Long?,
    val detail: String
) {
    val label: String get() = when {
        latencyMs != null -> "${latencyMs} ms • TCP"
        else -> detail
    }
}

object PublicServerProbe {
    private data class Target(val host: String, val port: Int, val transport: String = "tcp")

    fun probeLink(link: String, timeoutMs: Int = 3500): ServerProbeResult {
        val target = runCatching { targetFromShareLink(link) }.getOrNull()
            ?: return ServerProbeResult(false, null, "آدرس سرور قابل تشخیص نیست")
        return probe(target, timeoutMs)
    }

    fun probeVpnGate(server: VpnGateServer, timeoutMs: Int = 3500): ServerProbeResult {
        val profile = runCatching { VpnGateRepository.decodeConfig(server.configBase64) }.getOrNull()
            ?: return ServerProbeResult(false, null, "فایل کانفیگ قابل خواندن نیست")
        val directives = profile.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith(";") }.toList()
        val remote = directives.firstOrNull { it.startsWith("remote ", true) }
            ?.split(Regex("\\s+")) ?: return ServerProbeResult(false, null, "آدرس remote پیدا نشد")
        val host = remote.getOrNull(1)?.trim('"') ?: return ServerProbeResult(false, null, "میزبان نامعتبر")
        val port = remote.getOrNull(2)?.toIntOrNull() ?: return ServerProbeResult(false, null, "پورت نامعتبر")
        val proto = directives.firstOrNull { it.startsWith("proto ", true) }
            ?.substringAfter(' ')?.lowercase() ?: remote.getOrNull(3)?.lowercase() ?: "udp"
        if (!proto.startsWith("tcp")) {
            return ServerProbeResult(false, null, "UDP • تست TCP قابل اعمال نیست")
        }
        return probe(Target(host, port), timeoutMs)
    }

    private fun targetFromShareLink(raw: String): Target? {
        val link = raw.trim()
        return when {
            link.startsWith("vless://", true) || link.startsWith("trojan://", true) -> {
                val uri = URI(link)
                target(uri.host, uri.port)
            }
            link.startsWith("vmess://", true) -> {
                val encoded = link.substringAfter("://").substringBefore('#').trim()
                val json = String(Base64.decode(pad(encoded), Base64.DEFAULT), Charsets.UTF_8)
                val obj = JSONObject(json)
                target(obj.optString("add"), obj.optString("port").toIntOrNull() ?: -1)
            }
            link.startsWith("ss://", true) -> parseShadowsocks(link)
            else -> null
        }
    }

    private fun parseShadowsocks(link: String): Target? {
        val body = link.substringAfter("://").substringBefore('#').substringBefore('?')
        val at = body.lastIndexOf('@')
        if (at >= 0) {
            val endpoint = body.substring(at + 1)
            val uri = URI("ss://$endpoint")
            return target(uri.host, uri.port)
        }
        val decoded = runCatching {
            String(Base64.decode(pad(body), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val endpoint = decoded.substringAfterLast('@', decoded)
        val hostPort = endpoint.substringAfterLast('@')
        val split = hostPort.lastIndexOf(':')
        if (split <= 0) return null
        return target(hostPort.substring(0, split).trim('[', ']'), hostPort.substring(split + 1).toIntOrNull() ?: -1)
    }

    private fun pad(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)

    private fun target(host: String?, port: Int): Target? {
        if (host.isNullOrBlank() || port !in 1..65535) return null
        return Target(host, port)
    }

    private fun probe(target: Target, timeoutMs: Int): ServerProbeResult {
        val start = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(target.host, target.port), timeoutMs)
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000
            ServerProbeResult(true, elapsed, "در دسترس")
        } catch (_: Exception) {
            ServerProbeResult(false, null, "عدم دسترسی / timeout")
        }
    }
}
