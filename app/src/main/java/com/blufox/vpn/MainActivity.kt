package com.blufox.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Night = Color(0xFF07111F)
private val Panel = Color(0xFF101F33)
private val PanelSoft = Color(0xFF142842)
private val Electric = Color(0xFF398BFF)
private val Cyan = Color(0xFF35D6E8)
private val Muted = Color(0xFF91A4BC)
private val Green = Color(0xFF25C58A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BluFoxApp() }
    }
}

@Composable
private fun BluFoxApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showEngineInfo by remember { mutableStateOf(false) }

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
                        Triple("تنظیمات", Icons.Outlined.Settings, 3)
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (selectedTab) {
                    0 -> HomeScreen(onConnect = { showEngineInfo = true }, onServers = { selectedTab = 1 })
                    1 -> ServersScreen()
                    2 -> ToolsScreen()
                    else -> SettingsScreen()
                }
            }
        }

        if (showEngineInfo) {
            AlertDialog(
                onDismissRequest = { showEngineInfo = false },
                containerColor = Panel,
                title = { Text("موتور اتصال هنوز اضافه نشده") },
                text = {
                    Text(
                        "این نسخه، اسکلت اولیه و رابط کاربری BluFox است. اتصال واقعی VPN در مرحله بعد و پس از اضافه‌شدن موتور اتصال پیاده‌سازی و روی گوشی آزمایش می‌شود."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showEngineInfo = false }) { Text("متوجه شدم") }
                }
            )
        }
    }
}

@Composable
private fun HomeScreen(onConnect: () -> Unit, onServers: () -> Unit) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF142F53), Color(0xFF10243B), Color(0xFF101B2D))
                        )
                    )
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("وضعیت اتصال", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFFFB454)))
                    Spacer(Modifier.width(8.dp))
                    Text("آماده‌به‌کار", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth().height(66.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Electric)
                ) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(25.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("اتصال", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text("موتور VPN در مرحله توسعه است", color = Muted, fontSize = 11.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("سرور سالم", "—", Icons.Outlined.CheckCircle, Modifier.weight(1f))
                MetricCard("پینگ", "—", Icons.Outlined.Speed, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("آپلود", "۰ KB/s", Icons.Outlined.ArrowUpward, Modifier.weight(1f))
                MetricCard("دانلود", "۰ KB/s", Icons.Outlined.ArrowDownward, Modifier.weight(1f))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("سرورهای پیشنهادی", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("پس از اتصال منبع سرورها نمایش داده می‌شود", color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = onServers) { Text("همه سرورها") }
            }
            EmptyServerCard()
        }
    }
}

@Composable
private fun OrbitMark() {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Electric, Color(0xFF174A87)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.Public, contentDescription = "Orbit", tint = Color.White, modifier = Modifier.size(29.dp))
    }
}

@Composable
private fun MetricCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Column(Modifier.padding(15.dp)) {
            Icon(icon, contentDescription = null, tint = Cyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(12.dp))
            Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(title, fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable
private fun EmptyServerCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF203750))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Cloud, contentDescription = null, tint = Muted, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("هنوز سروری بررسی نشده", fontWeight = FontWeight.SemiBold)
                Text("برای جلوگیری از نمایش اطلاعات ساختگی، فهرست فعلاً خالی است.", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ServersScreen() {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("سرورها", fontSize = 25.sp, fontWeight = FontWeight.Black)
        Text("منابع و سرورهای قابل بررسی", color = Muted)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Dns, contentDescription = null, tint = Cyan, modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(12.dp))
                Text("منبع سرورها هنوز متصل نشده", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "در مرحله بعد، دریافت دستی فهرست سرورها و آزمایش واقعی دسترس‌پذیری اضافه می‌شود.",
                    color = Muted, textAlign = TextAlign.Center, fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ToolsScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("ابزارها", fontSize = 25.sp, fontWeight = FontWeight.Black)
        ToolRow(Icons.Outlined.Search, "آزمایش سرور", "در مرحله بعد فعال می‌شود")
        ToolRow(Icons.Outlined.Speed, "آزمون سرعت", "پس از ایجاد تونل واقعی")
        ToolRow(Icons.Outlined.Lock, "بررسی امنیت", "اطلاعات امنیتی پس از اتصال موتور")
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
        ToolRow(Icons.Outlined.Tune, "تنظیمات اتصال", "پس از افزودن موتور VPN")
        ToolRow(Icons.Outlined.Shield, "حریم خصوصی", "سرورهای عمومی ممکن است قابل اعتماد نباشند")
        ToolRow(Icons.Outlined.Settings, "درباره BluFox", "نسخه آزمایشی 0.1.0")
        Spacer(Modifier.height(4.dp))
        Text("BluFox VPN", color = Cyan, fontWeight = FontWeight.Bold)
        Text("ساخته‌شده به‌صورت مستقل؛ بدون تغییر در CloudFox", color = Muted, fontSize = 12.sp)
    }
}
