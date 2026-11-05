package com.blufox.vpn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PublicServersScreen(
    onBack: () -> Unit,
    onConnectLink: (String, String) -> Unit,
    onConnectConfig: (String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val groups = remember { mutableStateListOf<List<CloudFoxPublicConfig>>() }
    val gateServers = remember { mutableStateListOf<VpnGateServer>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var cloudLoading by remember { mutableStateOf(false) }
    var gateLoading by remember { mutableStateOf(false) }
    var warpLoading by remember { mutableStateOf(false) }
    var gateVisibleCount by remember { mutableIntStateOf(100) }
    val probeResults = remember { mutableStateMapOf<String, ServerProbeResult>() }
    val probing = remember { mutableStateMapOf<String, Boolean>() }

    fun runProbe(key: String, action: () -> ServerProbeResult) {
        if (probing[key] == true) return
        scope.launch {
            probing[key] = true
            val result = withContext(Dispatchers.IO) { action() }
            probeResults[key] = result
            probing[key] = false
        }
    }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun toggle(key: String) { expanded[key] = !(expanded[key] ?: false) }
    fun loadCloudFox() {
        if (cloudLoading) return
        scope.launch {
            cloudLoading = true
            error = ""
            message = ""
            try {
                val result = withContext(Dispatchers.IO) { CloudFoxPublicRepository.fetchGroups() }
                groups.clear()
                groups.addAll(result)
                message = "فهرست CloudFox دریافت شد؛ ${result.sumOf { it.size }} کانفیگ قابل‌تشخیص پیدا شد."
            } catch (e: Exception) {
                error = e.message ?: "دریافت سرورهای عمومی CloudFox ناموفق بود."
            } finally { cloudLoading = false }
        }
    }

    fun loadGate() {
        if (gateLoading) return
        scope.launch {
            gateLoading = true
            error = ""
            try {
                val result = withContext(Dispatchers.IO) { VpnGateRepository.fetchServers(Int.MAX_VALUE) }
                gateServers.clear()
                gateServers.addAll(result)
                message = "از VPN Gate تعداد ${result.size} سرور دریافت شد."
            } catch (e: Exception) {
                error = e.message ?: "دریافت سرورهای VPN Gate ناموفق بود."
            } finally { gateLoading = false }
        }
    }

    LaunchedEffect(Unit) {
        loadGate()
        loadCloudFox()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "بازگشت") }
            Column(Modifier.weight(1f)) {
                Text("سرورهای عمومی", fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text("چهار منبع مستقل؛ دریافت فقط هنگام باز کردن هر بخش", color = Color(0xFF91A4BC), fontSize = 11.sp)
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        if (message.isNotBlank()) Text(message, color = Color(0xFF25C58A), fontSize = 11.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                PublicGroupHeader("GET VPN • VPN Gate", "همه سرورهای قابل دریافت از منبع VPN Gate",
                    expanded["gate"] == true, if (gateServers.isEmpty()) null else gateServers.size,
                    { toggle("gate"); if (expanded["gate"] == true && gateServers.isEmpty()) loadGate() })
                if (expanded["gate"] == true) {
                    if (gateLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (gateServers.isEmpty() && !gateLoading) TextButton(onClick = ::loadGate) { Icon(Icons.Outlined.Refresh, null); Text("دریافت سرورها") }
                    gateServers.take(gateVisibleCount).forEach { server ->
                        PublicServerRow(
                            title = server.displayName,
                            subtitle = "OpenVPN • ${server.countryCode} • پینگ اعلامی منبع: ${if (server.pingMs == Long.MAX_VALUE) "نامشخص" else "${server.pingMs} ms"} • ${server.speedLabel}",
                            onClick = { runCatching { VpnGateConfigBuilder.build(server) }.onSuccess { onConnectConfig(it, server.displayName) }.onFailure { error = it.message ?: "ساخت کانفیگ ناموفق بود." } },
                            probeText = if (probing["gate-${server.ip}"] == true) "در حال تست…" else probeResults["gate-${server.ip}"]?.label,
                            onProbe = { runProbe("gate-${server.ip}") { PublicServerProbe.probeVpnGate(server) } }
                        )
                    }
                    if (gateServers.size > gateVisibleCount) {
                        TextButton(onClick = { gateVisibleCount = (gateVisibleCount + 100).coerceAtMost(gateServers.size) }) {
                            Text("نمایش ۱۰۰ سرور بعدی • باقی‌مانده: " + (gateServers.size - gateVisibleCount))
                        }
                    }
                }
            }
            item {
                PublicGroupHeader("WARP", "پروفایل رسمی ثبت‌شده از Cloudflare؛ نه سرورهای ساختگی",
                    expanded["warp"] == true, null, { toggle("warp") })
                if (expanded["warp"] == true) {
                    Text("WARP یک شبکه با endpointهای متغیر است و فهرست سرورهای جغرافیایی مستقل مثل VPN Gate ندارد. BluFox یک پروفایل معتبر ثبت می‌کند.", color = Color(0xFF91A4BC), fontSize = 11.sp)
                    Button(
                        onClick = {
                            scope.launch {
                                warpLoading = true
                                error = ""
                                try {
                                    val config = withContext(Dispatchers.IO) { CloudflareWarpRepository.getOrRegister() }
                                    onConnectConfig(config, "Cloudflare WARP")
                                } catch (e: Exception) {
                                    error = e.message ?: "ساخت پروفایل WARP ناموفق بود."
                                } finally { warpLoading = false }
                            }
                        },
                        enabled = !warpLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (warpLoading) "در حال دریافت پروفایل…" else "ساخت / اتصال پروفایل WARP") }
                }
            }
            item {
                PublicGroupHeader("سرورهای عمومی CloudFox", "چهار فهرست عمومی که خود CloudFox منتشر می‌کند",
                    expanded["cloudfox"] == true, if (groups.isEmpty()) null else groups.sumOf { it.size },
                    { toggle("cloudfox"); if (expanded["cloudfox"] == true && groups.isEmpty()) loadCloudFox() })
                if (expanded["cloudfox"] == true) {
                    if (cloudLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (groups.isEmpty() && !cloudLoading) TextButton(onClick = ::loadCloudFox) { Icon(Icons.Outlined.Refresh, null); Text("دریافت چهار فهرست") }
                    groups.forEachIndexed { index, entries ->
                        PublicGroupHeader("فهرست ${index + 1}", "کانفیگ‌های منتشرشده در منبع CloudFox",
                            expanded["cloudfox-${index}"] == true, entries.size, { toggle("cloudfox-${index}") })
                        if (expanded["cloudfox-${index}"] == true) {
                            entries.take(120).forEach { entry ->
                                PublicServerRow(entry.name, "${entry.protocol} • منبع CloudFox / گروه ${entry.sourceGroup}",
                                    onClick = { onConnectLink(entry.shareLink, entry.name) },
                                    probeText = if (probing[entry.id] == true) "در حال تست…" else probeResults[entry.id]?.label,
                                    onProbe = { runProbe(entry.id) { PublicServerProbe.probeLink(entry.shareLink) } }
                                )
                            }
                            if (entries.size > 120) Text("نمایش ۱۲۰ مورد اول این گروه؛ تعداد کل: ${entries.size}", color = Color(0xFF91A4BC), fontSize = 10.sp)
                        }
                    }
                }
            }
            item {
                PublicGroupHeader("سرورها بر اساس پروتکل", "VLESS، VMess، Shadowsocks و Trojan",
                    expanded["protocols"] == true, if (groups.isEmpty()) null else groups.flatten().size,
                    { toggle("protocols"); if (expanded["protocols"] == true && groups.isEmpty()) loadCloudFox() })
                if (expanded["protocols"] == true) {
                    val all = groups.flatten()
                    listOf("VLESS", "VMess", "Shadowsocks", "Trojan").forEach { protocol ->
                        val entries = all.filter { it.protocol == protocol }
                        PublicGroupHeader(protocol, "تعداد: ${entries.size}", expanded["protocol-$protocol"] == true,
                            entries.size, { toggle("protocol-$protocol") })
                        if (expanded["protocol-$protocol"] == true) {
                            entries.take(150).forEach { entry ->
                                PublicServerRow(entry.name, protocol,
                                    onClick = { onConnectLink(entry.shareLink, entry.name) },
                                    probeText = if (probing[entry.id] == true) "در حال تست…" else probeResults[entry.id]?.label,
                                    onProbe = { runProbe(entry.id) { PublicServerProbe.probeLink(entry.shareLink) } }
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text("توجه: عدد تست‌شده، زمان اتصال TCP به پورت سرور است و پینگ ICMP یا تضمین اتصال VPN نیست. سرورهای UDP در این آزمون TCP پشتیبانی نمی‌شوند.", color = Color(0xFFFFB454), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PublicGroupHeader(
    title: String,
    subtitle: String,
    open: Boolean,
    count: Int?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101F33)),
        border = BorderStroke(1.dp, Color(0xFF203750)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Cloud, contentDescription = null, tint = Color(0xFF35D6E8))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color(0xFF91A4BC), fontSize = 10.sp)
            }
            if (count != null) Text(count.toString(), color = Color(0xFF35D6E8), fontSize = 11.sp)
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
        }
    }
}

@Composable
private fun PublicServerRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    probeText: String? = null,
    onProbe: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF142842)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
            Text(subtitle, color = Color(0xFF91A4BC), fontSize = 10.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                    Text("اتصال", color = Color(0xFF35D6E8), fontSize = 10.sp)
                }
                Spacer(Modifier.width(12.dp))
                if (onProbe != null) TextButton(onClick = onProbe, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                    Text("تست TCP", color = Color(0xFF35D6E8), fontSize = 10.sp)
                }
                if (!probeText.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(probeText, color = if (probeText.contains("ms")) Color(0xFF25C58A) else Color(0xFFFFB454), fontSize = 10.sp)
                }
            }
        }
    }
}