package com.remodroid.windows.rtc

import com.remodroid.shared.control.CONTROL_CHANNEL_LABEL
import com.remodroid.shared.control.CoordinateMapper
import com.remodroid.shared.control.ScreenInfo
import com.remodroid.shared.rtc.HostRtcPeer
import com.remodroid.shared.rtc.RtcConfig
import com.remodroid.windows.input.VirtualScreen
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.media.video.VideoDesktopSource
import dev.onvoid.webrtc.media.video.VideoTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Captures the Windows desktop with libwebrtc's desktop capturer and sends it.
 *
 * Source id -1 is libwebrtc's "full desktop" (all monitors as one frame), which matches the
 * virtual-screen coordinate space used by SendInput, so remote input lines up on multi-monitor setups.
 */
class DesktopHostPeer(
    factory: PeerConnectionFactory,
    private val config: RtcConfig,
) : DesktopPeer(factory, config), HostRtcPeer {
    override val isSender = true

    private val _screen = MutableStateFlow<ScreenInfo?>(VirtualScreen.current().let { ScreenInfo(it.width, it.height, 0) })
    override val screen: StateFlow<ScreenInfo?> = _screen.asStateFlow()

    private val source = VideoDesktopSource()
    private val track: VideoTrack
    private val sender: dev.onvoid.webrtc.RTCRtpSender

    init {
        val info = _screen.value!!
        val (w, h) = CoordinateMapper.captureSize(info.width, info.height, config.quality.maxLongEdge)
        try {
            source.setSourceId(FULL_DESKTOP_ID, false)
            source.setFrameRate(config.quality.maxFps)
            source.setMaxFrameSize(w, h)
            source.start()
        } catch (e: Exception) {
            super.close()
            source.dispose()
            throw e
        }
        track = factory.createVideoTrack("remodroid-desktop", source)
        sender = pc.addTrack(track, listOf("remodroid"))
        runCatching {
            val params = sender.parameters
            params.encodings?.forEach {
                it.maxBitrate = config.quality.maxBitrateKbps * 1000
                it.maxFramerate = config.quality.maxFps.toDouble()
            }
            sender.parameters = params
        }
        attachDataChannel(pc.createDataChannel(CONTROL_CHANNEL_LABEL, RTCDataChannelInit().apply { ordered = true }))
    }

    /** Re-reads the virtual screen size (e.g. after a resolution change). */
    fun refreshScreen() {
        _screen.value = VirtualScreen.current().let { ScreenInfo(it.width, it.height, 0) }
    }

    override fun setVideoEnabled(enabled: Boolean) {
        if (!closed.get()) track.isEnabled = enabled
    }

    override fun close() {
        if (closed.get()) return
        runCatching { source.stop() }
        // The sender holds a native reference to the track; detach it so the track can be disposed.
        runCatching { pc.removeTrack(sender) }
        super.close()
        track.dispose()
        source.dispose()
    }

    companion object {
        const val FULL_DESKTOP_ID = -1L
    }
}
