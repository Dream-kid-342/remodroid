package com.remodroid.windows.rtc

import com.remodroid.shared.protocol.IceCandidate
import com.remodroid.shared.rtc.RtcConfig
import com.remodroid.shared.rtc.RtcConnectionState
import com.remodroid.shared.rtc.RtcEvent
import com.remodroid.shared.rtc.RtcPeer
import com.remodroid.shared.rtc.RtcStats
import com.remodroid.shared.rtc.StatEntry
import com.remodroid.shared.rtc.StatsParser
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCBundlePolicy
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCIceTransportPolicy
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtcpMuxPolicy
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RtcException(message: String) : Exception(message)

/** webrtc-java (dev.onvoid.webrtc) plumbing shared by the desktop host and viewer. */
abstract class DesktopPeer(factory: PeerConnectionFactory, config: RtcConfig) : RtcPeer {
    private val channel = Channel<RtcEvent>(Channel.UNLIMITED)
    final override val events: Flow<RtcEvent> = channel.receiveAsFlow()

    protected val closed = AtomicBoolean(false)
    private val statsParser = StatsParser()
    protected abstract val isSender: Boolean

    @Volatile
    private var dataChannel: RTCDataChannel? = null

    protected val pc: RTCPeerConnection

    init {
        val rtcConfig = RTCConfiguration().apply {
            iceServers = config.iceServers.map { s ->
                RTCIceServer().apply {
                    urls = s.urls
                    username = s.username
                    password = s.credential
                }
            }
            iceTransportPolicy = if (config.relayOnly) RTCIceTransportPolicy.RELAY else RTCIceTransportPolicy.ALL
            bundlePolicy = RTCBundlePolicy.MAX_BUNDLE
            rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE
        }
        pc = factory.createPeerConnection(rtcConfig, Observer()) ?: throw RtcException("Could not create a peer connection")
    }

    protected fun emit(event: RtcEvent) {
        if (!closed.get()) channel.trySend(event)
    }

    protected fun attachDataChannel(dc: RTCDataChannel) {
        dataChannel = dc
        dc.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(sentDataSize: Long) = Unit
            override fun onStateChange() {
                when (dc.state) {
                    RTCDataChannelState.OPEN -> emit(RtcEvent.ControlChannelOpen)
                    RTCDataChannelState.CLOSED -> emit(RtcEvent.ControlChannelClosed)
                    else -> Unit
                }
            }

            override fun onMessage(buffer: RTCDataChannelBuffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                emit(RtcEvent.ControlMessageReceived(bytes.toString(Charsets.UTF_8)))
            }
        })
        if (dc.state == RTCDataChannelState.OPEN) emit(RtcEvent.ControlChannelOpen)
    }

    protected open fun onRemoteTrack(transceiver: RTCRtpTransceiver) = Unit

    override suspend fun createOffer(iceRestart: Boolean): String {
        val offer = createSdp { obs -> pc.createOffer(RTCOfferOptions().apply { this.iceRestart = iceRestart }, obs) }
        setSdp { obs -> pc.setLocalDescription(offer, obs) }
        return offer.sdp
    }

    override suspend fun acceptOffer(sdp: String): String {
        setSdp { obs -> pc.setRemoteDescription(RTCSessionDescription(RTCSdpType.OFFER, sdp), obs) }
        val answer = createSdp { obs -> pc.createAnswer(RTCAnswerOptions(), obs) }
        setSdp { obs -> pc.setLocalDescription(answer, obs) }
        return answer.sdp
    }

    override suspend fun acceptAnswer(sdp: String) {
        setSdp { obs -> pc.setRemoteDescription(RTCSessionDescription(RTCSdpType.ANSWER, sdp), obs) }
    }

    override fun addRemoteCandidate(candidate: IceCandidate) {
        if (closed.get()) return
        pc.addIceCandidate(dev.onvoid.webrtc.RTCIceCandidate(candidate.sdpMid ?: "0", candidate.sdpMLineIndex ?: 0, candidate.candidate))
    }

    override fun sendControl(text: String): Boolean {
        val dc = dataChannel ?: return false
        if (closed.get() || dc.state != RTCDataChannelState.OPEN) return false
        return try {
            dc.send(RTCDataChannelBuffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun stats(): RtcStats? {
        if (closed.get()) return null
        val entries = suspendCancellableCoroutine { cont ->
            pc.getStats { report ->
                cont.resume(
                    report.stats.values.map {
                        // RTCStatsType.INBOUND_RTP -> "inbound-rtp" (the W3C name used by the shared parser).
                        StatEntry(it.id, it.type.name.lowercase().replace('_', '-'), it.attributes)
                    },
                )
            }
        }
        return statsParser.parse(entries, System.currentTimeMillis(), isSender)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataChannel?.let {
            it.unregisterObserver()
            it.close()
            it.dispose()
        }
        dataChannel = null
        pc.close()
        channel.close()
    }

    private inner class Observer : PeerConnectionObserver {
        override fun onIceCandidate(candidate: dev.onvoid.webrtc.RTCIceCandidate) {
            emit(RtcEvent.LocalCandidate(IceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)))
        }

        override fun onConnectionChange(state: RTCPeerConnectionState) {
            val mapped = when (state) {
                RTCPeerConnectionState.NEW -> RtcConnectionState.NEW
                RTCPeerConnectionState.CONNECTING -> RtcConnectionState.CONNECTING
                RTCPeerConnectionState.CONNECTED -> RtcConnectionState.CONNECTED
                RTCPeerConnectionState.DISCONNECTED -> RtcConnectionState.DISCONNECTED
                RTCPeerConnectionState.FAILED -> RtcConnectionState.FAILED
                RTCPeerConnectionState.CLOSED -> RtcConnectionState.CLOSED
            }
            emit(RtcEvent.ConnectionChanged(mapped))
        }

        override fun onDataChannel(dataChannel: RTCDataChannel) = attachDataChannel(dataChannel)
        override fun onTrack(transceiver: RTCRtpTransceiver) = onRemoteTrack(transceiver)
    }
}

private suspend fun createSdp(block: (CreateSessionDescriptionObserver) -> Unit): RTCSessionDescription =
    suspendCancellableCoroutine { cont ->
        block(object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) = cont.resume(description)
            override fun onFailure(error: String?) = cont.resumeWithException(RtcException(error ?: "createSdp failed"))
        })
    }

private suspend fun setSdp(block: (SetSessionDescriptionObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
    block(object : SetSessionDescriptionObserver {
        override fun onSuccess() = cont.resume(Unit)
        override fun onFailure(error: String?) = cont.resumeWithException(RtcException(error ?: "setSdp failed"))
    })
}
