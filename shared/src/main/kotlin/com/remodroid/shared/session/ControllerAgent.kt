package com.remodroid.shared.session

import com.remodroid.shared.control.ControlCodec
import com.remodroid.shared.control.HostState
import com.remodroid.shared.control.InputCommand
import com.remodroid.shared.control.InputRejected
import com.remodroid.shared.control.RejectReason
import com.remodroid.shared.models.Capability
import com.remodroid.shared.models.PermissionLevel
import com.remodroid.shared.protocol.CancelRequest
import com.remodroid.shared.protocol.ConnectionPending
import com.remodroid.shared.protocol.EndSession
import com.remodroid.shared.protocol.IceCandidate
import com.remodroid.shared.protocol.IceCandidateReceived
import com.remodroid.shared.protocol.MediaState
import com.remodroid.shared.protocol.OfferReceived
import com.remodroid.shared.protocol.PeerConnectionLost
import com.remodroid.shared.protocol.PeerReconnected
import com.remodroid.shared.protocol.PermissionUpdated
import com.remodroid.shared.protocol.RequestConnection
import com.remodroid.shared.protocol.Resume
import com.remodroid.shared.protocol.Resumed
import com.remodroid.shared.protocol.SendAnswer
import com.remodroid.shared.protocol.SendIceCandidate
import com.remodroid.shared.protocol.ServerError
import com.remodroid.shared.protocol.ServerMessage
import com.remodroid.shared.protocol.SessionApproved
import com.remodroid.shared.protocol.SessionEnded
import com.remodroid.shared.protocol.SessionRejected
import com.remodroid.shared.rtc.ConnectionQuality
import com.remodroid.shared.rtc.RtcConfig
import com.remodroid.shared.rtc.RtcConnectionState
import com.remodroid.shared.rtc.RtcEvent
import com.remodroid.shared.rtc.RtcFactory
import com.remodroid.shared.rtc.RtcPeer
import com.remodroid.shared.rtc.RtcStats
import com.remodroid.shared.signaling.ConnectionStatus
import com.remodroid.shared.signaling.SignalingClient
import com.remodroid.shared.validation.Validation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class ControllerUiState(
    val state: SessionState = SessionState.OFFLINE,
    val signaling: ConnectionStatus = ConnectionStatus.STOPPED,
    val sessionId: String? = null,
    val partnerId: String? = null,
    val peerName: String? = null,
    val requestedPermission: PermissionLevel? = null,
    /** As confirmed by the host over the data channel (authoritative) or the server. */
    val permission: PermissionLevel? = null,
    val hostState: HostState? = null,
    val approvalExpiresAt: Long? = null,
    val quality: ConnectionQuality? = null,
    val stats: RtcStats? = null,
    val peerSignalingLost: Boolean = false,
    val lastInputProblem: String? = null,
    val message: String? = null,
) {
    val capabilities: Set<Capability> get() = hostState?.capabilities.orEmpty()
}

/**
 * "Control Remote Device" role: authenticates against a partner ID, waits for approval, receives the
 * screen and sends input. It only reports CONNECTED once the media connection is up and the first frame
 * has rendered. Must run on a single-threaded [scope].
 */
