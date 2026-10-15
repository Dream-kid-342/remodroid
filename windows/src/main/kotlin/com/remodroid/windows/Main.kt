package com.remodroid.windows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.remodroid.shared.models.PermissionLevel
import com.remodroid.shared.rtc.VideoQuality
import com.remodroid.shared.session.IncomingRequest
import com.remodroid.shared.session.SessionState
import com.remodroid.windows.data.DesktopSettings
import com.remodroid.windows.data.DesktopSettingsData
import com.remodroid.windows.ui.HomeActions
import com.remodroid.windows.ui.HomeScreen
import com.remodroid.windows.ui.PermissionSelector
import com.remodroid.windows.ui.RemoColors
import com.remodroid.windows.ui.RemoDroidTheme
import com.remodroid.windows.ui.RemoteView
import com.remodroid.windows.ui.countdown
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

fun main() = application {
    val container = remember { DesktopContainer() }
    var closing by remember { mutableStateOf(false) }
    Window(
        onCloseRequest = {
            if (!closing) {
                closing = true
                container.scope.launch {
                    container.shutdown()
                    exitApplication()
                }
            }
        },
        title = "RemoDroid",
        icon = painterResource("remodroid_icon.png"),
        state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
    ) {
        RemoDroidTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                App(container) { window.toFront() }
            }
        }
    }
}

@Composable
private fun App(container: DesktopContainer, bringToFront: () -> Unit) {
    val host by container.host.ui.collectAsState()
    val controller by container.controller.ui.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    val inRemoteSession = controller.state in setOf(
        SessionState.NEGOTIATING, SessionState.CONNECTING_MEDIA, SessionState.CONNECTED_VIEW_ONLY,
        SessionState.CONNECTED_CONTROL_ENABLED, SessionState.RECONNECTING, SessionState.DISCONNECTING,
    )

    if (inRemoteSession) {
        RemoteView(controller, container.controller)
    } else {
        HomeScreen(
            host = host,
            controller = controller,
            actions = HomeActions(
                onToggleSharing = { if (it) container.host.startSharing() else container.host.stopSharing() },
                onToggleAccepting = { container.host.setAcceptingRequests(it) },
                onRefreshPassword = { container.host.refreshPassword() },
                onCopy = { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(it), null) },
                onConnect = { id, pw, level -> container.controller.connect(id, pw, level) },
                onCancelConnect = { container.controller.disconnect() },
                onOpenSettings = { showSettings = true },
                onHostEnd = { container.host.endSession() },
                onHostPause = { container.host.setPaused(it) },
                onHostPermission = { container.host.setPermission(it) },
                onDismissNotice = { container.host.clearNotice() },
            ),
        )
    }

    host.incoming?.takeIf { host.state == SessionState.INCOMING_REQUEST }?.let { request ->
        LaunchedEffect(request.sessionId) { bringToFront() }
        IncomingDialog(request, onApprove = { container.host.approve(it) }, onReject = { container.host.reject() })
    }

    if (showSettings) {
        val current by container.settings.settings.collectAsState()
        SettingsDialog(current, onSave = {
            container.settings.update(it)
            showSettings = false
        }, onDismiss = { showSettings = false })
    }
}

@Composable
private fun IncomingDialog(request: IncomingRequest, onApprove: (PermissionLevel) -> Unit, onReject: () -> Unit) {
    var level by remember(request.sessionId) { mutableStateOf(request.requestedPermission) }
    val left = countdown(request.expiresAt)
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Incoming connection request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${request.controllerName ?: "Someone"} wants to see your screen.", fontWeight = FontWeight.SemiBold)
                Text("They requested: ${request.requestedPermission.label}")
                Text(
                    "Only approve people you know and trust. They will see your whole desktop until you end the session.",
                    color = RemoColors.Warning,
                )
                PermissionSelector(level, { level = it }, max = request.requestedPermission, label = "Allow")
                left?.let { Text("This request expires in $it.", color = RemoColors.TextSecondary) }
            }
        },
        confirmButton = { Button(onClick = { onApprove(level) }) { Text("Approve") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Decline") } },
    )
}

@Composable
private fun SettingsDialog(current: DesktopSettingsData, onSave: (DesktopSettingsData) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(current.signalingUrl) }
    var name by remember { mutableStateOf(current.displayName) }
    var quality by remember { mutableStateOf(current.quality) }
    var relayOnly by remember { mutableStateOf(current.relayOnly) }
    val error = DesktopSettings.validateUrl(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Signaling server") },
                    isError = error != null,
                    supportingText = { Text(error ?: "Example: wss://signal.example.com/ws") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(value = name, onValueChange = { name = it.take(64) }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Video quality when sharing", fontWeight = FontWeight.SemiBold)
                VideoQuality.entries.forEach { q ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = quality == q, onClick = { quality = q })
                        Text(q.label)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Relay only (TURN)")
                        Text("Testing aid: force media through the TURN relay.", color = RemoColors.TextSecondary)
                    }
                    Switch(checked = relayOnly, onCheckedChange = { relayOnly = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(current.copy(signalingUrl = url, displayName = name, quality = quality, relayOnly = relayOnly)) }, enabled = error == null) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
