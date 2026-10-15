package com.remodroid.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.remodroid.shared.models.PermissionLevel
import kotlinx.coroutines.delay

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = RemoColors.Navy800),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                trailing()
            }
            content()
        }
    }
}

@Composable
fun StatusLine(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = RemoColors.TextSecondary, modifier = Modifier.testTag("status"))
    }
}

@Composable
fun Chip(text: String, color: Color = RemoColors.Navy600) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSelector(
    selected: PermissionLevel,
    onSelected: (PermissionLevel) -> Unit,
    modifier: Modifier = Modifier,
    max: PermissionLevel = PermissionLevel.MAXIMUM_SUPPORTED,
    label: String = "Requested access",
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            supportingText = { Text(selected.description) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable).testTag("permission_selector"),
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

/** "mm:ss" until [epochMs], ticking every second. */
@Composable
fun rememberCountdown(epochMs: Long?): String? {
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
