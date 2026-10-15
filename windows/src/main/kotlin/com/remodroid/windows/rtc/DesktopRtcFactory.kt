package com.remodroid.windows.rtc

import com.remodroid.shared.rtc.HostRtcPeer
import com.remodroid.shared.rtc.RtcConfig
import com.remodroid.shared.rtc.RtcFactory
import com.remodroid.shared.rtc.RtcPeer
import dev.onvoid.webrtc.PeerConnectionFactory

class DesktopRtcFactory : RtcFactory {
    /** Loads the native libwebrtc library on first use. */
    private val factory: PeerConnectionFactory by lazy { PeerConnectionFactory() }

    override suspend fun createHostPeer(config: RtcConfig): HostRtcPeer = DesktopHostPeer(factory, config)

    override suspend fun createViewerPeer(config: RtcConfig): RtcPeer = DesktopViewerPeer(factory, config)

    fun dispose() {
        runCatching { factory.dispose() }
    }
}
