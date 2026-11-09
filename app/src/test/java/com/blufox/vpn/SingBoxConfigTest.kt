package com.blufox.vpn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the sing-box schema used by every protocol builder.
 * libbox 1.14 rejects the legacy DNS server format (removed in 1.14.0) and inbound `sniff` fields (removed in 1.13.0).
 */
class SingBoxConfigTest {

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    @Test
    fun tunInbound_hasNoRemovedInboundFields() {
        val tun = SingBoxConfig.tunInbound()
        listOf("sniff", "sniff_override_destination", "sniff_timeout", "domain_strategy").forEach {
            assertFalse("inbound field '$it' was removed in sing-box 1.13", tun.has(it))
        }
        assertEquals("tun", tun.getString("type"))
        assertEquals(SingBoxConfig.TUN_TAG, tun.getString("tag"))
        assertTrue(tun.getBoolean("auto_route"))
    }

    @Test
    fun dns_usesTypedServersAndNoLegacyAddressField() {
        val servers = SingBoxConfig.dns("proxy").getJSONArray("servers").objects()
        assertTrue(servers.isNotEmpty())
        servers.forEach { server ->
            assertTrue("server ${server.optString("tag")} needs a type", server.has("type"))
            assertFalse("legacy 'address' field was removed in sing-box 1.14", server.has("address"))
        }
    }

    @Test
    fun dns_remoteServerGoesThroughDetour_bootstrapStaysDirect() {
        val servers = SingBoxConfig.dns("my-proxy").getJSONArray("servers").objects()
        val remote = servers.first { it.getString("tag") == SingBoxConfig.REMOTE_DNS_TAG }
        val bootstrap = servers.first { it.getString("tag") == SingBoxConfig.BOOTSTRAP_DNS_TAG }
        assertEquals("my-proxy", remote.getString("detour"))
        // The proxy hostname must never be resolved through the proxy itself.
        assertFalse(bootstrap.has("detour"))
    }

    @Test
    fun dns_finalPointsToAnExistingServer() {
        val dns = SingBoxConfig.dns("proxy")
        val tags = dns.getJSONArray("servers").objects().map { it.getString("tag") }
        assertTrue(dns.getString("final") in tags)
    }

    @Test
    fun route_sniffsTunTrafficAndHijacksDns() {
        val rules = SingBoxConfig.route("proxy").getJSONArray("rules").objects()
        assertTrue(rules.any { it.optString("action") == "sniff" && it.optString("inbound") == SingBoxConfig.TUN_TAG })
        assertTrue(rules.any { it.optString("action") == "hijack-dns" && it.optString("protocol") == "dns" })
        // Sniffing must happen before DNS hijacking so the protocol is known.
        val sniffIndex = rules.indexOfFirst { it.optString("action") == "sniff" }
        val hijackIndex = rules.indexOfFirst { it.optString("action") == "hijack-dns" }
        assertTrue(sniffIndex < hijackIndex)
    }

    @Test
    fun route_defaultDomainResolverReferencesBootstrapServer() {
        val route = SingBoxConfig.route("proxy")
        val tags = SingBoxConfig.dns("proxy").getJSONArray("servers").objects().map { it.getString("tag") }
        val resolver = route.getJSONObject("default_domain_resolver").getString("server")
        assertTrue(resolver in tags)
        assertEquals("proxy", route.getString("final"))
    }

    @Test
    fun route_canOmitDomainResolverWhenConfigHasItsOwnDns() {
        val route = SingBoxConfig.route("proxy", domainResolver = null)
        assertFalse(route.has("default_domain_resolver"))
        assertEquals("proxy", route.getString("final"))
    }
}
