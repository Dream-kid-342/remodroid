package com.remodroid.windows.tools

import com.remodroid.shared.protocol.IceServer
import com.remodroid.shared.rtc.RtcConfig
import com.remodroid.shared.rtc.RtcConnectionState
import com.remodroid.shared.rtc.RtcEvent
import com.remodroid.windows.rtc.DesktopHostPeer
import com.remodroid.windows.rtc.DesktopViewerPeer
import dev.onvoid.webrtc.PeerConnectionFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * Local check of the Windows host media path: captures this desktop with DesktopHostPeer and
 * receives it in a DesktopViewerPeer in the same process (no signaling server, no input injection).
 * Usage: smokeLoopback [seconds]. Exits 0 if frames were decoded.
 */
fun main(args: Array<String>): Unit = runBlocking<Unit> {
    val seconds = args.getOrNull(0)?.toInt() ?: 5
    val factory = PeerConnectionFactory()
    val config = RtcConfig(listOf(IceServer(listOf("stun:stun.l.google.com:19302"))))
    val host = DesktopHostPeer(factory, config)
    val viewer = DesktopViewerPeer(factory, config)

    // Trickle candidates directly between the two peers.
    val jobs = listOf(
        launch { host.events.collect { if (it is RtcEvent.LocalCandidate) viewer.addRemoteCandidate(it.candidate) else println("[host] $it") } },
        launch { viewer.events.collect { if (it is RtcEvent.LocalCandidate) host.addRemoteCandidate(it.candidate) else println("[viewer] $it") } },
    )
    val answer = viewer.acceptOffer(host.createOffer(iceRestart = false))
    host.acceptAnswer(answer)

    val first = withTimeoutOrNull(20_000) { viewer.frame.first { it != null } }
    if (first == null) {
        println("RESULT: FAILED (no frame within 20 s)")
        exitProcess(1)
    }
    println("[media] first frame ${first.width}x${first.height}; screen=${host.screen.value}")
    repeat(seconds) {
        delay(1000)
        val s = viewer.stats()
        println("[media] frames=${viewer.frame.value?.serial} fps=${s?.framesPerSecond} kbps=${s?.bitrateKbps?.toInt()} rtt=${s?.roundTripMs}")
    }
    host.setVideoEnabled(false)
    println("[privacy] video disabled (black frames)")
    delay(1000)
    val total = viewer.frame.value?.serial ?: 0
    jobs.forEach { it.cancel() }
    viewer.close()
    host.close()
    factory.dispose()
    println("RESULT: OK ($total frames decoded)")
    exitProcess(0)
}
