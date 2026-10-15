package com.remodroid.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.remodroid.android.data.AppSettings
import com.remodroid.android.data.Settings
import com.remodroid.shared.rtc.VideoQuality

@Composable
fun SettingsScreen(current: Settings, onSave: (Settings) -> Unit, onBack: () -> Unit, onOpenAccessibility: () -> Unit) {
    var url by remember { mutableStateOf(current.signalingUrl) }
    var name by remember { mutableStateOf(current.displayName) }
    var quality by remember { mutableStateOf(current.quality) }
    var relayOnly by remember { mutableStateOf(current.relayOnly) }
    val urlError = AppSettings.validateUrl(url)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        SectionCard("Connection") {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Signaling server") },
                supportingText = { Text(urlError ?: "Example: wss://signal.example.com/ws") },
                isError = urlError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("settings_url"),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(64) },
                label = { Text("Device name shown to partners") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Relay only (TURN)")
                    Text(
                        "Testing aid: force all media through the TURN relay to verify relay connectivity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RemoColors.TextSecondary,
                    )
                }
                Switch(checked = relayOnly, onCheckedChange = { relayOnly = it })
            }
        }
        SectionCard("Video quality when sharing") {
            VideoQuality.entries.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = quality == q, onClick = { quality = q }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = quality == q, onClick = { quality = q })
                    Text(q.label)
                }
            }
            Text(
                "These are upper limits. WebRTC lowers bitrate and resolution automatically on slow or mobile connections.",
                style = MaterialTheme.typography.bodySmall,
                color = RemoColors.TextSecondary,
            )
        }
        SectionCard("Remote control") {
            Text(
                "Remote taps, swipes, navigation buttons and typing use Android's Accessibility service. " +
                    "Without it, sessions on this device are view-only. Android does not allow any app to operate " +
                    "the lock screen, other secure screens, or windows that block screen capture.",
                style = MaterialTheme.typography.bodySmall,
                color = RemoColors.TextSecondary,
            )
            Button(onClick = onOpenAccessibility) { Text("Open Accessibility settings") }
        }
        Button(
            onClick = { onSave(current.copy(signalingUrl = url, displayName = name.ifBlank { AppSettings.defaultName() }, quality = quality, relayOnly = relayOnly)) },
            enabled = urlError == null,
            modifier = Modifier.fillMaxWidth().testTag("settings_save"),
        ) { Text("Save") }
    }
}
