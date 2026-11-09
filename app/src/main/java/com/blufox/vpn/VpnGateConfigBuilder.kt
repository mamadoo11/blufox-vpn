package com.blufox.vpn

import org.json.JSONArray
import org.json.JSONObject

object VpnGateConfigBuilder {
    fun build(server: VpnGateServer): String {
        val profile = VpnGateRepository.decodeConfig(server.configBase64)
        val directives = profile.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith(";") }
            .toList()

        val remote = directives.firstOrNull { it.startsWith("remote ", true) }
            ?.split(Regex("\\s+"))
            ?: throw IllegalArgumentException("آدرس سرور OpenVPN داخل فایل پیدا نشد.")
        val host = remote.getOrNull(1)?.trim('"')
            ?: throw IllegalArgumentException("آدرس سرور نامعتبر است.")
        val port = remote.getOrNull(2)?.toIntOrNull()
            ?: throw IllegalArgumentException("پورت سرور نامعتبر است.")
        require(port in 1..65535) { "پورت سرور خارج از محدوده است." }

        val proto = directives.firstOrNull { it.startsWith("proto ", true) }
            ?.substringAfter(' ')?.lowercase() ?: remote.getOrNull(3)?.lowercase() ?: "udp"
        val network = if (proto.startsWith("tcp")) "tcp" else "udp"

        fun inlineBlock(tag: String): String? {
            val start = profile.indexOf("<$tag>", ignoreCase = true)
            if (start < 0) return null
            val contentStart = profile.indexOf('>', start) + 1
            val end = profile.indexOf("</$tag>", contentStart, ignoreCase = true)
            if (end < 0) return null
            return profile.substring(contentStart, end).trim()
        }

        val ca = inlineBlock("ca")
        val tlsAuth = inlineBlock("tls-auth")
        val tlsCrypt = inlineBlock("tls-crypt")
        require(!ca.isNullOrBlank()) { "گواهی CA در فایل سرور وجود ندارد؛ این سرور قابل تبدیل نیست." }

        val endpoint = JSONObject()
            .put("type", "openvpn-client")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("network", network)
            .put("username", "vpn")
            .put("password", "vpn")
            .put("redirect_gateway", true)
            .put("allow_compression", "no")
            .put("ping_interval", "20s")
            .put("ping_restart", "90s")

        val tls = JSONObject().put("certificate", JSONArray().put(ca))
        if (directives.any { it.equals("remote-cert-tls server", true) }) {
            tls.put("remote_certificate_tls", "server")
        }
        if (tlsCrypt != null) {
            tls.put(
                "control_wrap",
                JSONObject().put("type", "tls_crypt").put("key", JSONArray().put(tlsCrypt))
            )
        } else if (tlsAuth != null) {
            val direction = directives.firstOrNull { it.startsWith("key-direction ", true) }
                ?.substringAfter(' ')?.trim()
            val controlWrap = JSONObject()
                .put("type", "tls_auth")
                .put("key", JSONArray().put(tlsAuth))
            when (direction) {
                "0" -> controlWrap.put("direction", "server")
                "1" -> controlWrap.put("direction", "client")
            }
            tls.put("control_wrap", controlWrap)
        }
        endpoint.put("tls", tls)

        val cipher = directives.firstOrNull { it.startsWith("cipher ", true) }
            ?.substringAfter(' ')?.trim()?.uppercase()
        val dataCiphers = directives.firstOrNull { it.startsWith("data-ciphers ", true) }
            ?.substringAfter(' ')?.split(':')?.map { it.trim().uppercase() }?.filter { it.isNotBlank() }
        if (!dataCiphers.isNullOrEmpty()) {
            endpoint.put("data_ciphers", JSONArray(dataCiphers))
        } else if (cipher != null) {
            endpoint.put("data_ciphers_fallback", cipher)
            if (cipher.endsWith("-CBC")) endpoint.put("data_ciphers", JSONArray().put(cipher))
        }
        directives.firstOrNull { it.startsWith("auth ", true) }
            ?.substringAfter(' ')?.trim()?.uppercase()?.let { endpoint.put("auth", it) }

        val tun = SingBoxConfig.tunInbound()

        val dns = JSONObject()
            .put(
                "servers",
                JSONArray().put(
                    JSONObject()
                        .put("type", "openvpn")
                        .put("tag", "ovpn-dns")
                        .put("endpoint", "proxy")
                        .put("accept_default_resolvers", true)
                        .put("accept_search_domain", true)
                )
            )
            .put("final", "ovpn-dns")
            .put("strategy", "prefer_ipv4")

        val config = JSONObject()
            .put("log", JSONObject().put("level", "info"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun))
            .put("endpoints", JSONArray().put(endpoint))
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            // This config defines its own OpenVPN DNS server, so there is no bootstrap resolver to reference.
            .put("route", SingBoxConfig.route("proxy", domainResolver = null))

        return config.toString()
    }
}
