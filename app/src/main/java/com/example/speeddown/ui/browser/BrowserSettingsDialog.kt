package com.example.speeddown.ui.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speeddown.data.BrowserSettings
import com.example.speeddown.engine.SecureDnsHelper
import kotlinx.coroutines.launch

private val Purple = Color(0xFF7C3AED)
private val Green = Color(0xFF16A34A)
private val Blue = Color(0xFF2563EB)
private val Amber = Color(0xFFF59E0B)
private val Red = Color(0xFFDC2626)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSettingsDialog(
    currentSettings: BrowserSettings,
    onSaveSettings: (BrowserSettings) -> Unit,
    onClearData: (clearCache: Boolean, clearCookies: Boolean, clearHistory: Boolean, clearStorage: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var settingsState by remember { mutableStateOf(currentSettings) }
    var dnsLatencyText by remember { mutableStateOf<String?>(null) }
    var isTestingDns by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = {
            onSaveSettings(settingsState)
            onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Purple.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Tune, null, tint = Purple, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Browser Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("DNS, Privacy & Surfing Engine", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = {
                    onSaveSettings(settingsState)
                    onDismiss()
                }) {
                    Text("Done", fontWeight = FontWeight.Bold, color = Purple)
                }
            }

            Spacer(Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ─── 1. DNS & ANTI-CENSORSHIP ────────────────────────────────
                item {
                    BrowserSettingCard(
                        title = "Secure DNS & Anti-Censorship",
                        subtitle = "Bypasses ISP blocks on streaming websites and accelerates lookups",
                        icon = Icons.Filled.Dns,
                        iconTint = Blue
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // DNS Presets
                            val providers = listOf("Cloudflare", "Google", "AdGuard", "Quad9", "System")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                providers.forEach { prov ->
                                    val isSel = settingsState.dnsProvider == prov
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSel) Purple else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .clickable {
                                                settingsState = settingsState.copy(dnsProvider = prov)
                                                dnsLatencyText = null
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                prov,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            // DNS Details & Speed Test
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when (settingsState.dnsProvider) {
                                        "Cloudflare" -> "1.1.1.1 (Cloudflare Ultra-Fast)"
                                        "Google" -> "8.8.8.8 (Google Public DNS)"
                                        "AdGuard" -> "94.140.14.14 (AdGuard Anti-Ad DNS)"
                                        "Quad9" -> "9.9.9.9 (Quad9 Cybersecurity DNS)"
                                        else -> "Default ISP Network DNS"
                                    },
                                    fontSize = 11.sp,
                                    color = Purple,
                                    fontWeight = FontWeight.SemiBold
                                )

                                OutlinedButton(
                                    onClick = {
                                        isTestingDns = true
                                        coroutineScope.launch {
                                            val (ok, ms) = SecureDnsHelper.testDnsLatency(settingsState.dnsProvider)
                                            isTestingDns = false
                                            dnsLatencyText = if (ok) "⚡ $ms ms (Active)" else "Failed"
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(
                                        text = if (isTestingDns) "Testing..." else (dnsLatencyText ?: "Test Ping"),
                                        fontSize = 10.sp,
                                        color = if (dnsLatencyText?.contains("ms") == true) Green else Purple,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // ─── 2. DEFAULT SEARCH ENGINE ────────────────────────────────
                item {
                    BrowserSettingCard(
                        title = "Default Search Engine",
                        subtitle = "Engine used when typing queries in address bar",
                        icon = Icons.Filled.Search,
                        iconTint = Purple
                    ) {
                        val engines = listOf("Google", "DuckDuckGo", "Brave", "Bing", "Yandex")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            engines.forEach { eng ->
                                val isSel = settingsState.searchEngine == eng
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSel) Purple else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clickable {
                                            settingsState = settingsState.copy(searchEngine = eng)
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            eng,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ─── 3. PRIVACY & SECURITY ───────────────────────────────────
                item {
                    BrowserSettingCard(
                        title = "Privacy & Security",
                        subtitle = "Cookie management, tracking protection, and site encryption",
                        icon = Icons.Filled.Security,
                        iconTint = Green
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // HTTPS-Only Mode
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("HTTPS-Only Mode", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Automatically upgrade insecure HTTP requests to HTTPS", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = settingsState.httpsOnly,
                                    onCheckedChange = { settingsState = settingsState.copy(httpsOnly = it) }
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            // Do Not Track / Global Privacy Control
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Send 'Do Not Track' (DNT)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Signals websites not to track your browsing session", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = settingsState.doNotTrack,
                                    onCheckedChange = { settingsState = settingsState.copy(doNotTrack = it) }
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            // Third-Party Cookies
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Third-Party Cookies", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Required for video streaming players (Streamwish, Turbovid, Doodstream)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = settingsState.acceptThirdPartyCookies,
                                    onCheckedChange = { settingsState = settingsState.copy(acceptThirdPartyCookies = it) }
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            // Clear Browsing Data Button
                            OutlinedButton(
                                onClick = { showClearDataDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Filled.CleaningServices, null, tint = Red, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Clear Browsing Data (Cache, Cookies, History)", color = Red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // ─── 4. DISPLAY & SURFING EXPERIENCE ─────────────────────────
                item {
                    BrowserSettingCard(
                        title = "Display & Surfing Experience",
                        subtitle = "Font scaling, night reading, and desktop rendering",
                        icon = Icons.Filled.Web,
                        iconTint = Purple
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Force Dark Mode
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Night Reading (Dark Web Pages)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Themes bright web layouts to dark for night browsing", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = settingsState.forceDarkMode,
                                    onCheckedChange = { settingsState = settingsState.copy(forceDarkMode = it) }
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            // Default Desktop Mode
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Always Open as Desktop Site", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Use desktop user-agent by default for all new tabs", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = settingsState.defaultDesktopMode,
                                    onCheckedChange = { settingsState = settingsState.copy(defaultDesktopMode = it) }
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            // Text Zoom Slider
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Text Scaling", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("${settingsState.textZoom}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Purple)
                                }
                                Slider(
                                    value = settingsState.textZoom.toFloat(),
                                    onValueChange = { settingsState = settingsState.copy(textZoom = it.toInt()) },
                                    valueRange = 70f..150f,
                                    steps = 7
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // Clear Data Confirmation Dialog
    if (showClearDataDialog) {
        var clearCache by remember { mutableStateOf(true) }
        var clearCookies by remember { mutableStateOf(true) }
        var clearHistory by remember { mutableStateOf(true) }
        var clearStorage by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            icon = { Icon(Icons.Filled.DeleteSweep, null, tint = Red, modifier = Modifier.size(32.dp)) },
            title = { Text("Clear Browsing Data", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearCache, onCheckedChange = { clearCache = it })
                        Spacer(Modifier.width(6.dp))
                        Text("Cached images and files", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearCookies, onCheckedChange = { clearCookies = it })
                        Spacer(Modifier.width(6.dp))
                        Text("Cookies and site data", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearHistory, onCheckedChange = { clearHistory = it })
                        Spacer(Modifier.width(6.dp))
                        Text("Browsing history and tab sessions", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearStorage, onCheckedChange = { clearStorage = it })
                        Spacer(Modifier.width(6.dp))
                        Text("Web DOM storage and offline databases", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearData(clearCache, clearCookies, clearHistory, clearStorage)
                        showClearDataDialog = false
                        Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Red)
                ) {
                    Text("Clear Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun BrowserSettingCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(iconTint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
