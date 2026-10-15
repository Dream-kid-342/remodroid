package com.remodroid.windows.tools

import com.remodroid.shared.control.NormalizedPoint
import com.remodroid.shared.control.Tap
import com.remodroid.shared.control.GlobalAction
import com.remodroid.shared.control.GlobalActionCommand
import com.remodroid.shared.control.Swipe
import com.remodroid.shared.control.TextInput
import com.remodroid.shared.models.PermissionLevel
import com.remodroid.shared.session.ControllerAgent
import com.remodroid.shared.session.SessionState
import com.remodroid.shared.signaling.SignalingClient
import com.remodroid.windows.rtc.DesktopRtcFactory
import com.remodroid.windows.rtc.DesktopViewerPeer
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * Headless end-to-end check: real signaling, real WebRTC (webrtc-java), real decoding.
 * Usage: smokeController <signalingUrl> <partnerId> <password> [permission] [holdSeconds]
 * Exits 0 only if video frames were decoded from the partner.
 */
fun main(args: Array<String>): Unit = runBlocking<Unit> {
    if (args.size < 3) {
        System.err.println("usage: <url> <partnerId> <password> [view_only|guided_support|extended_support|maximum_supported] [holdSeconds]")
        exitProcess(2)
    }
    val (url, id, password) = args
    val level = args.getOrNull(3)?.let { a -> PermissionLevel.entries.first { it.name.equals(a, true) } } ?: PermissionLevel.GUIDED_SUPPORT
    val hold = args.getOrNull(4)?.toLong() ?: 10

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    val factory = DesktopRtcFactory()
    val signaling = SignalingClient({ url }, scope, log = { println("[signaling] $it") })
    val agent = ControllerAgent(signaling, factory, scope, { "Smoke test (Windows)" }, log = { println("[agent] $it") })

    val stateLogger = scope.launch { agent.ui.collect { println("[state] ${it.state} host=${it.hostState?.let { h -> "paused=${h.paused} perm=${h.permission} caps=${h.capabilities.size}" }}${it.message?.let { m -> " – $m" } ?: ""}") } }
    agent.connect(id, password, level)

    val connected = withTimeoutOrNull(120_000) {
        agent.ui.first { it.state.isConnected || it.state == SessionState.FAILED || it.state == SessionState.DISCONNECTED }
    }
    if (connected == null || !connected.state.isConnected) {
        println("RESULT: FAILED (${connected?.message ?: "timeout"})")
        exitProcess(1)
    }
    val peer = agent.peer.value as DesktopViewerPeer
    println("[media] connected; host=${connected.hostState?.platform} caps=${connected.capabilities}")

    var lastSerial = 0L
    repeat(hold.toInt()) {
        delay(1000)
        val f = peer.frame.value
        val s = agent.ui.value.stats
        println(
            "[media] frame=${f?.width}x${f?.height} frames=${(f?.serial ?: 0) - lastSerial}/s " +
                "rtt=${s?.roundTripMs?.toInt()}ms kbps=${s?.bitrateKbps?.toInt()} relayed=${s?.relayed} quality=${agent.ui.value.quality}",
        )
        lastSerial = f?.serial ?: 0
    }
    // Optional 6th argument: comma-separated actions, e.g. "home,wait,back,tap:0.5:0.5,swipe:0.5:0.8:0.5:0.2".
    val actions = args.getOrNull(5)?.split(',')?.filter { it.isNotBlank() } ?: listOf("tap:0.5:0.5")
    for (action in actions) {
        val parts = action.split(':')
        val sent = when (parts[0]) {
            "wait" -> { delay(3000); true }
            "home" -> agent.send { GlobalActionCommand(it, GlobalAction.HOME) }
            "back" -> agent.send { GlobalActionCommand(it, GlobalAction.BACK) }
            "recents" -> agent.send { GlobalActionCommand(it, GlobalAction.RECENTS) }
            "notifications" -> agent.send { GlobalActionCommand(it, GlobalAction.NOTIFICATIONS) }
            "tap" -> agent.send { Tap(it, NormalizedPoint(parts[1].toFloat(), parts[2].toFloat())) }
            "swipe" -> agent.send {
                Swipe(it, listOf(NormalizedPoint(parts[1].toFloat(), parts[2].toFloat()), NormalizedPoint(parts[3].toFloat(), parts[4].toFloat())), 300)
            }
            "text" -> agent.send { TextInput(it, parts.drop(1).joinToString(":")) }
            // snapshot:<path> saves the last decoded remote frame as PNG.
            "snapshot" -> {
                val f = (agent.peer.value as? DesktopViewerPeer)?.frame?.value
                if (f != null) {
                    val png = org.jetbrains.skia.Image.makeFromBitmap(f.image.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)
                    png?.bytes?.let { java.io.File(parts.drop(1).joinToString(":")).writeBytes(it) }
                }
                f != null
            }
            // tapfile:<path> waits (up to 60 s) for a file containing "x y" (normalised) written by a test script.
            "tapfile" -> {
                val file = java.io.File(parts.drop(1).joinToString(":"))
                val deadline = System.currentTimeMillis() + 60_000
                while (!file.exists() && System.currentTimeMillis() < deadline) delay(200)
                delay(200)
                val (x, y) = file.readText().trim().split(Regex("\\s+")).map { it.toFloat() }
                agent.send { Tap(it, NormalizedPoint(x, y)) }
            }
            else -> false
        }
        delay(1500)
        println("[input] $action sent=$sent problem=${agent.ui.value.lastInputProblem}")
    }

    val totalFrames = peer.frame.value?.serial ?: 0
    agent.disconnect().join()
    delay(500)
    stateLogger.cancel()
    signaling.stop()
    println(if (totalFrames > 0) "RESULT: OK ($totalFrames frames decoded)" else "RESULT: FAILED (no frames)")
    exitProcess(if (totalFrames > 0) 0 else 1)
}

