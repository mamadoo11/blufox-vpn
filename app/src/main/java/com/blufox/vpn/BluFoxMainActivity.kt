package com.blufox.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val BNight = Color(0xFF07111F)
private val BPanel = Color(0xFF101F33)
private val BPanelSoft = Color(0xFF142842)
private val BElectric = Color(0xFF398BFF)
private val BCyan = Color(0xFF35D6E8)
private val BMuted = Color(0xFF91A4BC)
private val BGreen = Color(0xFF25C58A)
private val BAmber = Color(0xFFFFB454)
private val BRed = Color(0xFFFF667A)

class BluFoxMainActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingConfig: String? = null
    private var pendingName = ""

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startPendingVpn()
        else VpnRuntime.update(VpnStatus.ERROR, message = "اجازه ایجاد VPN داده نشد.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vpnState by VpnRuntime.state.collectAsState()
            BluFoxRoot(
                vpnState = vpnState,
                onConnectLink = ::connectLink,
                onConnectConfig = ::connectConfig,
                onDisconnect = ::disconnect
            )
        }
    }

    private fun connectLink(raw: String, displayName: String) {
        val link = raw.trim()
        when {
            link.startsWith("ssconf://", true) -> activityScope.launch {
                VpnRuntime.update(VpnStatus.CONNECTING, displayName, "در حال دریافت تنظیمات کلید پویای Outline…")
                try {
                    val profile = withContext(Dispatchers.IO) { DynamicAccessKeyResolver.resolve(link) }
                    connectConfig(profile.configJson, displayName.ifBlank { profile.name })
                } catch (error: Exception) {
                    VpnRuntime.update(VpnStatus.ERROR, displayName, error.message ?: "دریافت کلید پویای Outline ناموفق بود.")
                }
            }
            link.startsWith("vless://", true) -> runCatching { VlessConfigBuilder.fromShareLink(link) }
                .onSuccess { connectConfig(it.configJson, displayName.ifBlank { it.name }) }
                .onFailure { VpnRuntime.update(VpnStatus.ERROR, displayName, it.message ?: "لینک VLESS معتبر نیست.") }
            link.startsWith("vmess://", true) -> runCatching { VMessConfigBuilder.fromShareLink(link) }
                .onSuccess { connectConfig(it.configJson, displayName.ifBlank { it.name }) }
                .onFailure { VpnRuntime.update(VpnStatus.ERROR, displayName, it.message ?: "لینک VMess معتبر نیست.") }
            link.startsWith("ss://", true) -> runCatching { ShadowsocksConfigBuilder.fromShareLink(link) }
                .onSuccess { connectConfig(it.configJson, displayName.ifBlank { it.name }) }
                .onFailure { VpnRuntime.update(VpnStatus.ERROR, displayName, it.message ?: "لینک Shadowsocks معتبر نیست.") }
            link.startsWith("trojan://", true) -> runCatching { TrojanConfigBuilder.fromShareLink(link) }
                .onSuccess { connectConfig(it.configJson, displayName.ifBlank { it.name }) }
                .onFailure { VpnRuntime.update(VpnStatus.ERROR, displayName, it.message ?: "لینک Trojan معتبر نیست.") }
            else -> VpnRuntime.update(VpnStatus.ERROR, displayName, "پروتکل این لینک پشتیبانی نمی‌شود.")
        }
    }

    private fun connectConfig(config: String, name: String) {
        pendingConfig = config
        pendingName = name
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) vpnPermission.launch(permissionIntent) else startPendingVpn()
    }

    private fun startPendingVpn() {
        val config = pendingConfig ?: return
        val intent = Intent(this, BluFoxVpnService::class.java).apply {
            action = BluFoxVpnService.ACTION_START
            putExtra(BluFoxVpnService.EXTRA_CONFIG, config)
            putExtra(BluFoxVpnService.EXTRA_NAME, pendingName)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun disconnect() {
        startService(Intent(this, BluFoxVpnService::class.java).setAction(BluFoxVpnService.ACTION_STOP))
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}

@Composable
private fun BluFoxRoot(
    vpnState: VpnUiState,
    onConnectLink: (String, String) -> Unit,
    onConnectConfig: (String, String) -> Unit,
    onDisconnect: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = BElectric, secondary = BCyan, background = BNight, surface = BPanel,
        onPrimary = Color.White, onBackground = Color(0xFFF2F7FF), onSurface = Color(0xFFF2F7FF)
    )) {
        Scaffold(
            containerColor = BNight,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0B192A)) {
                    listOf(
                        Triple("خانه", Icons.Filled.Home, 0),
                        Triple("سرورها", Icons.Filled.Public, 1),
                        Triple("ابزارها", Icons.Filled.Speed, 2),
                        Triple("تنظیمات", Icons.Filled.Tune, 3)
                    ).forEach { (label, icon, index) ->
                        NavigationBarItem(
                            selected = tab == index, onClick = { tab = index },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = BCyan, selectedTextColor = BCyan,
                                indicatorColor = BElectric.copy(alpha = .16f),
                                unselectedIconColor = BMuted, unselectedTextColor = BMuted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> BluFoxHome(vpnState, { tab = 1 }, onDisconnect)
                    1 -> ServerManagerScreen(onConnectLink, onConnectConfig, vpnState)
                    2 -> SimpleInfoScreen("ابزارها", "آزمون پینگ و سرعت پس از تثبیت مدیریت سرورها اضافه می‌شود.")
                    else -> SimpleInfoScreen("تنظیمات", "سرورها و لینک‌ها فقط در حافظه داخلی همین گوشی ذخیره می‌شوند.")
                }
            }
        }
    }
}

