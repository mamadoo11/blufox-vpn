package com.blufox.vpn

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared sing-box configuration fragments for libbox 1.14+.
 *
 * Schema notes (sing-box "Deprecated Feature List"):
 *  - Inbound `sniff` / `domain_strategy` fields were removed in 1.13.0; sniffing is now a route rule action.
 *  - The legacy DNS server format (`"address": "https://..."`) was removed in 1.14.0; servers are typed.
 *
 * Every protocol builder must use these helpers so the whole app stays on one valid schema.
 */
object SingBoxConfig {
    const val TUN_TAG = "tun-in"
    const val REMOTE_DNS_TAG = "remote-dns"
    const val BOOTSTRAP_DNS_TAG = "bootstrap-dns"

    /** TUN inbound. Sniffing is configured in [route] rules, not on the inbound. */
    fun tunInbound(): JSONObject = JSONObject()
        .put("type", "tun")
        .put("tag", TUN_TAG)
        .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
        .put("auto_route", true)
        .put("strict_route", true)
        .put("stack", "mixed")

    /**
     * DNS configuration.
     * - [REMOTE_DNS_TAG]: DNS-over-HTTPS sent through [detour] (the proxy/endpoint) for all application lookups.
     * - [BOOTSTRAP_DNS_TAG]: direct DNS-over-HTTPS used only to resolve the proxy server's own hostname, so
     *   the proxy is never asked to resolve its own address (which would loop).
     */
    fun dns(detour: String): JSONObject = JSONObject()
        .put(
            "servers",
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "https")
                        .put("tag", REMOTE_DNS_TAG)
                        .put("server", "1.1.1.1")
                        .put("detour", detour)
                )
                .put(
                    JSONObject()
                        .put("type", "https")
                        .put("tag", BOOTSTRAP_DNS_TAG)
                        .put("server", "1.1.1.1")
                )
        )
        .put("final", REMOTE_DNS_TAG)
        .put("strategy", "prefer_ipv4")

    /** Route rules: sniff traffic entering the TUN, then hijack DNS so lookups reach the DNS module. */
    fun rules(): JSONArray = JSONArray()
        .put(JSONObject().put("inbound", TUN_TAG).put("action", "sniff"))
        .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))

    /**
     * Route section. [domainResolver] resolves outbound server hostnames; pass null when the config has no
     * such DNS server (for example when it defines its own DNS servers).
     */
    fun route(finalOutbound: String, domainResolver: String? = BOOTSTRAP_DNS_TAG): JSONObject {
        val route = JSONObject()
            .put("auto_detect_interface", true)
            .put("rules", rules())
            .put("final", finalOutbound)
        if (domainResolver != null) {
            route.put("default_domain_resolver", JSONObject().put("server", domainResolver))
        }
        return route
    }
}
