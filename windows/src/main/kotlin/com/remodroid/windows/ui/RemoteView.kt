package com.remodroid.windows.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key as ComposeKey
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.remodroid.shared.control.ControlCodec
import com.remodroid.shared.control.CoordinateMapper
import com.remodroid.shared.control.GestureClassifier
import com.remodroid.shared.control.GlobalAction
import com.remodroid.shared.control.GlobalActionCommand
import com.remodroid.shared.control.Key
import com.remodroid.shared.control.KeyCommand
import com.remodroid.shared.control.MouseButton
import com.remodroid.shared.control.MouseButtonEvent
import com.remodroid.shared.control.MouseMove
import com.remodroid.shared.control.NormalizedPoint
import com.remodroid.shared.control.PointerSample
import com.remodroid.shared.control.Swipe
import com.remodroid.shared.control.TextInput
import com.remodroid.shared.control.ViewRect
import com.remodroid.shared.control.Wheel
import com.remodroid.shared.models.Capability
import com.remodroid.shared.models.HostPlatform
import com.remodroid.shared.session.ControllerAgent
import com.remodroid.shared.session.ControllerUiState
import com.remodroid.shared.session.SessionState
import com.remodroid.shared.session.StateLabels
import com.remodroid.windows.rtc.DesktopViewerPeer
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RemoteView(ui: ControllerUiState, agent: ControllerAgent) {
    val peer by agent.peer.collectAsState()
    val frame = (peer as? DesktopViewerPeer)?.frame?.collectAsState()?.value
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var fill by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val caps = ui.capabilities
    val hostPlatform = ui.hostState?.platform
    val pointerAllowed = Capability.POINTER in caps

    val rect: ViewRect? = frame?.let {
        (if (fill) CoordinateMapper::fillRect else CoordinateMapper::fitRect)(it.width, it.height, viewSize.width.toFloat(), viewSize.height.toFloat())
    }
    // Android hosts receive gestures built from a whole drag; Windows hosts get raw mouse events.
    val drag = remember { mutableListOf<PointerSample>() }
    var lastMoveSent by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Toolbar(ui, fill, { fill = !fill }, agent)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewSize = it }
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e -> handleKey(e, agent, hostPlatform, caps) }
                .onPointerEvent(PointerEventType.Press) { e ->
                    runCatching { focus.requestFocus() }
                    val r = rect ?: return@onPointerEvent
                    if (!pointerAllowed) return@onPointerEvent
                    val c = e.changes.first()
                    val p = CoordinateMapper.viewToNormalized(c.position.x, c.position.y, r) ?: return@onPointerEvent
                    if (hostPlatform == HostPlatform.WINDOWS) {
                        agent.send { MouseButtonEvent(it, p, e.button.toMouseButton(), down = true) }
                    } else if (e.button == PointerButton.Secondary) {
                        agent.send { GlobalActionCommand(it, GlobalAction.BACK) }
                    } else {
                        drag.clear()
                        drag += PointerSample(p, c.uptimeMillis)
                    }
                }
                .onPointerEvent(PointerEventType.Move) { e ->
                    val r = rect ?: return@onPointerEvent
                    if (!pointerAllowed) return@onPointerEvent
                    val c = e.changes.first()
                    val p = CoordinateMapper.viewToNormalizedClamped(c.position.x, c.position.y, r)
                    if (hostPlatform == HostPlatform.WINDOWS) {
                        // ~60 Hz is plenty and keeps the data channel light on mobile links.
                        if (c.uptimeMillis - lastMoveSent >= 16) {
                            lastMoveSent = c.uptimeMillis
                            agent.send { MouseMove(it, p) }
                        }
                    } else if (drag.isNotEmpty()) {
                        drag += PointerSample(p, c.uptimeMillis)
                    }
                }
                .onPointerEvent(PointerEventType.Release) { e ->
                    val r = rect ?: return@onPointerEvent
                    if (!pointerAllowed) return@onPointerEvent
                    val c = e.changes.first()
                    val p = CoordinateMapper.viewToNormalizedClamped(c.position.x, c.position.y, r)
                    if (hostPlatform == HostPlatform.WINDOWS) {
                        agent.send { MouseButtonEvent(it, p, e.button.toMouseButton(), down = false) }
                    } else if (drag.isNotEmpty()) {
                        drag += PointerSample(p, c.uptimeMillis)
                        GestureClassifier.classify(drag.toList(), r.width, r.height, slopPx = 8f)?.let { g ->
                            agent.send { GestureClassifier.toCommand(g, it) }
                        }
                        drag.clear()
                    }
                }
                .onPointerEvent(PointerEventType.Scroll) { e ->
                    val r = rect ?: return@onPointerEvent
                    if (!pointerAllowed) return@onPointerEvent
                    val c = e.changes.first()
                    val p = CoordinateMapper.viewToNormalizedClamped(c.position.x, c.position.y, r)
                    val dy = c.scrollDelta.y
                    if (hostPlatform == HostPlatform.WINDOWS) {
                        agent.send { Wheel(it, p, c.scrollDelta.x, dy) }
                    } else if (dy != 0f) {
                        // Wheel on an Android host: a short vertical swipe (wheel down = content moves up).
                        val end = NormalizedPoint(p.x, (p.y - 0.15f * dy.coerceIn(-1f, 1f)).coerceIn(0f, 1f))
                        agent.send { Swipe(it, listOf(p, end), 200) }
                    }
                },
        ) {
            if (frame != null && rect != null) {
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(
                        frame.image,
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                    )
                }
            }
            val overlay = when {
                ui.hostState?.paused == true -> "The host paused screen sharing."
                ui.state == SessionState.RECONNECTING -> "Connection unstable · reconnecting…"
                frame == null -> "Connecting video…"
                else -> null
            }
            overlay?.let {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (ui.hostState?.paused != true) CircularProgressIndicator()
                    Text(it, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun Toolbar(ui: ControllerUiState, fill: Boolean, onToggleFit: () -> Unit, agent: ControllerAgent) {
    val caps = ui.capabilities
    Column(Modifier.fillMaxWidth().background(RemoColors.Navy900).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(StateLabels.controller(ui.state), style = MaterialTheme.typography.labelLarge)
                Text(
                    listOfNotNull(
                        ui.peerName,
                        ui.permission?.label,
                        ui.quality?.let { "Quality ${it.label}" },
                        ui.stats?.roundTripMs?.let { "${it.roundToInt()} ms" },
                        ui.stats?.relayed?.let { if (it) "relay" else "direct" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = RemoColors.TextSecondary,
                )
            }
            IconButton(onClick = onToggleFit) {
                Icon(if (fill) Icons.Filled.FitScreen else Icons.Filled.CropFree, contentDescription = if (fill) "Fit to window" else "Fill window")
            }
            if (Capability.NAVIGATION in caps) {
                IconButton(onClick = { agent.send { GlobalActionCommand(it, GlobalAction.BACK) } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                IconButton(onClick = { agent.send { GlobalActionCommand(it, GlobalAction.HOME) } }) { Icon(Icons.Filled.Home, "Home") }
                IconButton(onClick = { agent.send { GlobalActionCommand(it, GlobalAction.RECENTS) } }) { Icon(Icons.Filled.ViewAgenda, "Recent apps") }
            }
            if (Capability.SYSTEM_PANELS in caps) {
                IconButton(onClick = { agent.send { GlobalActionCommand(it, GlobalAction.NOTIFICATIONS) } }) { Icon(Icons.Filled.Notifications, "Notifications") }
            }
            IconButton(onClick = { agent.disconnect() }) { Icon(Icons.Filled.CallEnd, contentDescription = "Disconnect", tint = RemoColors.Danger) }
        }
        ui.lastInputProblem?.let { Text(it, color = RemoColors.Warning, style = MaterialTheme.typography.bodySmall) }
        ui.hostState?.controlNote?.takeIf { caps.none { c -> c != Capability.VIEW_SCREEN } }?.let {
            Text(it, color = RemoColors.Warning, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun PointerButton?.toMouseButton() = when (this) {
    PointerButton.Secondary -> MouseButton.RIGHT
    PointerButton.Tertiary -> MouseButton.MIDDLE
    else -> MouseButton.LEFT
}

private val specialKeys = mapOf(
    ComposeKey.Enter to Key.ENTER, ComposeKey.Backspace to Key.BACKSPACE, ComposeKey.Delete to Key.DELETE,
    ComposeKey.Tab to Key.TAB, ComposeKey.Escape to Key.ESCAPE, ComposeKey.DirectionLeft to Key.ARROW_LEFT,
    ComposeKey.DirectionRight to Key.ARROW_RIGHT, ComposeKey.DirectionUp to Key.ARROW_UP, ComposeKey.DirectionDown to Key.ARROW_DOWN,
    ComposeKey.MoveHome to Key.HOME, ComposeKey.MoveEnd to Key.END, ComposeKey.PageUp to Key.PAGE_UP, ComposeKey.PageDown to Key.PAGE_DOWN,
    ComposeKey.ShiftLeft to Key.SHIFT, ComposeKey.ShiftRight to Key.SHIFT, ComposeKey.CtrlLeft to Key.CONTROL,
    ComposeKey.CtrlRight to Key.CONTROL, ComposeKey.AltLeft to Key.ALT, ComposeKey.AltRight to Key.ALT,
)

/** Keyboard forwarding: printable characters as text, editing/navigation keys as key events. */
private fun handleKey(e: KeyEvent, agent: ControllerAgent, host: HostPlatform?, caps: Set<Capability>): Boolean {
    if (Capability.TEXT_INPUT !in caps) return false
    val special = specialKeys[e.key]
    if (special != null) {
        when (e.type) {
            KeyEventType.KeyDown -> agent.send { KeyCommand(it, special, down = true) }
            KeyEventType.KeyUp -> if (host == HostPlatform.WINDOWS) agent.send { KeyCommand(it, special, down = false) }
        }
        return true
    }
    if (e.type == KeyEventType.KeyDown && !e.isCtrlPressed) {
        val cp = e.utf16CodePoint
        if (cp >= 0x20 && cp != 0x7F) {
            val text = String(Character.toChars(cp)).take(ControlCodec.MAX_TEXT_LENGTH)
            agent.send { TextInput(it, text) }
            return true
        }
    }
    return false
}
