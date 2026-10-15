package com.remodroid.android.rtc

import com.remodroid.shared.rtc.RtcConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.VideoTrack

/** Receives the remote screen. The UI attaches a renderer to [remoteTrack]. */
class AndroidViewerPeer(factory: PeerConnectionFactory, config: RtcConfig) : AndroidPeer(factory, config) {
    override val isSender = false

    private val _remoteTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteTrack: StateFlow<VideoTrack?> = _remoteTrack.asStateFlow()

    override fun onRemoteTrack(transceiver: RtpTransceiver) {
        val track = transceiver.receiver.track() as? VideoTrack ?: return
        _remoteTrack.value = track
    }

    override fun close() {
        _remoteTrack.value = null
        super.close()
    }
}
