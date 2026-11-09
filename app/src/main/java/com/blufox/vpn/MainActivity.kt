package com.blufox.vpn

import android.content.Intent
import android.text.format.DateFormat
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

private val Night = Color(0xFF07111F)
private val Panel = Color(0xFF101F33)
private val PanelSoft = Color(0xFF142842)
private val Electric = Color(0xFF398BFF)
private val Cyan = Color(0xFF35D6E8)
private val Muted = Color(0xFF91A4BC)
private val Green = Color(0xFF25C58A)
private val Amber = Color(0xFFFFB454)
private val Red = Color(0xFFFF667A)

class MainActivity : ComponentActivity() {
    private var pendingConfigJson: String? = null
    private var pendingServerName = ""

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startPendingVpn()
            } else {
                VpnRuntime.update(VpnStatus.ERROR, message = "اجازه ایجاد VPN داده نشد.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vpnState by VpnRuntime.state.collectAsState()
            BluFoxApp(
                vpnState = vpnState,
                onConnect = ::prepareConnection,
                onDisconnect = ::disconnect,
                onConnectConfig = ::prepareConfig
            )
        }
    }

    private fun prepareConnection(link: String) {
        try {
            val profile = VlessConfigBuilder.fromShareLink(link)
            prepareConfig(profile.configJson, profile.name)
        } catch (error: Exception) {
            VpnRuntime.update(VpnStatus.ERROR, message = error.message ?: "لینک سرور قابل خواندن نیست.")
        }
    }

    private fun prepareConfig(configJson: String, name: String) {
        runCatching { ProfileStore.save(this, name.ifBlank { "سرور بدون نام" }, configJson) }
        pendingConfigJson = configJson
        pendingServerName = name
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            vpnPermissionLauncher.launch(permissionIntent)
        } else {
            startPendingVpn()
        }
    }

    private fun startPendingVpn() {
        val config = pendingConfigJson ?: return
        val intent = Intent(this, BluFoxVpnService::class.java).apply {
            action = BluFoxVpnService.ACTION_START
            putExtra(BluFoxVpnService.EXTRA_CONFIG, config)
            putExtra(BluFoxVpnService.EXTRA_NAME, pendingServerName)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun disconnect() {
        startService(Intent(this, BluFoxVpnService::class.java).setAction(BluFoxVpnService.ACTION_STOP))
    }
}

@Composable
private fun BluFoxApp(
    vpnState: VpnUiState,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onConnectConfig: (String, String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var configLink by remember { mutableStateOf("") }
    val context = LocalContext.current
    val savedProfiles = remember { mutableStateListOf<SavedVpnProfile>().apply { addAll(ProfileStore.load(context)) } }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Electric,
            secondary = Cyan,
            background = Night,
            surface = Panel,
            onPrimary = Color.White,
            onBackground = Color(0xFFF2F7FF),
            onSurface = Color(0xFFF2F7FF)
        )
    ) {
        Scaffold(
            containerColor = Night,
            bottomBar = {
                NavigationBar(
                    containerColor = Color(0xFF0B192A),
                    contentColor = Color.White,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    val tabs = listOf(
                        Triple("خانه", Icons.Filled.Home, 0),
                        Triple("سرورها", Icons.Outlined.Public, 1),
                        Triple("ابزارها", Icons.Filled.Speed, 2),
                        Triple("تنظیمات", Icons.Filled.Tune, 3)
                    )
                    tabs.forEach { (label, icon, index) ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Cyan,
                                selectedTextColor = Cyan,
                                indicatorColor = Electric.copy(alpha = 0.16f),
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (selectedTab) {
                    0 -> HomeScreen(
                        vpnState = vpnState,
                        savedProfileCount = savedProfiles.size,
                        onConnect = { showConfigDialog = true },
                        onDisconnect = onDisconnect,
                        onServers = { selectedTab = 1 }
                    )
                    1 -> ServersScreen(
                        onConnect = { config, name ->
                            onConnectConfig(config, name)
                            savedProfiles.clear()
                            savedProfiles.addAll(ProfileStore.load(context))
                        },
                        onSaveProfile = { config, name ->
                            ProfileStore.save(context, name, config)
                            savedProfiles.clear()
                            savedProfiles.addAll(ProfileStore.load(context))
                        },
                        savedProfiles = savedProfiles,
                        onSavedConnect = { onConnectConfig(it.configJson, it.name) },
                        onDeleteSaved = { profile ->
                            ProfileStore.delete(context, profile.id)
                            savedProfiles.removeAll { it.id == profile.id }
                        }
                    )
                    2 -> ToolsScreen()
                    else -> SettingsScreen()
                }
            }
        }

        if (showConfigDialog) {
            AlertDialog(
                onDismissRequest = { showConfigDialog = false },
                containerColor = Panel,
                title = { Text("افزودن سرور VLESS") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("لینک سرور VLESS را اینجا جای‌گذاری کن. اطلاعات سرور روی گوشی پردازش می‌شود.")
                        OutlinedTextField(
                            value = configLink,
                            onValueChange = { configLink = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("لینک vless://") },
                            minLines = 3,
                            maxLines = 5
                        )
                        Text(
                            "فعلاً VLESS با TLS یا REALITY و انتقال TCP، WebSocket یا gRPC پشتیبانی می‌شود.",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = configLink.trim().startsWith("vless://", true),
                        onClick = {
                            showConfigDialog = false
                            onConnect(configLink)
                            savedProfiles.clear()
                            savedProfiles.addAll(ProfileStore.load(context))
                            configLink = ""
                        }
                    ) { Text("اتصال") }
                },
                dismissButton = {
                    TextButton(onClick = { showConfigDialog = false }) { Text("انصراف") }
                }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    vpnState: VpnUiState,
    savedProfileCount: Int,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onServers: () -> Unit
) {
    val connected = vpnState.status == VpnStatus.CONNECTED
    val connecting = vpnState.status == VpnStatus.CONNECTING
    val statusColor = when (vpnState.status) {
        VpnStatus.CONNECTED -> Green
        VpnStatus.CONNECTING -> Amber
        VpnStatus.ERROR -> Red
        VpnStatus.DISCONNECTED -> Muted
    }
    val statusText = when (vpnState.status) {
        VpnStatus.CONNECTED -> "متصل و ترافیک تأییدشده"
        VpnStatus.CONNECTING -> "در حال اتصال"
        VpnStatus.ERROR -> "خطا در اتصال"
        VpnStatus.DISCONNECTED -> "قطع"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 22.dp, bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrbitMark()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("BLUFOX", fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Text("اتصال آزاد، کنترل هوشمند", color = Muted, fontSize = 12.sp)
                }
                Surface(shape = CircleShape, color = PanelSoft) {
                    Icon(Icons.Outlined.Shield, contentDescription = "امنیت", tint = Cyan, modifier = Modifier.padding(11.dp).size(21.dp))
                }
            }
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF142F53), Color(0xFF10243B), Color(0xFF101B2D))))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("وضعیت اتصال", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(8.dp))
                    Text(statusText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                if (vpnState.serverName.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(vpnState.serverName, color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = if (connected) onDisconnect else onConnect,
                    enabled = !connecting,
                    modifier = Modifier.fillMaxWidth().height(66.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (connected) Color(0xFFB33E56) else Electric)
                ) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(25.dp))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        when {
                            connected -> "قطع اتصال"
                            connecting -> "در حال اتصال…"
                            else -> "افزودن سرور و اتصال"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (vpnState.message.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        vpnState.message,
                        color = if (vpnState.status == VpnStatus.ERROR) Red else Muted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("IP خروجی VPN", vpnState.exitIp.ifBlank { "—" }, Icons.Outlined.Public, Modifier.weight(1f))
                MetricCard("پینگ", "—", Icons.Outlined.CheckCircle, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("آپلود", "پس از افزودن آمار", Icons.Outlined.ArrowUpward, Modifier.weight(1f))
                MetricCard("دانلود", "پس از افزودن آمار", Icons.Outlined.ArrowDownward, Modifier.weight(1f))
            }
        }
        item { EngineStatusCard(savedProfileCount, vpnState) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("سرورهای پیشنهادی", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("فهرست Secure/Verified؛ اعتبار هر سرور جداگانه نمایش داده می‌شود", color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = onServers) { Text("همه سرورها") }
            }
            ServerSourceCard(onServers)
        }
    }
}


@Composable
private fun EngineStatusCard(savedProfileCount: Int, vpnState: VpnUiState) {
    val engineStatus = when (vpnState.status) {
        VpnStatus.CONNECTED -> "تونل تأییدشده"
        VpnStatus.CONNECTING -> "در حال راه‌اندازی"
        VpnStatus.ERROR -> "خطا در موتور"
        VpnStatus.DISCONNECTED -> "آماده"
    }
    val engineColor = when (vpnState.status) {
        VpnStatus.CONNECTED -> Green
        VpnStatus.CONNECTING -> Amber
        VpnStatus.ERROR -> Red
        VpnStatus.DISCONNECTED -> Cyan
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF203750))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = Cyan, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(9.dp))
                Text("موتور اتصال", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Text(engineStatus, color = engineColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text("sing-box • تنها موتور متصل به Android VpnService", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("WARP و Psiphon تا زمان ادغام تونل واقعی و تأیید عبور ترافیک غیرفعال هستند.", color = Muted, fontSize = 11.sp)
            Text("پروفایل‌های ذخیره‌شده: ${savedProfileCount}", color = Cyan, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OrbitMark() {
    Box(
        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Electric, Color(0xFF174A87)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.Public, contentDescription = "Orbit", tint = Color.White, modifier = Modifier.size(29.dp))
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(15.dp)) {
            Icon(icon, contentDescription = null, tint = Cyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(12.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(title, fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable
private fun ServerSourceCard(onServers: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF203750))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Cloud, contentDescription = null, tint = Cyan, modifier = Modifier.size(25.dp))
                Spacer(Modifier.width(10.dp))
                Text("فهرست سرورهای رایگان", fontWeight = FontWeight.SemiBold)
            }
            Text(
                "منبع Secure/Verified هر کانفیگ را در سه دور درخواست HTTP واقعی می‌آزماید و تنظیمات ضعیفِ غیرفعال‌کننده اعتبارسنجی گواهی را حذف می‌کند. نتیجه مربوط به شبکه آزمایش منبع است؛ دسترسی از اینترنت تو جداگانه سنجیده می‌شود.",
                color = Muted,
                fontSize = 11.sp
            )
            Button(
                onClick = onServers,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text("نمایش و جست‌وجوی سرورها") }
        }
    }
}

@Composable
private fun ServersScreen(
    onConnect: (String, String) -> Unit,
    onSaveProfile: (String, String) -> Unit,
    savedProfiles: List<SavedVpnProfile>,
    onSavedConnect: (SavedVpnProfile) -> Unit,
    onDeleteSaved: (SavedVpnProfile) -> Unit
) {
    val scope = rememberCoroutineScope()
    val servers = remember { mutableStateListOf<FreeProxyServer>() }
    val pingResults = remember { mutableStateListOf<ServerPingResult>() }
    var loading by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var successMessage by remember { mutableStateOf("") }
    var sourceUpdatedAt by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var selectedServerTag by remember { mutableStateOf("") }

    suspend fun refreshServers() {
        loading = true
        errorMessage = ""
        successMessage = ""
        try {
            val found = FreeProxyRepository.fetchServers(100)
            servers.clear()
            servers.addAll(found)
            pingResults.clear()
            sourceUpdatedAt = FreeProxyRepository.lastUpdatedAt
            if (found.none { it.tag == selectedServerTag }) selectedServerTag = ""
        } catch (error: Exception) {
            // Never leave a previously fetched, now potentially stale list connectable after refresh fails.
            servers.clear()
            pingResults.clear()
            sourceUpdatedAt = ""
            selectedServerTag = ""
            errorMessage = error.message ?: "دریافت فهرست سرورها ناموفق بود."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (servers.isEmpty() && !loading) refreshServers()
    }

    val pingByTag = pingResults.associateBy { it.server.tag }
    val displayedServers = if (pingResults.isNotEmpty()) {
        pingResults.map { it.server }
    } else {
        servers.toList()
    }
    val filteredServers = displayedServers.filter { server ->
        searchQuery.isBlank() ||
            server.tag.contains(searchQuery, ignoreCase = true) ||
            server.host.contains(searchQuery, ignoreCase = true) ||
            server.type.contains(searchQuery, ignoreCase = true) ||
            server.countryCode.contains(searchQuery, ignoreCase = true)
    }
    val reachableCount = pingResults.count { it.reachable }
    val selectedServerName = servers.firstOrNull { it.tag == selectedServerTag }?.displayName

    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("سرورها", fontSize = 25.sp, fontWeight = FontWeight.Black)
        Text(
            "منبع: 0xRadikal • فقط دسته Secure سازگار با sing-box؛ هر سرور پس از سه آزمون HTTP منبع فهرست می‌شود.",
            color = Muted,
            fontSize = 11.sp
        )
        if (sourceUpdatedAt.isNotBlank()) {
            Text(
                "آخرین به‌روزرسانی منبع: ${sourceUpdatedAt} UTC • نمایش ${servers.size} مورد از ${FreeProxyRepository.secureCount} سرور Secure/Verified",
                color = Cyan,
                fontSize = 10.sp
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("جست‌وجوی نام، کشور، نوع یا IP") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) }
        )

        if (selectedServerName != null) {
            Text("سرور انتخاب‌شده: ${selectedServerName}", color = Cyan, fontSize = 11.sp)
        }

        if (savedProfiles.isNotEmpty()) {
            Text("پروفایل‌های ذخیره‌شده (${savedProfiles.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            savedProfiles.forEach { profile ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, fontWeight = FontWeight.Bold)
                            Text(profile.engineLabel, color = Muted, fontSize = 10.sp)
                        }
                        TextButton(onClick = { onSavedConnect(profile) }) { Text("اتصال") }
                        TextButton(onClick = { onDeleteSaved(profile) }) { Text("حذف", color = Red) }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { scope.launch { refreshServers() } },
                enabled = !loading && !scanning,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (loading) "دریافت…" else "به‌روزرسانی سرورها", fontSize = 12.sp)
            }
            Button(
                onClick = {
                    scope.launch {
                        scanning = true
                        errorMessage = ""
                        pingResults.clear()
                        try {
                            val results = FreeProxyRepository.pingAll(servers.toList())
                            pingResults.addAll(results)
                        } catch (error: Exception) {
                            errorMessage = error.message ?: "آزمایش پینگ انجام نشد."
                        } finally {
                            scanning = false
                        }
                    }
                },
                enabled = servers.isNotEmpty() && !loading && !scanning,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF176C64))
            ) {
                Text(if (scanning) "در حال پینگ…" else "پینگ همه سرورها", fontSize = 12.sp)
            }
        }

        if (scanning) {
            Text(
                "در حال بررسی دسترسی TCP همه ${servers.size} سرور؛ حداکثر ۱۰ تست هم‌زمان.",
                color = Amber,
                fontSize = 11.sp
            )
        }
        if (pingResults.isNotEmpty()) {
            Text(
                "قابل دسترس از این اینترنت: ${reachableCount} از ${pingResults.size} • مرتب‌شده از کمترین تأخیر TCP",
                color = if (reachableCount > 0) Green else Red,
                fontSize = 11.sp
            )
        }
        if (errorMessage.isNotBlank()) {
            Text(errorMessage, color = Red, fontSize = 11.sp)
        }
        if (successMessage.isNotBlank()) {
            Text(successMessage, color = Green, fontSize = 11.sp)
        }
        Text(
            "اعتبارسنجی Secure/Verified مربوط به آزمون سه‌مرحله‌ای منبع و فیلتر امنیتی گواهی است. پینگ این صفحه فقط اتصال TCP به پورت سرور را می‌سنجد؛ اتصال VPN فقط پس از عبور واقعی ترافیک تأیید می‌شود.",
            color = Muted,
            fontSize = 10.sp
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)
        ) {
            if (filteredServers.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(17.dp)
                    ) {
                        Text(
                            when {
                                loading -> "در حال دریافت فهرست تازه سرورها…"
                                searchQuery.isNotBlank() -> "سروری با این عبارت پیدا نشد."
                                errorMessage.isNotBlank() -> "فهرست فعلاً در دسترس نیست؛ سرور قدیمی برای اتصال نگه داشته نمی‌شود."
                                else -> "در منبع فعلی سرور سازگار و تازه‌ای پیدا نشد."
                            },
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            color = Muted,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            items(filteredServers, key = { it.tag }) { server ->
                val result = pingByTag[server.tag]
                val selected = selectedServerTag == server.tag
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(17.dp),
                    border = BorderStroke(
                        1.dp,
                        when {
                            selected -> Cyan
                            result?.reachable == true -> Green.copy(alpha = 0.45f)
                            else -> Color(0xFF203750)
                        }
                    )
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(13.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${server.flagEmoji.ifBlank { "🌐" }} ${server.countryCode.ifBlank { "مکان نامشخص" }}",
                                color = Cyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "Secure • Verified ${server.validationRounds}/3 HTTP",
                                color = Green,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(server.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2)
                                Text(
                                    "${server.type.uppercase()} • ${server.host}:${server.port}",
                                    color = Muted,
                                    fontSize = 10.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                when {
                                    result == null -> Text("تست نشده", color = Muted, fontSize = 10.sp)
                                    result.reachable -> {
                                        Text("${result.latencyMs} ms", color = Green, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("TCP در دسترس", color = Green, fontSize = 9.sp)
                                    }
                                    else -> {
                                        Text("ناموفق", color = Red, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        Text("TCP ناموفق", color = Red, fontSize = 9.sp)
                                    }
                                }
                            }
                        }
                        Text(
                            "منبع: ${server.sourceLabel} • آخرین به‌روزرسانی: ${server.upstreamUpdatedAt} UTC",
                            color = Muted,
                            fontSize = 9.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(
                                onClick = { selectedServerTag = server.tag },
                                modifier = Modifier.weight(0.9f)
                            ) {
                                Text(if (selected) "انتخاب شد" else "انتخاب", fontSize = 11.sp)
                            }
                            TextButton(
                                onClick = {
                                    try {
                                        onSaveProfile(FreeProxyRepository.buildSingleServerConfig(server), server.tag)
                                        errorMessage = ""
                                        successMessage = "سرور به پروفایل‌های ذخیره‌شده اضافه شد."
                                    } catch (error: Exception) {
                                        successMessage = ""
                                        errorMessage = error.message ?: "ذخیره سرور ناموفق بود."
                                    }
                                },
                                modifier = Modifier.weight(0.9f)
                            ) { Text("افزودن", fontSize = 11.sp) }
                            Button(
                                onClick = {
                                    selectedServerTag = server.tag
                                    try {
                                        onConnect(FreeProxyRepository.buildSingleServerConfig(server), server.tag)
                                    } catch (error: Exception) {
                                        errorMessage = error.message ?: "ساخت تنظیمات سرور ناموفق بود."
                                    }
                                },
                                modifier = Modifier.weight(1.2f).height(40.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("اتصال", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("ابزارها", fontSize = 25.sp, fontWeight = FontWeight.Black)
        ToolRow(Icons.Outlined.Search, "پینگ همه سرورها", "در صفحه سرورها؛ تست TCP و مرتب‌سازی بر اساس تأخیر")
        ToolRow(Icons.Filled.Speed, "آزمون سرعت", "پس از اتصال واقعی تونل قابل انجام است")
        ToolRow(Icons.Outlined.Lock, "بررسی امنیت", "وضعیت TLS و REALITY از لینک سرور")
    }
}

@Composable
private fun ToolRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Cyan, modifier = Modifier.size(25.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("تنظیمات", fontSize = 25.sp, fontWeight = FontWeight.Black)
        ToolRow(Icons.Filled.Tune, "تنظیمات اتصال", "تنظیمات موتور sing-box")
        ToolRow(Icons.Outlined.Shield, "حریم خصوصی", "سرورهای عمومی ممکن است قابل اعتماد نباشند")
        ToolRow(Icons.Outlined.Settings, "درباره BluFox", "نسخه آزمایشی 0.3.0")
        Spacer(Modifier.height(4.dp))
        Text("BluFox VPN", color = Cyan, fontWeight = FontWeight.Bold)
        Text("نسخه مستقل؛ CloudFox بدون تغییر باقی مانده است.", color = Muted, fontSize = 12.sp)
    }
}
