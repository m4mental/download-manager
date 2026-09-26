package com.example.speeddown.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speeddown.data.DownloadItem

private val Purple = Color(0xFF7C3AED)

@Composable
fun RefreshUrlDialog(
    item: DownloadItem,
    onDismiss: () -> Unit,
    onConfirm: (newUrl: String) -> Unit
) {
    var newUrl by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Refresh, null, tint = Purple)
                Spacer(Modifier.width(8.dp))
                Text("Refresh Download Link", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Agar link expire ho gaya hai ya 403/410 error aa raha hai, to naya fresh URL paste karein. Download zero se shuru nahi hoga, wahi se continue hoga!",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "File: ${item.fileName}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Purple
                )

                OutlinedTextField(
                    value = newUrl,
                    onValueChange = {
                        newUrl = it
                        errorText = ""
                    },
                    label = { Text("New Fresh Download URL") },
                    placeholder = { Text("Paste refreshed link...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = errorText.isNotEmpty(),
                    supportingText = if (errorText.isNotEmpty()) {{ Text(errorText, color = MaterialTheme.colorScheme.error) }} else null,
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = clipboardManager.getText()?.text?.trim() ?: ""
                            if (clip.isNotBlank()) newUrl = clip
                        }) {
                            Icon(Icons.Filled.Link, "Paste")
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = newUrl.trim()
                    if (clean.isBlank() || (!clean.startsWith("http://") && !clean.startsWith("https://") && !clean.startsWith("magnet:"))) {
                        errorText = "Please enter a valid HTTP/HTTPS or Magnet URL"
                        return@Button
                    }
                    onConfirm(clean)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Purple),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Update & Resume", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
