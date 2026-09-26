package com.example.speeddown.ui.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
fun BrowserSettingsScreen(
    currentSettings: BrowserSettings,
    onSaveSettings: (BrowserSettings) -> Unit,
    onClearData: (clearCache: Boolean, clearCookies: Boolean, clearHistory: Boolean, clearStorage: Boolean) -> Unit,
    onResetShortcuts: () -> Unit,
    onBack: () -> Unit
) {
    var settingsState by remember { mutableStateOf(currentSettings) }
    var customDnsInput by remember { mutableStateOf(currentSettings.customDnsHost.ifEmpty { currentSettings.customDnsIp }) }
    var dnsLatencyText by remember { mutableStateOf<String?>(null) }
    var isTestingDns by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    fun save(updated: BrowserSettings) {
        settingsState = updated
        onSaveSettings(updated)
    }

    BackHandler {
        save(settingsState.copy(customDnsHost = customDnsInput))
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Browser Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("DNS, Privacy & Surfing Engine", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        save(settingsState.copy(customDnsHost = customDnsInput))
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        save(settingsState.copy(customDnsHost = customDnsInput))
                        Toast.makeText(context, "Settings Saved", Toast.LENGTH_SHORT).show()
                        onBack()
                    }) {
                        Text("Done", fontWeight = FontWeight.Bold, color = Purple)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Section 1: Secure DNS & Anti-Censorship ──────────────────────────
            item {
                SettingsSectionCard(
                    title = "Secure DNS & Anti-Censorship",
                    description = "Bypasses ISP blocks on streaming sites and accelerates domain lookups",
                    icon = Icons.Filled.Dns,
                    iconTint = Blue
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("Cloudflare", "Google", "AdGuard").forEach { prov ->
                                val selected = settingsState.dnsProvider == prov
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        dnsLatencyText = null
                                        save(settingsState.copy(dnsProvider = prov))
                                    },
                                    label = { Text(prov, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Purple,
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("Quad9", "Custom", "System").forEach { prov ->
                                val selected = settingsState.dnsProvider == prov
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        dnsLatencyText = null
                                        save(settingsState.copy(dnsProvider = prov))
                                    },
                                    label = { Text(prov, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Purple,
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // Custom DNS Hostname / IP Input
                        if (settingsState.dnsProvider == "Custom") {
                            OutlinedTextField(
                                value = customDnsInput,
                                onValueChange = {
                                    customDnsInput = it
                                    save(settingsState.copy(customDnsHost = it, customDnsIp = it))
                                },
                                label = { Text("Custom DNS Hostname or IP") },
                                placeholder = { Text("e.g. dns.adguard-dns.com, 1.1.1.1, one.one.one.one") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        // DNS Details & Ping Tester
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val providerDesc = when (settingsState.dnsProvider) {
                                "Cloudflare" -> "1.1.1.1 (Cloudflare Ultra-Fast)"
                                "Google" -> "8.8.8.8 (Google Public DNS)"
                                "AdGuard" -> "94.140.14.14 (AdGuard Ad-Shield)"
                                "Quad9" -> "9.9.9.9 (Quad9 Malware Shield)"
                                "Custom" -> customDnsInput.ifEmpty { "Enter custom host above" }
                                else -> "Default System / Wi-Fi DNS"
                            }
                            Text(
                                text = providerDesc,
                                fontSize = 11.sp,
                                color = Purple,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Button(
                                onClick = {
                                    isTestingDns = true
                                    dnsLatencyText = "Testing..."
                                    coroutineScope.launch {
                                        val (success, ms) = SecureDnsHelper.testDnsLatency(
                                            settingsState.dnsProvider,
                                            customDnsInput
                                        )
                                        isTestingDns = false
                                        dnsLatencyText = if (success) "Ping: ${ms}ms (Active)" else "Failed to reach"
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                enabled = !isTestingDns
                            ) {
                                Text(
                                    dnsLatencyText ?: "Test Ping",
                                    fontSize = 11.sp,
                                    color = if (dnsLatencyText?.contains("ms") == true) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // ── Section 2: Default Search Engine ────────────────────────────────
            item {
                SettingsSectionCard(
                    title = "Default Search Engine",
                    description = "Used when typing queries in the address bar & home search box",
                    icon = Icons.Filled.Search,
                    iconTint = Purple
                ) {
                    val engines = listOf("Google", "DuckDuckGo", "Brave", "Bing", "Yandex")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        engines.take(3).forEach { engine ->
                            val isSel = settingsState.searchEngine == engine
                            FilterChip(
                                selected = isSel,
                                onClick = { save(settingsState.copy(searchEngine = engine)) },
                                label = { Text(engine, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Purple,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        engines.drop(3).forEach { engine ->
                            val isSel = settingsState.searchEngine == engine
                            FilterChip(
                                selected = isSel,
                                onClick = { save(settingsState.copy(searchEngine = engine)) },
                                label = { Text(engine, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Purple,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            // ── Section 3: Privacy & Security ────────────────────────────────────
            item {
                SettingsSectionCard(
                    title = "Privacy & Anti-Tracking",
                    description = "Signals, cookies, and connection encryption rules",
                    icon = Icons.Filled.Security,
                    iconTint = Green
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // HTTPS Only
                        SettingsToggleRow(
                            title = "HTTPS-Only Mode",
                            subtitle = "Automatically upgrades all insecure HTTP links to HTTPS",
                            checked = settingsState.httpsOnly,
                            onCheckedChange = { save(settingsState.copy(httpsOnly = it)) }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Do Not Track
                        SettingsToggleRow(
                            title = "Do Not Track & Global Privacy Control",
                            subtitle = "Sends DNT: 1 and Sec-GPC: 1 signals to prevent tracking cookies",
                            checked = settingsState.doNotTrack,
                            onCheckedChange = { save(settingsState.copy(doNotTrack = it)) }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // 3rd-party cookies
                        SettingsToggleRow(
                            title = "Allow Embedded Player Cookies",
                            subtitle = "Required for embedded video iframe players (Streamwish, Dood, etc.)",
                            checked = settingsState.acceptThirdPartyCookies,
                            onCheckedChange = { save(settingsState.copy(acceptThirdPartyCookies = it)) }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Block popups
                        SettingsToggleRow(
                            title = "Block Popup Windows",
                            subtitle = "Stops intrusive window.open script popups and redirects",
                            checked = settingsState.blockPopups,
                            onCheckedChange = { save(settingsState.copy(blockPopups = it)) }
                        )
                    }
                }
            }

            // ── Section 4: Webpage Zoom & Display ────────────────────────────────
            item {
                SettingsSectionCard(
                    title = "Display & Webpage Zoom",
                    description = "Adjust text zoom, pinch-to-zoom, and visual themes",
                    icon = Icons.Filled.ZoomIn,
                    iconTint = Amber
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Zoom Slider with In / Out buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Webpage Zoom Level", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Pinch-to-zoom is enabled natively", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${settingsState.textZoom}%", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Purple)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                val newZoom = (settingsState.textZoom - 10).coerceAtLeast(70)
                                save(settingsState.copy(textZoom = newZoom))
                            }) {
                                Icon(Icons.Filled.ZoomOut, "Zoom Out", tint = Purple)
                            }

                            Slider(
                                value = settingsState.textZoom.toFloat(),
                                onValueChange = { save(settingsState.copy(textZoom = it.toInt())) },
                                valueRange = 70f..160f,
                                steps = 8,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(onClick = {
                                val newZoom = (settingsState.textZoom + 10).coerceAtMost(160)
                                save(settingsState.copy(textZoom = newZoom))
                            }) {
                                Icon(Icons.Filled.ZoomIn, "Zoom In", tint = Purple)
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Force Dark Mode
                        SettingsToggleRow(
                            title = "Force Dark Mode on Web Pages",
                            subtitle = "Inverts bright page backgrounds for comfortable night surfing",
                            checked = settingsState.forceDarkMode,
                            onCheckedChange = { save(settingsState.copy(forceDarkMode = it)) }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Default Desktop Mode
                        SettingsToggleRow(
                            title = "Default to Desktop Mode",
                            subtitle = "Always request full desktop version of websites",
                            checked = settingsState.defaultDesktopMode,
                            onCheckedChange = { save(settingsState.copy(defaultDesktopMode = it)) }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // JavaScript
                        SettingsToggleRow(
                            title = "JavaScript Enabled",
                            subtitle = "Required for interactive modern websites and video stream loading",
                            checked = settingsState.javaScriptEnabled,
                            onCheckedChange = { save(settingsState.copy(javaScriptEnabled = it)) }
                        )
                    }
                }
            }

            // ── Section 5: Data Management & Shortcuts ───────────────────────────
            item {
                SettingsSectionCard(
                    title = "Browsing Data & Shortcuts",
                    description = "Clear cache, cookies, history, or manage speed dial",
                    icon = Icons.Filled.DeleteSweep,
                    iconTint = Red
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { showClearDataDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Red.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Delete, null, tint = Red, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Clear Browsing Data...", color = Red, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                onResetShortcuts()
                                Toast.makeText(context, "Shortcuts reset to default", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Restore, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Reset Speed Dial to Defaults")
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // Clear Data Dialog
    if (showClearDataDialog) {
        var clearCache by remember { mutableStateOf(true) }
        var clearCookies by remember { mutableStateOf(true) }
        var clearHistory by remember { mutableStateOf(true) }
        var clearStorage by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("Clear Browsing Data", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearCache, onCheckedChange = { clearCache = it })
                        Spacer(Modifier.width(6.dp))
                        Text("Cached images and files")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearCookies, onCheckedChange = { clearCookies = it })
                        Spacer(Modifier.width(6.dp))
                        Text("Cookies & site data")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearHistory, onCheckedChange = { clearHistory = it })
                        Spacer(Modifier.width(6.dp))
                        Text("Browsing history")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearStorage, onCheckedChange = { clearStorage = it })
                        Spacer(Modifier.width(6.dp))
                        Text("Local DOM web storage")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearData(clearCache, clearCookies, clearHistory, clearStorage)
                        showClearDataDialog = false
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
private fun SettingsSectionCard(
    title: String,
    description: String,
    icon: ImageVector,
    iconTint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(iconTint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
