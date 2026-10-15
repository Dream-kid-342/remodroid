package com.remodroid.shared.rtc

import com.remodroid.shared.control.InputCommand
import com.remodroid.shared.control.ScreenInfo
import com.remodroid.shared.models.Capability
import com.remodroid.shared.models.HostPlatform
import com.remodroid.shared.protocol.IceCandidate
import com.remodroid.shared.protocol.IceServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-neutral view of a WebRTC peer connection. Android implements it with org.webrtc
 * (Stream WebRTC), Windows with dev.onvoid.webrtc (webrtc-java). Both wrap Google's libwebrtc,
 * so they interoperate on the wire.
 */
enum class RtcConnectionState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

sealed interface RtcEvent {
    data class LocalCandidate(val candidate: IceCandidate) : RtcEvent
    data class ConnectionChanged(val state: RtcConnectionState) : RtcEvent
    data object ControlChannelOpen : RtcEvent
    data object ControlChannelClosed : RtcEvent
    data class ControlMessageReceived(val text: String) : RtcEvent

    /** Viewer only: the first remote video frame has been decoded and rendered. */
    data object FirstFrameRendered : RtcEvent
}

data class RtcConfig(
    val iceServers: List<IceServer>,
    /** Force TURN relay (iceTransportPolicy=relay). Used to verify relay connectivity. */
    val relayOnly: Boolean = false,
    val quality: VideoQuality = VideoQuality.AUTO,
)

/** Capture and encoder targets. WebRTC's congestion control adapts below these limits. */
enum class VideoQuality(val label: String, val maxLongEdge: Int, val maxFps: Int, val maxBitrateKbps: Int) {
    AUTO("Auto (up to 720p, 30 fps)", 1280, 30, 2500),
    BALANCED("Balanced (540p, 24 fps)", 960, 24, 1200),
    DATA_SAVER("Data saver (480p, 15 fps)", 854, 15, 600),
}

data class RtcStats(
    val roundTripMs: Double?,
    val packetLossPercent: Double?,
    val bitrateKbps: Double?,
    val framesPerSecond: Double?,
    val frameWidth: Int?,
    val frameHeight: Int?,
    /** True when the selected candidate pair goes through a TURN relay. */
    val relayed: Boolean?,
)

enum class ConnectionQuality(val label: String) {
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor"),
    ;

    companion object {
        fun from(stats: RtcStats?): ConnectionQuality? {
            if (stats == null) return null
            val rtt = stats.roundTripMs
            val loss = stats.packetLossPercent
            return when {
                (rtt != null && rtt > 600) || (loss != null && loss > 8) -> POOR
                (rtt != null && rtt > 250) || (loss != null && loss > 2) -> FAIR
                rtt == null && loss == null -> null
                else -> GOOD
            }
        }
    }
}

interface RtcPeer {
    /**
     * Single-consumer event stream. Implementations must buffer events until collected
     * (e.g. `Channel(UNLIMITED).receiveAsFlow()`): agents start collecting on their serial scope
     * after calls such as [createOffer] that already produce ICE candidates.
     */
    val events: Flow<RtcEvent>

    /** Creates an offer, sets it as the local description and returns its SDP. */
    suspend fun createOffer(iceRestart: Boolean): String

    /** Sets the remote offer, creates and sets an answer, and returns its SDP. */
    suspend fun acceptOffer(sdp: String): String

    suspend fun acceptAnswer(sdp: String)

    fun addRemoteCandidate(candidate: IceCandidate)

    /** Sends over the control data channel. Returns false if the channel is not open. */
    fun sendControl(text: String): Boolean

    suspend fun stats(): RtcStats?

    /** Releases the peer connection and all associated media resources. Idempotent. */
    fun close()
}

interface HostRtcPeer : RtcPeer {
    /** Current captured screen dimensions/rotation. */
    val screen: StateFlow<ScreenInfo?>

    /** Privacy pause: stop sending real frames without tearing down the connection. */
    fun setVideoEnabled(enabled: Boolean)
}

interface RtcFactory {
    /**
     * Creates the host side: captures the screen, adds the video track and opens the control channel.
     * On Android the caller must already hold MediaProjection consent.
     */
    suspend fun createHostPeer(config: RtcConfig): HostRtcPeer

    /** Creates the controller side, which receives video and accepts the control channel. */
    suspend fun createViewerPeer(config: RtcConfig): RtcPeer
}

sealed interface InjectionResult {
    data object Ok : InjectionResult
    data class Unsupported(val detail: String) : InjectionResult
    data class Failed(val detail: String) : InjectionResult
}

/** Host-side input backend: AccessibilityService on Android, SendInput on Windows. */
interface InputInjector {
    val platform: HostPlatform

    /** Capabilities the device can perform right now (changes, e.g., when the accessibility service is toggled). */
    val supported: StateFlow<Set<Capability>>

    /** Why some capabilities are unavailable, for display on both sides. */
    val note: StateFlow<String?>

    suspend fun inject(command: InputCommand, screen: ScreenInfo?): InjectionResult
}
