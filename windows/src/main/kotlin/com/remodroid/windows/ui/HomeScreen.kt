package com.remodroid.windows.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.remodroid.shared.models.PermissionLevel
import com.remodroid.shared.rtc.ConnectionQuality
import com.remodroid.shared.session.ControllerUiState
import com.remodroid.shared.session.HostUiState
import com.remodroid.shared.session.SessionState
import com.remodroid.shared.session.StateLabels
import com.remodroid.shared.signaling.ConnectionStatus
import com.remodroid.shared.validation.Validation
import kotlinx.coroutines.delay

class HomeActions(
    val onToggleSharing: (Boolean) -> Unit,
    val onToggleAccepting: (Boolean) -> Unit,
    val onRefreshPassword: () -> Unit,
    val onCopy: (String) -> Unit,
    val onConnect: (String, String, PermissionLevel) -> Unit,
    val onCancelConnect: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onHostEnd: () -> Unit,
    val onHostPause: (Boolean) -> Unit,
    val onHostPermission: (PermissionLevel) -> Unit,
    val onDismissNotice: () -> Unit,
)

@Composable
fun HomeScreen(host: HostUiState, controller: ControllerUiState, actions: HomeActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource("remodroid_icon.png"), contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("RemoDroid", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Control. Support. Anywhere.", style = MaterialTheme.typography.bodySmall, color = RemoColors.TextSecondary)
            }
            IconButton(onClick = actions.onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
        // Side by side when there is room, stacked in narrow windows.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth > 860.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f)) { AllowAccessPanel(host, actions) }
                    Box(Modifier.weight(1f)) { ControlPanel(controller, actions) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AllowAccessPanel(host, actions)
                    ControlPanel(controller, actions)
                }
            }
        }
    }
}

@Composable
private fun Panel(title: String, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = RemoColors.Navy800)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                trailing()
            }
            content()
        }
    }
}

@Composable
private fun AllowAccessPanel(host: HostUiState, actions: HomeActions) {
    Panel("Allow Remote Access", trailing = { Switch(checked = host.sharingEnabled, onCheckedChange = actions.onToggleSharing) }) {
        if (!host.sharingEnabled) {
            Text(
                "Turn on to get a temporary ID and password. You approve every request and can end a session at any time.",
                color = RemoColors.TextSecondary,
            )
        } else {
            Credential("Your ID", host.deviceId?.let(Validation::formatId) ?: "—", if (host.idIsTemporary) "Temporary" else "Persistent") {
                host.deviceId?.let(actions.onCopy)
            }
            val countdown = countdown(host.passwordExpiresAt)
            Credential("Password", host.password ?: "—", countdown?.let { "Expires in $it" }, extra = {
                IconButton(onClick = actions.onRefreshPassword) { Icon(Icons.Filled.Refresh, contentDescription = "New password") }
            }) { host.password?.let(actions.onCopy) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Accept incoming requests", modifier = Modifier.weight(1f))
                Switch(checked = host.acceptingRequests, onCheckedChange = actions.onToggleAccepting)
            }
            Status(StateLabels.host(host.state, host.signaling, host.acceptingRequests), statusColor(host.state, host.signaling))
            if (host.signaling == ConnectionStatus.WAITING_TO_RETRY) Text(StateLabels.signaling(host.signaling), color = RemoColors.Danger)
            if (host.sessionId != null) {
                HorizontalDivider(color = RemoColors.Navy600)
                Text("Sharing your screen with ${host.peerName ?: "your partner"}", fontWeight = FontWeight.SemiBold)
                host.quality?.let {
                    Text(
                        "Connection: ${it.label}" + (host.relayed?.let { r -> if (r) " · via relay" else " · direct" } ?: ""),
                        color = qualityColor(it),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                host.grantedPermission?.let { granted ->
                    PermissionSelector(granted, actions.onHostPermission, max = host.requestedPermission ?: granted, label = "Allowed access")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.onHostPause(!host.paused) }) { Text(if (host.paused) "Resume sharing" else "Privacy pause") }
                    OutlinedButton(
                        onClick = { actions.onHostPermission(PermissionLevel.VIEW_ONLY) },
                        enabled = host.grantedPermission != PermissionLevel.VIEW_ONLY,
                    ) { Text("Revoke control") }
                    Button(onClick = actions.onHostEnd, colors = ButtonDefaults.buttonColors(containerColor = RemoColors.Danger)) { Text("End session") }
                }
            }
        }
        host.notice?.let { Notice(it, actions.onDismissNotice) }
    }
}

@Composable
private fun ControlPanel(controller: ControllerUiState, actions: HomeActions) {
    var id by remember { mutableStateOf(controller.partnerId?.let(Validation::formatId) ?: "") }
    var password by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(PermissionLevel.GUIDED_SUPPORT) }
    val busy = controller.state == SessionState.REQUESTING_CONNECTION || controller.state == SessionState.WAITING_FOR_APPROVAL
    Panel("Control Remote Device") {
        OutlinedTextField(
            value = id,
            onValueChange = { v -> id = v.filter { it.isDigit() || it == ' ' }.take(11) },
            label = { Text("Partner ID") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(16) },
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        PermissionSelector(level, { level = it }, enabled = !busy)
        if (busy) {
            Status(StateLabels.controller(controller.state), RemoColors.Warning)
            countdown(controller.approvalExpiresAt)?.let { Text("Request expires in $it", color = RemoColors.TextSecondary) }
            OutlinedButton(onClick = actions.onCancelConnect, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        } else {
            Button(onClick = { actions.onConnect(id, password, level) }, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
        }
        controller.message?.let {
            Text(it, color = if (controller.state == SessionState.FAILED) RemoColors.Danger else RemoColors.TextSecondary)
        }
    }
}

@Composable
private fun Credential(label: String, value: String, chip: String?, extra: @Composable () -> Unit = {}, onCopy: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = RemoColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
            chip?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(RemoColors.Navy600, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MonoDigits, modifier = Modifier.weight(1f))
            extra()
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy $label") }
        }
    }
}

@Composable
fun Status(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = RemoColors.TextSecondary)
    }
}

@Composable
fun Notice(text: String, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = RemoColors.Warning, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSelector(
    selected: PermissionLevel,
    onSelected: (PermissionLevel) -> Unit,
    max: PermissionLevel = PermissionLevel.MAXIMUM_SUPPORTED,
    label: String = "Requested access",
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            supportingText = { Text(selected.description) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PermissionLevel.entries.filter { it.ordinal <= max.ordinal }.forEach { level ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(level.label)
                            Text(level.description, style = MaterialTheme.typography.bodySmall, color = RemoColors.TextSecondary)
                        }
                    },
                    onClick = {
                        onSelected(level)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun countdown(epochMs: Long?): String? {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(epochMs) {
        while (epochMs != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    if (epochMs == null) return null
    val left = ((epochMs - now) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(left / 60, left % 60)
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