@Composable
private fun BluFoxHome(state: VpnUiState, onServers: () -> Unit, onDisconnect: () -> Unit) {
    val color = when (state.status) {
        VpnStatus.CONNECTED -> BGreen
        VpnStatus.CONNECTING -> BAmber
        VpnStatus.ERROR -> BRed
        VpnStatus.DISCONNECTED -> BMuted
    }
    val label = when (state.status) {
        VpnStatus.CONNECTED -> "متصل؛ عبور اینترنت تأیید شد"
        VpnStatus.CONNECTING -> "در حال اتصال"
        VpnStatus.ERROR -> "خطا در اتصال"
        VpnStatus.DISCONNECTED -> "قطع"
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(BElectric, Color(0xFF174A87)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("BLUFOX", fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("اتصال آزاد، کنترل هوشمند", color = BMuted, fontSize = 12.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = BPanel), shape = RoundedCornerShape(26.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("وضعیت اتصال", color = BMuted)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(8.dp))
                    Text(label, fontWeight = FontWeight.Bold)
                }
                if (state.serverName.isNotBlank()) Text(state.serverName, color = BMuted, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                if (state.status == VpnStatus.CONNECTED) {
                    Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB33E56))) {
                        Text("قطع اتصال", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(onClick = onServers, modifier = Modifier.fillMaxWidth().height(54.dp), enabled = state.status != VpnStatus.CONNECTING) {
                        Text(if (state.status == VpnStatus.CONNECTING) "در حال اتصال…" else "انتخاب سرور و اتصال", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (state.exitIp.isNotBlank()) Text("IP خروجی: ${state.exitIp}", color = BCyan, fontSize = 12.sp)
                if (state.message.isNotBlank()) Text(state.message, color = if (state.status == VpnStatus.ERROR) BRed else BMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = BPanel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("مدیریت سرورها", fontWeight = FontWeight.Bold)
                Text("افزودن، ویرایش، حذف و به‌روزرسانی سرورها از یک صفحه", color = BMuted, fontSize = 12.sp)
                TextButton(onClick = onServers) { Text("رفتن به فهرست سرورها") }
            }
        }
    }
}

@Composable
private fun ServerManagerScreen(
    onConnectLink: (String, String) -> Unit,
    onConnectConfig: (String, String) -> Unit,
    vpnState: VpnUiState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers = remember { mutableStateListOf<SavedServer>() }
    var selectedId by remember { mutableStateOf("") }
    var showEditor by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var serverName by remember { mutableStateOf("") }
    var serverLink by remember { mutableStateOf("") }
    var loadingGate by remember { mutableStateOf(false) }
    var showPublicServers by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }

    fun persist() = ServerStore.save(context, servers.toList())
    fun beginAdd() {
        editingId = null
        serverName = ""
        serverLink = ""
        showEditor = true
    }
    fun beginEdit(server: SavedServer) {
        editingId = server.id
        serverName = server.name
        serverLink = server.shareLink
        showEditor = true
    }
    fun saveManual() {
        val link = serverLink.trim()
        val name = serverName.trim()
        if (link.isBlank()) { error = "لینک سرور را وارد کن."; return }
        if (name.isBlank()) { error = "برای سرور یک نام انتخاب کن."; return }
        try {
            val protocol = when {
                link.startsWith("vless://", true) -> { VlessConfigBuilder.fromShareLink(link); "VLESS" }
                link.startsWith("vmess://", true) -> { VMessConfigBuilder.fromShareLink(link); "VMess" }
                link.startsWith("ss://", true) -> { ShadowsocksConfigBuilder.fromShareLink(link); "Shadowsocks" }
                link.startsWith("trojan://", true) -> { TrojanConfigBuilder.fromShareLink(link); "Trojan" }
                link.startsWith("ssconf://", true) -> "Outline Dynamic"
                else -> throw IllegalArgumentException("لینک باید VLESS، Shadowsocks، Trojan یا ssconf باشد.")
            }
            val index = servers.indexOfFirst { it.id == editingId }
            val record = SavedServer(
                id = editingId ?: UUID.randomUUID().toString(),
                name = name, protocol = protocol, shareLink = link, source = "manual"
            )
            if (index >= 0) servers[index] = record else servers.add(0, record)
            selectedId = record.id
            persist()
            showEditor = false
            error = ""
            info = if (index >= 0) "اطلاعات سرور به‌روزرسانی شد." else "سرور ذخیره شد."
        } catch (e: Exception) {
            error = e.message ?: "لینک سرور معتبر نیست."
        }
    }

    if (showPublicServers) {
        PublicServersScreen(
            onBack = { showPublicServers = false },
            onConnectLink = onConnectLink,
            onConnectConfig = onConnectConfig
        )
        return
    }

    LaunchedEffect(Unit) {
        servers.clear()
        servers.addAll(ServerStore.load(context))
        if (selectedId.isBlank()) selectedId = servers.firstOrNull()?.id.orEmpty()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("سرورهای من", fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text("${servers.size} سرور ذخیره‌شده روی گوشی", color = BMuted, fontSize = 12.sp)
            }
            TextButton(onClick = { showPublicServers = true }) { Text("عمومی", color = BCyan) }
            IconButton(onClick = ::beginAdd) { Icon(Icons.Outlined.Add, contentDescription = "افزودن", tint = BCyan) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::beginAdd, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(13.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(5.dp)); Text("افزودن سرور")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        loadingGate = true
                        error = ""; info = ""
                        try {
                            val fetched = withContext(Dispatchers.IO) { VpnGateRepository.fetchServers(40) }
                            var added = 0
                            fetched.forEach { server ->
                                val id = "vpngate:${server.ip}:${server.countryCode}"
                                val config = runCatching { VpnGateConfigBuilder.build(server) }.getOrNull() ?: return@forEach
                                val item = SavedServer(id, "${server.country} • ${server.ip}", "OpenVPN", server.country, server.ip, configJson = config, source = "VPN Gate")
                                val index = servers.indexOfFirst { it.id == id }
                                if (index >= 0) servers[index] = item else { servers.add(item); added++ }
                            }
                            persist()
                            if (servers.isNotEmpty() && selectedId.isBlank()) selectedId = servers.first().id
                            info = "فهرست VPN Gate به‌روزرسانی شد؛ $added سرور جدید اضافه شد."
                        } catch (e: Exception) {
                            error = e.message ?: "به‌روزرسانی سرورها ناموفق بود."
                        } finally { loadingGate = false }
                    }
                },
                enabled = !loadingGate,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(13.dp)
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(5.dp)); Text(if (loadingGate) "در حال دریافت…" else "به‌روزرسانی عمومی")
            }
        }
        Text("سرورهای عمومی شامل VPN Gate، WARP و فهرست‌های عمومی CloudFox هستند. دریافت هر منبع فقط با باز کردن همان گروه انجام می‌شود.", color = BMuted, fontSize = 11.sp)
        if (error.isNotBlank()) Text(error, color = BRed, fontSize = 12.sp)
        if (info.isNotBlank()) Text(info, color = BGreen, fontSize = 12.sp)
        if (servers.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = BPanel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Cloud, contentDescription = null, tint = BCyan, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("هنوز سروری نداری", fontWeight = FontWeight.Bold)
                    Text("یک لینک VLESS، Shadowsocks، Trojan یا Outline اضافه کن.", color = BMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(servers, key = { it.id }) { server ->
                val selected = selectedId == server.id
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { selectedId = server.id },
                    colors = CardDefaults.cardColors(containerColor = if (selected) BPanelSoft else BPanel),
                    border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) BCyan else Color(0xFF203750)),
                    shape = RoundedCornerShape(17.dp)
                ) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(server.name, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(listOf(server.protocol, server.country, server.address).filter { it.isNotBlank() }.joinToString(" • "), color = BMuted, fontSize = 11.sp)
                            }
                            if (selected) Text("انتخاب‌شده", color = BCyan, fontSize = 10.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { selectedId = server.id }) { Text("انتخاب") }
                            if (server.source == "manual") {
                                TextButton(onClick = { beginEdit(server) }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text("ویرایش")
                                }
                            }
                            TextButton(onClick = {
                                servers.removeAll { it.id == server.id }
                                if (selectedId == server.id) selectedId = servers.firstOrNull()?.id.orEmpty()
                                persist()
                                info = "سرور حذف شد."
                            }) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, tint = BRed, modifier = Modifier.size(16.dp))
                                Text("حذف", color = BRed)
                            }
                        }
                    }
                }
            }
        }
        val selectedServer = servers.firstOrNull { it.id == selectedId }
        Button(
            onClick = {
                selectedServer?.let { server ->
                    if (server.configJson.isNotBlank()) onConnectConfig(server.configJson, server.name)
                    else onConnectLink(server.shareLink, server.name)
                }
            },
            enabled = selectedServer != null && vpnState.status != VpnStatus.CONNECTING && vpnState.status != VpnStatus.CONNECTED,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(15.dp)
        ) {
            Text(if (vpnState.status == VpnStatus.CONNECTED) "ابتدا اتصال فعلی را قطع کن" else "اتصال به سرور انتخاب‌شده", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        if (vpnState.status == VpnStatus.CONNECTED) Text("اتصال فعال: ${vpnState.serverName} — برای تغییر سرور ابتدا از صفحه خانه قطع کن.", color = BGreen, fontSize = 11.sp)
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            containerColor = BPanel,
            title = { Text(if (editingId == null) "افزودن سرور" else "ویرایش سرور") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = serverName, onValueChange = { serverName = it }, label = { Text("نام دلخواه سرور") }, singleLine = true)
                    OutlinedTextField(value = serverLink, onValueChange = { serverLink = it }, label = { Text("لینک سرور") }, minLines = 3, maxLines = 5)
                    Text("پشتیبانی: VLESS، VMess، Shadowsocks، Trojan و ssconf پویا. لینک‌ها فقط روی گوشی ذخیره می‌شوند.", color = BMuted, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = ::saveManual) { Text("ذخیره") } },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("انصراف") } }
        )
    }
}

@Composable
private fun SimpleInfoScreen(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 25.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = BMuted, fontSize = 13.sp)
    }
}
