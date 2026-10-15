package com.remodroid.android.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.remodroid.android.rtc.AndroidViewerPeer
import com.remodroid.shared.control.ControlCodec
import com.remodroid.shared.control.CoordinateMapper
import com.remodroid.shared.control.GestureClassifier
import com.remodroid.shared.control.GlobalAction
import com.remodroid.shared.control.GlobalActionCommand
import com.remodroid.shared.control.Key
import com.remodroid.shared.control.KeyCommand
import com.remodroid.shared.control.PointerSample
import com.remodroid.shared.control.TextInput
import com.remodroid.shared.models.Capability
import com.remodroid.shared.session.ControllerAgent
import com.remodroid.shared.session.ControllerUiState
import com.remodroid.shared.session.SessionState
import com.remodroid.shared.session.StateLabels
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun RemoteSessionScreen(
    ui: ControllerUiState,
    agent: ControllerAgent,
    eglBase: EglBase,
    onRotate: () -> Unit,
) {
    val peer by agent.peer.collectAsState()
    val track = (peer as? AndroidViewerPeer)?.remoteTrack?.collectAsState()?.value
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var fill by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    val caps = ui.capabilities
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
            if (track != null) {
                RemoteVideo(
                    track = track,
                    eglBase = eglBase,
                    fill = fill,
                    onFirstFrame = { agent.onFirstFrameRendered() },
                    onFrameSize = { frameSize = it },
                )
            }
            // Touch overlay: classify each finger trace and send it as a gesture.
            Box(
                Modifier.fillMaxSize().testTag("remote_surface").pointerInput(frameSize, viewSize, fill, caps) {
                    val rect = (if (fill) CoordinateMapper::fillRect else CoordinateMapper::fitRect)(
                        frameSize.width, frameSize.height, viewSize.width.toFloat(), viewSize.height.toFloat(),
                    ) ?: return@pointerInput
                    if (Capability.POINTER !in caps) return@pointerInput
                    val slop = with(density) { 12.dp.toPx() }
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                            val start = CoordinateMapper.viewToNormalized(down.position.x, down.position.y, rect) ?: continue
                            val samples = mutableListOf(PointerSample(start, down.uptimeMillis))
                            down.consume()
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                samples += PointerSample(
                                    CoordinateMapper.viewToNormalizedClamped(change.position.x, change.position.y, rect),
                                    change.uptimeMillis,
                                )
                                change.consume()
                                if (!change.pressed) break
                            }
                            GestureClassifier.classify(samples, rect.width, rect.height, slop)?.let { g ->
                                agent.send { seq -> GestureClassifier.toCommand(g, seq) }
                            }
                        }
                    }
                },
            )
        }

        Overlay(ui)

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding()) {
            Toolbar(
                ui = ui,
                fill = fill,
                onToggleFit = { fill = !fill },
                onRotate = onRotate,
                onGlobal = { action -> agent.send { GlobalActionCommand(it, action) } },
                onKeyboard = { showKeyboard = true },
                onDisconnect = { agent.disconnect() },
            )
            ui.lastInputProblem?.let {
                Text(
                    it,
                    color = RemoColors.Warning,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp).background(RemoColors.Navy900.copy(alpha = 0.8f)).padding(6.dp),
                )
            }
        }
    }

    if (showKeyboard) {
        TypeTextDialog(
            onSend = { text -> agent.send { TextInput(it, text.take(ControlCodec.MAX_TEXT_LENGTH)) } },
            onKey = { key -> agent.send { KeyCommand(it, key, down = true) } },
            onDismiss = { showKeyboard = false },
        )
    }
}

