package com.remodroid.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.remodroid.shared.models.PermissionLevel
import com.remodroid.shared.session.IncomingRequest

/**
 * Explicit consent for an incoming session. Approving leads to Android's own screen-capture
 * consent dialog; declining either dialog rejects the request.
 */
@Composable
fun IncomingRequestDialog(
    request: IncomingRequest,
    remoteControlEnabled: Boolean,
    onApprove: (PermissionLevel) -> Unit,
    onReject: () -> Unit,
) {
    var level by remember(request.sessionId) { mutableStateOf(request.requestedPermission) }
    val countdown = rememberCountdown(request.expiresAt)
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Incoming connection request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${request.controllerName ?: "Someone"} wants to see your screen.",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("incoming_name"),
                )
                Text("They requested: ${request.requestedPermission.label}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Only approve people you know and trust. They will see everything on your screen, " +
                        "including notifications, until you end the session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RemoColors.Warning,
                )
                PermissionSelector(
                    selected = level,
                    onSelected = { level = it },
                    max = request.requestedPermission,
                    label = "Allow",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (level != PermissionLevel.VIEW_ONLY && !remoteControlEnabled) {
                    Text(
                        "Remote control is off on this device, so the session will be view-only until you enable it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RemoColors.TextSecondary,
                    )
                }
                Text(
                    "Next, Android will ask for permission to capture your screen." +
                        (countdown?.let { " This request expires in $it." } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = RemoColors.TextSecondary,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onApprove(level) }, modifier = Modifier.testTag("approve")) { Text("Approve") }
        },
        dismissButton = {
            TextButton(onClick = onReject, modifier = Modifier.testTag("reject")) { Text("Decline") }
        },
    )
}
