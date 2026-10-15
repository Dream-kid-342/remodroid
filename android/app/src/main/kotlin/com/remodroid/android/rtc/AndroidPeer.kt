package com.remodroid.android.rtc

import com.remodroid.shared.protocol.IceCandidate
import com.remodroid.shared.rtc.RtcConfig
import com.remodroid.shared.rtc.RtcConnectionState
import com.remodroid.shared.rtc.RtcEvent
import com.remodroid.shared.rtc.RtcPeer
import com.remodroid.shared.rtc.RtcStats
import com.remodroid.shared.rtc.StatEntry
import com.remodroid.shared.rtc.StatsParser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RtcException(message: String) : Exception(message)

/** Shared org.webrtc plumbing for the host (sender) and viewer (receiver) sides. */
abstract class AndroidPeer(factory: PeerConnectionFactory, config: RtcConfig) : RtcPeer {
    private val channel = Channel<RtcEvent>(Channel.UNLIMITED)
    final override val events: Flow<RtcEvent> = channel.receiveAsFlow()

    protected val closed = AtomicBoolean(false)
    private val statsParser = StatsParser()
    protected abstract val isSender: Boolean

    @Volatile
    private var dataChannel: DataChannel? = null

    protected val pc: PeerConnection

    init {
        val rtcConfig = PeerConnection.RTCConfiguration(
            config.iceServers.map { s ->
                PeerConnection.IceServer.builder(s.urls).apply {
                    s.username?.let(::setUsername)
                    s.credential?.let(::setPassword)
                }.createIceServer()
            },
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = if (config.relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Keep gathering so candidates for a new network (Wi-Fi <-> mobile data) appear without a full restart.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        pc = factory.createPeerConnection(rtcConfig, Observer()) ?: throw RtcException("Could not create a peer connection")
    }

    protected fun emit(event: RtcEvent) {
        if (!closed.get()) channel.trySend(event)
    }

    protected fun attachDataChannel(dc: DataChannel) {
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                when (dc.state()) {
                    DataChannel.State.OPEN -> emit(RtcEvent.ControlChannelOpen)
                    DataChannel.State.CLOSED -> emit(RtcEvent.ControlChannelClosed)
                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                emit(RtcEvent.ControlMessageReceived(bytes.toString(Charsets.UTF_8)))
            }
        })
        if (dc.state() == DataChannel.State.OPEN) emit(RtcEvent.ControlChannelOpen)
    }

    protected open fun onRemoteTrack(transceiver: RtpTransceiver) = Unit

    override suspend fun createOffer(iceRestart: Boolean): String {
        val constraints = MediaConstraints().apply {
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        val offer = createSdp { obs -> pc.createOffer(obs, constraints) }
        setSdp { obs -> pc.setLocalDescription(obs, offer) }
        return offer.description
    }

    override suspend fun acceptOffer(sdp: String): String {
        setSdp { obs -> pc.setRemoteDescription(obs, SessionDescription(SessionDescription.Type.OFFER, sdp)) }
        val answer = createSdp { obs -> pc.createAnswer(obs, MediaConstraints()) }
        setSdp { obs -> pc.setLocalDescription(obs, answer) }
        return answer.description
    }

    override suspend fun acceptAnswer(sdp: String) {
        setSdp { obs -> pc.setRemoteDescription(obs, SessionDescription(SessionDescription.Type.ANSWER, sdp)) }
    }

    override fun addRemoteCandidate(candidate: IceCandidate) {
        if (closed.get()) return
        pc.addIceCandidate(org.webrtc.IceCandidate(candidate.sdpMid ?: "0", candidate.sdpMLineIndex ?: 0, candidate.candidate))
    }

    override fun sendControl(text: String): Boolean {
        val dc = dataChannel ?: return false
        if (closed.get() || dc.state() != DataChannel.State.OPEN) return false
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
    }

    override suspend fun stats(): RtcStats? {
        if (closed.get()) return null
        val entries = suspendCancellableCoroutine { cont ->
            pc.getStats { report ->
                cont.resume(report.statsMap.values.map { StatEntry(it.id, it.type, it.members) })
            }
        }
        return statsParser.parse(entries, System.currentTimeMillis(), isSender)
    }

    /** Subclasses release their media first, then call super. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataChannel?.let {
            it.unregisterObserver()
            it.close()
            it.dispose()
        }
        dataChannel = null
        pc.close()
        pc.dispose()
        channel.close()
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
            emit(RtcEvent.LocalCandidate(IceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            val mapped = when (newState) {
                PeerConnection.PeerConnectionState.NEW -> RtcConnectionState.NEW
                PeerConnection.PeerConnectionState.CONNECTING -> RtcConnectionState.CONNECTING
                PeerConnection.PeerConnectionState.CONNECTED -> RtcConnectionState.CONNECTED
                PeerConnection.PeerConnectionState.DISCONNECTED -> RtcConnectionState.DISCONNECTED
                PeerConnection.PeerConnectionState.FAILED -> RtcConnectionState.FAILED
                PeerConnection.PeerConnectionState.CLOSED -> RtcConnectionState.CLOSED
            }
            emit(RtcEvent.ConnectionChanged(mapped))
        }

        override fun onDataChannel(dc: DataChannel) = attachDataChannel(dc)
        override fun onTrack(transceiver: RtpTransceiver) = onRemoteTrack(transceiver)

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }
}

private suspend fun createSdp(block: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
    block(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
        override fun onCreateFailure(error: String?) = cont.resumeWithException(RtcException(error ?: "createSdp failed"))
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String?) = Unit
    })
}

private suspend fun setSdp(block: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
    block(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetSuccess() = cont.resume(Unit)
        override fun onSetFailure(error: String?) = cont.resumeWithException(RtcException(error ?: "setSdp failed"))
    })
}
