package com.remodroid.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.remodroid.android.R
import com.remodroid.shared.models.PermissionLevel
import com.remodroid.shared.rtc.ConnectionQuality
import com.remodroid.shared.session.ControllerUiState
import com.remodroid.shared.session.HostUiState
import com.remodroid.shared.session.SessionState
import com.remodroid.shared.session.StateLabels
import com.remodroid.shared.signaling.ConnectionStatus
import com.remodroid.shared.validation.Validation

class HomeActions(
    val onToggleSharing: (Boolean) -> Unit = {},
    val onToggleAccepting: (Boolean) -> Unit = {},
    val onRefreshPassword: () -> Unit = {},
    val onCopy: (label: String, value: String) -> Unit = { _, _ -> },
    val onShare: (id: String, password: String) -> Unit = { _, _ -> },
    val onOpenAccessibility: () -> Unit = {},
    val onConnect: (id: String, password: String, level: PermissionLevel) -> Unit = { _, _, _ -> },
    val onCancelConnect: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onHostEnd: () -> Unit = {},
    val onHostPause: (Boolean) -> Unit = {},
    val onHostPermission: (PermissionLevel) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

@Composable
fun HomeScreen(host: HostUiState, controller: ControllerUiState, remoteControlEnabled: Boolean, actions: HomeActions) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(actions.onOpenSettings)
        AllowAccessCard(host, remoteControlEnabled, actions)
        ControlCard(controller, actions)
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.remodroid_mark), contentDescription = null, modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("RemoDroid", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Control. Support. Anywhere.", style = MaterialTheme.typography.bodySmall, color = RemoColors.TextSecondary)
        }
        IconButton(onClick = onSettings, modifier = Modifier.testTag("open_settings")) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun AllowAccessCard(host: HostUiState, remoteControlEnabled: Boolean, actions: HomeActions) {
    SectionCard(
        "Allow Remote Access",
        trailing = {
            Switch(
                checked = host.sharingEnabled,
                onCheckedChange = actions.onToggleSharing,
                modifier = Modifier.testTag("sharing_switch"),
            )
        },
    ) {
        if (!host.sharingEnabled) {
            Text(
                "Turn on to get a temporary ID and password that a person you trust can use to request access. " +
                    "You approve every request and can end it at any time.",
                style = MaterialTheme.typography.bodyMedium,
                color = RemoColors.TextSecondary,
            )
        } else {
            CredentialRow(
                label = "Your ID",
                value = host.deviceId?.let(Validation::formatId) ?: "—",
                chip = if (host.idIsTemporary) "Temporary" else "Persistent",
                onCopy = host.deviceId?.let { id -> { actions.onCopy("RemoDroid ID", id) } },
                tag = "device_id",
            )
            val countdown = rememberCountdown(host.passwordExpiresAt)
            CredentialRow(
                label = "Password",
                value = host.password ?: "—",
                chip = countdown?.let { "Expires in $it" },
                onCopy = host.password?.let { pw -> { actions.onCopy("RemoDroid password", pw) } },
                tag = "device_password",
                extra = {
                    IconButton(onClick = actions.onRefreshPassword, enabled = host.deviceId != null) {
                        Icon(Icons.Filled.Refresh, contentDescription = "New password")
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { actions.onShare(host.deviceId ?: return@OutlinedButton, host.password ?: return@OutlinedButton) },
                    enabled = host.deviceId != null && host.password != null,
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Share details")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Accept incoming requests", modifier = Modifier.weight(1f))
                Switch(checked = host.acceptingRequests, onCheckedChange = actions.onToggleAccepting)
            }
            StatusLine(
                StateLabels.host(host.state, host.signaling, host.acceptingRequests).let {
                    if (host.signaling == ConnectionStatus.WAITING_TO_RETRY) "${StateLabels.signaling(host.signaling)} · $it" else it
                },
                statusColor(host.state, host.signaling),
            )
            HorizontalDivider(color = RemoColors.Navy600)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Remote control", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (remoteControlEnabled) "Enabled · partners can tap and type when you allow it"
                        else "Off · sessions are view-only until you enable it",
                        style = MaterialTheme.typography.bodySmall,
                        color = RemoColors.TextSecondary,
                    )
                }
                TextButton(onClick = actions.onOpenAccessibility) { Text(if (remoteControlEnabled) "Manage" else "Set up") }
            }
            if (host.sessionId != null) HostSessionPanel(host, actions)
        }
        host.notice?.let { Notice(it, actions.onDismissNotice) }
    }
}

