package com.example.speeddown.ui.dialogs

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val Amber = Color(0xFFD97706)
private val Purple = Color(0xFF7C3AED)

@Composable
fun DuplicateWarningDialog(
    existingFile: File,
    onDismiss: () -> Unit,
    onOpenExisting: () -> Unit,
    onOverwrite: () -> Unit,
    onRenameAndDownload: (newName: String) -> Unit
) {
    val context = LocalContext.current
    val sizeStr = Formatter.formatFileSize(context, existingFile.length())

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.WarningAmber, null, tint = Amber, modifier = Modifier.size(36.dp)) },
        title = {
            Text("File Already Exists", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Yeh file aapke phone me already downloaded hai:",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(existingFile.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.height(2.dp))
                        Text("Size: $sizeStr", fontSize = 11.sp, color = Amber)
                    }
                }

                Text(
                    "Aap kya karna chahte hain?",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onOpenExisting,
                    colors = ButtonDefaults.buttonColors(containerColor = Purple),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open Existing File", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        val ext = existingFile.extension
                        val base = existingFile.nameWithoutExtension
                        val newName = if (ext.isNotBlank()) "$base (1).$ext" else "$base (1)"
                        onRenameAndDownload(newName)
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download as '${existingFile.nameWithoutExtension} (1)'")
                }

                TextButton(
                    onClick = onOverwrite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Overwrite Existing File", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
