package com.example.speeddown.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speeddown.data.DownloadItem
import com.example.speeddown.ui.components.*

@Composable
fun ChecksumDialog(
    item: DownloadItem,
    onCalculateChecksums: suspend (String) -> Pair<String, String>,
    onDismiss: () -> Unit
) {
    var md5Hash by remember { mutableStateOf("Calculating...") }
    var sha256Hash by remember { mutableStateOf("Calculating...") }
    var isCalculating by remember { mutableStateOf(true) }
    var compareInput by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(item.filePath) {
        try {
            val (md5, sha256) = onCalculateChecksums(item.filePath)
            md5Hash = md5
            sha256Hash = sha256
        } catch (e: Exception) {
            md5Hash = "Error: ${e.message}"
            sha256Hash = "Error: ${e.message}"
        } finally {
            isCalculating = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.VerifiedUser, null, tint = Purple, modifier = Modifier.size(28.dp)) },
        title = { Text("File Checksum Verifier", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.fileName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatSize(item.downloadedSize), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (isCalculating) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Purple)
                        Spacer(Modifier.width(10.dp))
                        Text("Calculating cryptographic hashes...", fontSize = 12.sp)
                    }
                } else {
                    // MD5 block
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.4f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("MD5", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Purple)
                                IconButton(onClick = { clipboardManager.setText(AnnotatedString(md5Hash)) }, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Filled.ContentCopy, "Copy MD5", modifier = Modifier.size(14.dp), tint = Purple)
                                }
                            }
                            Text(md5Hash, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }

                    // SHA-256 block
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.4f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("SHA-256", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Purple)
                                IconButton(onClick = { clipboardManager.setText(AnnotatedString(sha256Hash)) }, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Filled.ContentCopy, "Copy SHA-256", modifier = Modifier.size(14.dp), tint = Purple)
                                }
                            }
                            Text(sha256Hash, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    // Compare block
                    OutlinedTextField(
                        value = compareInput,
                        onValueChange = { compareInput = it.trim() },
                        label = { Text("Compare Expected Hash") },
                        placeholder = { Text("Paste expected MD5 or SHA-256") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (compareInput.isNotBlank()) {
                                IconButton(onClick = { compareInput = "" }) {
                                    Icon(Icons.Filled.Clear, "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    )

                    if (compareInput.isNotBlank()) {
                        val matches = compareInput.equals(md5Hash, ignoreCase = true) ||
                                compareInput.equals(sha256Hash, ignoreCase = true)
                        Surface(
                            color = if (matches) Green.copy(0.12f) else Red.copy(0.12f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (matches) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                    null,
                                    tint = if (matches) Green else Red,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (matches) "✓ Hash Matched! File is authentic & verified." else "✗ Hash Mismatch! Checksum does not match.",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (matches) Green else Red
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