@Composable
private fun HostSessionPanel(host: HostUiState, actions: HomeActions) {
    HorizontalDivider(color = RemoColors.Navy600)
    Text(
        "Sharing your screen with ${host.peerName ?: "your partner"}",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.testTag("host_session_title"),
    )
    if (host.peerSignalingLost) Text("Partner's connection to the server dropped; waiting for it to return.", color = RemoColors.Warning)
    host.quality?.let { q ->
        Text(
            "Connection: ${q.label}" + (host.relayed?.let { if (it) " · via relay" else " · direct" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = qualityColor(q),
        )
    }
    host.grantedPermission?.let { granted ->
        PermissionSelector(
            selected = granted,
            onSelected = actions.onHostPermission,
            max = host.requestedPermission ?: granted,
            label = "Allowed access",
        )
    }
    host.controlNote?.takeIf { host.grantedPermission != PermissionLevel.VIEW_ONLY }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = RemoColors.Warning)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.onHostPause(!host.paused) }, modifier = Modifier.testTag("pause_button")) {
            Text(if (host.paused) "Resume sharing" else "Privacy pause")
        }
        OutlinedButton(
            onClick = { actions.onHostPermission(PermissionLevel.VIEW_ONLY) },
            enabled = host.grantedPermission != PermissionLevel.VIEW_ONLY,
        ) { Text("Revoke control") }
    }
    Button(
        onClick = actions.onHostEnd,
        colors = ButtonDefaults.buttonColors(containerColor = RemoColors.Danger),
        modifier = Modifier.fillMaxWidth().testTag("host_end"),
    ) { Text("End session") }
}

@Composable
private fun ControlCard(controller: ControllerUiState, actions: HomeActions) {
    var partnerId by rememberSaveable { mutableStateOf(controller.partnerId?.let(Validation::formatId) ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(true) }
    var level by rememberSaveable { mutableStateOf(PermissionLevel.GUIDED_SUPPORT) }
    val busy = controller.state in setOf(SessionState.REQUESTING_CONNECTION, SessionState.WAITING_FOR_APPROVAL)

    SectionCard("Control Remote Device") {
        OutlinedTextField(
            value = partnerId,
            onValueChange = { v -> partnerId = v.filter { it.isDigit() || it == ' ' }.take(11) },
            label = { Text("Partner ID") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("partner_id"),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(16) },
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Show password")
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("partner_password"),
        )
        PermissionSelector(selected = level, onSelected = { level = it }, enabled = !busy)
        if (busy) {
            StatusLine(StateLabels.controller(controller.state), RemoColors.Warning)
            val countdown = rememberCountdown(controller.approvalExpiresAt)
            countdown?.let { Text("Request expires in $it", style = MaterialTheme.typography.bodySmall, color = RemoColors.TextSecondary) }
            OutlinedButton(onClick = actions.onCancelConnect, modifier = Modifier.fillMaxWidth().testTag("cancel_connect")) { Text("Cancel") }
        } else {
            Button(
                onClick = { actions.onConnect(partnerId, password, level) },
                modifier = Modifier.fillMaxWidth().testTag("connect_button"),
            ) { Text("Connect") }
        }
        controller.message?.let {
            Text(
                it,
                color = if (controller.state == SessionState.FAILED) RemoColors.Danger else RemoColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("controller_message"),
            )
        }
    }
}

@Composable
private fun CredentialRow(
    label: String,
    value: String,
    chip: String?,
    onCopy: (() -> Unit)?,
    tag: String,
    extra: @Composable () -> Unit = {},
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = RemoColors.TextSecondary)
            chip?.let { Chip(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MonoDigits, modifier = Modifier.weight(1f).testTag(tag))
            extra()
            IconButton(onClick = { onCopy?.invoke() }, enabled = onCopy != null) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy $label")
            }
        }
    }
}

@Composable
fun Notice(text: String, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = RemoColors.Warning, modifier = Modifier.weight(1f).testTag("notice"))
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

fun statusColor(state: SessionState, signaling: ConnectionStatus) = when {
    signaling == ConnectionStatus.WAITING_TO_RETRY -> RemoColors.Danger
    state.isConnected || state == SessionState.AVAILABLE -> RemoColors.Success
    state == SessionState.FAILED -> RemoColors.Danger
    state == SessionState.OFFLINE -> RemoColors.TextSecondary
    else -> RemoColors.Warning
}

fun qualityColor(q: ConnectionQuality) = when (q) {
    ConnectionQuality.GOOD -> RemoColors.Success
    ConnectionQuality.FAIR -> RemoColors.Warning
    ConnectionQuality.POOR -> RemoColors.Danger
}