class ControllerAgent(
    private val signaling: SignalingClient,
    private val rtcFactory: RtcFactory,
    private val scope: CoroutineScope,
    private val displayName: () -> String?,
    private val relayOnly: () -> Boolean = { false },
    private val log: (String) -> Unit = {},
) {
    private val machine = SessionStateMachine(Role.CONTROLLER)
    private val details = MutableStateFlow(ControllerUiState())
    private val _ui = MutableStateFlow(ControllerUiState())
    val ui: StateFlow<ControllerUiState> = _ui.asStateFlow()

    /** The view the app renders remote video into is owned by the platform; it observes this peer. */
    private val _peer = MutableStateFlow<RtcPeer?>(null)
    val peer: StateFlow<RtcPeer?> = _peer.asStateFlow()

    private var requestId: String? = null
    private var resumeToken: String? = null
    private var mediaConnected = false
    private var firstFrame = false
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private val sessionJobs = mutableListOf<Job>()
    private var timeoutJob: Job? = null
    private var recoveryJob: Job? = null
    private var statsJob: Job? = null
    private var seq = 0L

    init {
        scope.launch {
            combine(machine.state, details, signaling.status) { s, d, sig -> d.copy(state = s, signaling = sig) }
                .collect { _ui.value = it }
        }
        scope.launch { signaling.messages.collect { onServerMessage(it) } }
        scope.launch { signaling.connected.collect { onSignalingConnected() } }
    }

    // ------------------------------------------------------------------ public actions

    fun connect(partnerId: String, password: String, permission: PermissionLevel) = scope.launch {
        if (machine.current.isInSession) return@launch
        val problems = Validation.validateConnectForm(partnerId, password)
        if (problems.isNotEmpty()) {
            details.update { it.copy(message = problems.joinToString(" ") { p -> Validation.describe(p) }) }
            return@launch
        }
        val id = Validation.normalizeId(partnerId)
        val reqId = UUID.randomUUID().toString().replace("-", "").take(24)
        requestId = reqId
        details.value = ControllerUiState(partnerId = id, requestedPermission = permission)
        machine.transition(SessionState.REQUESTING_CONNECTION)

        signaling.start()
        val connected = withTimeoutOrNull(SERVER_CONNECT_TIMEOUT_MS) {
            signaling.status.first { it == ConnectionStatus.CONNECTED }
        }
        if (requestId != reqId) return@launch // cancelled meanwhile
        if (connected == null) {
            fail(signaling.lastError.value ?: "Cannot reach the signaling server. Check the internet connection.")
            return@launch
        }
        val sent = signaling.send(
            RequestConnection(
                requestId = reqId,
                targetId = id,
                password = Validation.normalizePassword(password),
                permission = permission,
                controllerName = displayName()?.let(Validation::sanitizeDisplayName),
            ),
        )
        if (!sent) return@launch fail("Lost the connection to the signaling server.")
        timeoutJob = scope.launch {
            delay(SERVER_RESPONSE_TIMEOUT_MS)
            if (machine.current == SessionState.REQUESTING_CONNECTION && requestId == reqId) {
                fail("The signaling server did not respond.")
            }
        }
    }

    /** Cancels a pending request or ends an active session. */
    fun disconnect() = scope.launch {
        val sessionId = details.value.sessionId
        when (machine.current) {
            SessionState.REQUESTING_CONNECTION -> {
                requestId = null
                teardown(SessionState.DISCONNECTED, "Request cancelled.")
            }
            SessionState.WAITING_FOR_APPROVAL -> {
                if (sessionId != null) signaling.send(CancelRequest(sessionId))
                requestId = null
                teardown(SessionState.DISCONNECTED, "Request cancelled.")
            }
            else -> if (machine.current.isInSession) {
                if (sessionId != null) signaling.send(EndSession(sessionId, "user_ended"))
                machine.transition(SessionState.DISCONNECTING)
                teardown(SessionState.DISCONNECTED, "You ended the session.")
            }
        }
    }

    /**
     * Sends an input command to the host. The local capability check is for UX only; the host
     * enforces its own permission state on every command.
     */
    fun send(build: (seq: Long) -> InputCommand): Boolean {
        val p = _peer.value ?: return false
        val command = build(++seq)
        if (command.requiredCapability !in details.value.capabilities) return false
        return p.sendControl(ControlCodec.encode(command))
    }

    fun clearMessage() = scope.launch { details.update { it.copy(message = null) } }

    /** Called by the platform renderer when the first remote frame is displayed. */
    fun onFirstFrameRendered() = scope.launch { onRtcEvent(RtcEvent.FirstFrameRendered) }

    // ------------------------------------------------------------------ signaling

    private fun onSignalingConnected() {
        val token = resumeToken ?: return
        if (details.value.sessionId != null) signaling.send(Resume(token, requestId = RESUME_TAG))
    }

    private suspend fun onServerMessage(message: ServerMessage) {
        val d = details.value
        when (message) {
            is ConnectionPending -> if (message.requestId == requestId) {
                timeoutJob?.cancel()
                details.update { it.copy(sessionId = message.sessionId, approvalExpiresAt = message.approvalExpiresAt) }
                machine.transition(SessionState.WAITING_FOR_APPROVAL)
            } else if (requestId == null) {
                // A request we cancelled before the server answered; withdraw it.
                signaling.send(CancelRequest(message.sessionId))
            }
            is SessionRejected -> if (message.sessionId == d.sessionId || message.requestId == requestId) {
                fail(Reasons.rejection(message.reason))
            }
            is SessionApproved -> if (message.role == "controller" && message.sessionId == d.sessionId) {
                resumeToken = message.resumeToken
                details.update {
                    it.copy(permission = message.permission, peerName = message.peerName, approvalExpiresAt = null)
                }
                machine.transition(SessionState.NEGOTIATING)
                startPeer(message.iceServers)
            }
            is OfferReceived -> if (message.sessionId == d.sessionId) onOffer(message.sessionId, message.sdp)
            is IceCandidateReceived -> if (message.sessionId == d.sessionId) {
                val p = _peer.value
                if (p == null || !remoteDescriptionSet) pendingRemoteCandidates += message.candidate
                else p.addRemoteCandidate(message.candidate)
            }
            is PermissionUpdated -> if (message.sessionId == d.sessionId) {
                details.update { it.copy(permission = message.permission) }
            }
            is PeerConnectionLost -> if (message.sessionId == d.sessionId) details.update { it.copy(peerSignalingLost = true) }
            is PeerReconnected -> if (message.sessionId == d.sessionId) details.update { it.copy(peerSignalingLost = false) }
            is SessionEnded -> if (message.sessionId == d.sessionId) {
                machine.transition(SessionState.DISCONNECTING)
                teardown(SessionState.DISCONNECTED, Reasons.endReason(message.reason, message.endedBy))
            }
            is Resumed -> if (message.kind == "controller" && message.requestId == RESUME_TAG) {
                resumeToken = message.resumeToken
                log("controller: resumed")
            }
            is ServerError -> onError(message)
            else -> Unit
        }
    }

    private fun onError(error: ServerError) {
        val d = details.value
        when {
            error.requestId != null && error.requestId == requestId && error.ref == "request_connection" ->
                fail(Reasons.error(error.code, error.retryAfterMs))
            error.ref == "resume" && error.requestId == RESUME_TAG -> {
                resumeToken = null
                if (d.sessionId != null) fail("The session was lost while reconnecting.")
            }
            error.ref == "webrtc_answer" && error.code != "peer_unavailable" -> log("controller: answer refused (${error.code})")
        }
    }

    // ------------------------------------------------------------------ media

    private suspend fun startPeer(iceServers: List<com.remodroid.shared.protocol.IceServer>) {
        val created = try {
            rtcFactory.createViewerPeer(RtcConfig(iceServers, relayOnly()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            details.value.sessionId?.let { signaling.send(EndSession(it, "error")) }
            fail("Could not start the video receiver: ${e.message ?: "unknown error"}")
            return
        }
        _peer.value = created
        mediaConnected = false
        firstFrame = false
        remoteDescriptionSet = false
        sessionJobs += scope.launch { created.events.collect { onRtcEvent(it) } }
        timeoutJob = scope.launch {
            delay(MEDIA_TIMEOUT_MS)
            if (!machine.current.isConnected) {
                details.value.sessionId?.let { signaling.send(EndSession(it, "error")) }
                fail(HostAgent.MEDIA_FAILURE_TEXT)
            }
        }
    }

    private suspend fun onOffer(sessionId: String, sdp: String) {
        val p = _peer.value ?: return
        val answer = try {
            remoteDescriptionSet = false
            p.acceptOffer(sdp).also { remoteDescriptionSet = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("controller: could not accept offer: ${e.message}")
            return
        }
        pendingRemoteCandidates.forEach { p.addRemoteCandidate(it) }
        pendingRemoteCandidates.clear()
        signaling.send(SendAnswer(sessionId, answer))
        machine.transitionIf(setOf(SessionState.NEGOTIATING), SessionState.CONNECTING_MEDIA)
    }

    private suspend fun onRtcEvent(event: RtcEvent) {
        val sessionId = details.value.sessionId ?: return
        when (event) {
            is RtcEvent.LocalCandidate -> signaling.send(SendIceCandidate(sessionId, event.candidate))
            is RtcEvent.ConnectionChanged -> when (event.state) {
                RtcConnectionState.CONNECTED -> {
                    mediaConnected = true
                    recoveryJob?.cancel()
                    maybeConnected()
                    val stats = _peer.value?.stats()
                    signaling.send(MediaState(sessionId, connected = true, relayed = stats?.relayed))
                }
                RtcConnectionState.DISCONNECTED, RtcConnectionState.FAILED -> {
                    mediaConnected = false
                    if (machine.current.isConnected) {
                        machine.transition(SessionState.RECONNECTING)
                        signaling.send(MediaState(sessionId, connected = false))
                        // The host drives ICE restarts; give up if nothing recovers in time.
                        recoveryJob?.cancel()
                        recoveryJob = scope.launch {
                            delay(HostAgent.RECOVERY_DEADLINE_MS + 5_000)
                            if (!machine.current.isConnected) {
                                signaling.send(EndSession(sessionId, "error"))
                                fail("The connection was lost and could not be recovered.")
                            }
                        }
                    }
                }
                else -> Unit
            }
            RtcEvent.FirstFrameRendered -> {
                firstFrame = true
                maybeConnected()
            }
            is RtcEvent.ControlMessageReceived -> onControlMessage(event.text)
            RtcEvent.ControlChannelOpen, RtcEvent.ControlChannelClosed -> Unit
        }
    }

    private fun maybeConnected() {
        if (!mediaConnected || !firstFrame) return
        timeoutJob?.cancel()
        val control = details.value.capabilities.any { it != Capability.VIEW_SCREEN }
        machine.transition(if (control) SessionState.CONNECTED_CONTROL_ENABLED else SessionState.CONNECTED_VIEW_ONLY)
        if (statsJob?.isActive != true) startStatsLoop()
    }

    private fun startStatsLoop() {
        statsJob = scope.launch {
            while (isActive) {
                val stats = _peer.value?.stats()
                details.update { it.copy(stats = stats, quality = ConnectionQuality.from(stats)) }
                delay(HostAgent.STATS_INTERVAL_MS)
            }
        }
    }

    private fun onControlMessage(text: String) {
        when (val message = ControlCodec.decode(text)) {
            is HostState -> {
                details.update { it.copy(hostState = message, permission = message.permission) }
                if (machine.current.isConnected) maybeConnected()
            }
            is InputRejected -> details.update {
                it.copy(
                    lastInputProblem = when (message.reason) {
                        RejectReason.NOT_PERMITTED -> "The host has not allowed that action."
                        RejectReason.PAUSED -> "The host paused sharing."
                        RejectReason.UNSUPPORTED -> message.detail ?: "The host device does not support that action."
                        RejectReason.INVALID -> "The action was not accepted."
                        RejectReason.FAILED -> message.detail ?: "The host could not perform that action."
                    },
                )
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ teardown

    private fun fail(reason: String) {
        machine.transition(SessionState.FAILED, reason)
        teardown(SessionState.FAILED, reason)
    }

    private fun teardown(final: SessionState, message: String?) {
        timeoutJob?.cancel()
        timeoutJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        statsJob?.cancel()
        statsJob = null
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        _peer.value?.close()
        _peer.value = null
        requestId = null
        resumeToken = null
        mediaConnected = false
        firstFrame = false
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
        details.update {
            ControllerUiState(partnerId = it.partnerId, requestedPermission = it.requestedPermission, message = message)
        }
        machine.transition(final, message)
    }

    companion object {
        const val RESUME_TAG = "controller"
        const val SERVER_CONNECT_TIMEOUT_MS = 12_000L
        const val SERVER_RESPONSE_TIMEOUT_MS = 15_000L
        const val MEDIA_TIMEOUT_MS = 45_000L
    }
}