@Composable
private fun RemoteVideo(
    track: org.webrtc.VideoTrack,
    eglBase: EglBase,
    fill: Boolean,
    onFirstFrame: () -> Unit,
    onFrameSize: (IntSize) -> Unit,
) {
    val main = remember { Handler(Looper.getMainLooper()) }
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {
                        main.post(onFirstFrame)
                    }

                    override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                        val size = if (rotation % 180 == 0) IntSize(width, height) else IntSize(height, width)
                        main.post { onFrameSize(size) }
                    }
                })
                setEnableHardwareScaler(true)
                renderer = this
            }
        },
        update = {
            it.setScalingType(if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        },
        onRelease = { it.release() },
    )
    DisposableEffect(track, renderer) {
        val r = renderer
        if (r != null) track.addSink(r)
        onDispose { if (r != null) runCatching { track.removeSink(r) } }
    }
}

@Composable
private fun Overlay(ui: ControllerUiState) {
    val text = when {
        ui.hostState?.paused == true -> "The host paused screen sharing."
        ui.state == SessionState.RECONNECTING -> "Connection unstable · reconnecting…"
        ui.state == SessionState.NEGOTIATING || ui.state == SessionState.CONNECTING_MEDIA -> "Connecting video…"
        else -> null
    }
    if (text != null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ui.hostState?.paused != true) CircularProgressIndicator()
                Text(text, color = Color.White)
            }
        }
    }
}

@Composable
private fun Toolbar(
    ui: ControllerUiState,
    fill: Boolean,
    onToggleFit: () -> Unit,
    onRotate: () -> Unit,
    onGlobal: (GlobalAction) -> Unit,
    onKeyboard: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val caps = ui.capabilities
    Column(Modifier.fillMaxWidth().background(RemoColors.Navy900.copy(alpha = 0.85f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(StateLabels.controller(ui.state), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("session_state"))
                Text(
                    listOfNotNull(
                        ui.peerName,
                        ui.permission?.label,
                        ui.quality?.let { "Quality ${it.label}" },
                        ui.stats?.relayed?.let { if (it) "relay" else "direct" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = RemoColors.TextSecondary,
                )
            }
            IconButton(onClick = onDisconnect, modifier = Modifier.testTag("disconnect")) {
                Icon(Icons.Filled.CallEnd, contentDescription = "Disconnect", tint = RemoColors.Danger)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleFit) {
                Icon(if (fill) Icons.Filled.FitScreen else Icons.Filled.CropFree, contentDescription = if (fill) "Fit to screen" else "Fill screen")
            }
            IconButton(onClick = onRotate) { Icon(Icons.Filled.ScreenRotation, contentDescription = "Rotate") }
            if (Capability.NAVIGATION in caps) {
                IconButton(onClick = { onGlobal(GlobalAction.BACK) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                IconButton(onClick = { onGlobal(GlobalAction.HOME) }) { Icon(Icons.Filled.Home, contentDescription = "Home") }
                IconButton(onClick = { onGlobal(GlobalAction.RECENTS) }) { Icon(Icons.Filled.ViewAgenda, contentDescription = "Recent apps") }
            }
            if (Capability.SYSTEM_PANELS in caps) {
                IconButton(onClick = { onGlobal(GlobalAction.NOTIFICATIONS) }) {
                    Icon(Icons.Filled.Notifications, contentDescription = "Notifications")
                }
            }
            if (Capability.TEXT_INPUT in caps) {
                IconButton(onClick = onKeyboard) { Icon(Icons.Filled.Keyboard, contentDescription = "Type text") }
            }
        }
        ui.hostState?.controlNote?.takeIf { caps.none { it != Capability.VIEW_SCREEN } && ui.permission?.ordinal != 0 }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = RemoColors.Warning)
        }
    }
}

@Composable
private fun TypeTextDialog(onSend: (String) -> Unit, onKey: (Key) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type on the remote device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Text is inserted into the field that is focused on the other device.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onKey(Key.BACKSPACE) }) { Text("⌫") }
                    OutlinedButton(onClick = { onKey(Key.ENTER) }) { Text("Enter") }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (text.isNotEmpty()) onSend(text)
                text = ""
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
