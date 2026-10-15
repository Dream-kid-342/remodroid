package com.remodroid.shared.session

import com.remodroid.shared.control.ControlCodec
import com.remodroid.shared.control.HostState
import com.remodroid.shared.control.InputCommand
import com.remodroid.shared.control.InputRejected
import com.remodroid.shared.control.RejectReason
import com.remodroid.shared.models.Capability
import com.remodroid.shared.models.PermissionLevel
import com.remodroid.shared.models.PermissionPolicy
import com.remodroid.shared.protocol.AnswerReceived
import com.remodroid.shared.protocol.ApproveConnection
import com.remodroid.shared.protocol.ConnectionRequestReceived
import com.remodroid.shared.protocol.EndSession
import com.remodroid.shared.protocol.HostRegistered
import com.remodroid.shared.protocol.HostUnregistered
import com.remodroid.shared.protocol.IceCandidate
import com.remodroid.shared.protocol.IceCandidateReceived
import com.remodroid.shared.protocol.IceServer
import com.remodroid.shared.protocol.MediaState
import com.remodroid.shared.protocol.PasswordExpired
import com.remodroid.shared.protocol.PasswordRefreshed
import com.remodroid.shared.protocol.PeerConnectionLost
import com.remodroid.shared.protocol.PeerReconnected
import com.remodroid.shared.protocol.PermissionUpdate
import com.remodroid.shared.protocol.RefreshPassword
import com.remodroid.shared.protocol.RegisterHost
import com.remodroid.shared.protocol.RegistrationExpired
import com.remodroid.shared.protocol.RejectConnection
import com.remodroid.shared.protocol.RequestCancelled
import com.remodroid.shared.protocol.Resume
import com.remodroid.shared.protocol.Resumed
import com.remodroid.shared.protocol.SendIceCandidate
import com.remodroid.shared.protocol.SendOffer
import com.remodroid.shared.protocol.ServerError
import com.remodroid.shared.protocol.ServerMessage
import com.remodroid.shared.protocol.SessionApproved
import com.remodroid.shared.protocol.SessionEnded
import com.remodroid.shared.protocol.SetAvailability
import com.remodroid.shared.protocol.UnregisterHost
import com.remodroid.shared.rtc.ConnectionQuality
import com.remodroid.shared.rtc.HostRtcPeer
import com.remodroid.shared.rtc.InjectionResult
import com.remodroid.shared.rtc.InputInjector
import com.remodroid.shared.rtc.RtcConfig
import com.remodroid.shared.rtc.RtcConnectionState
import com.remodroid.shared.rtc.RtcEvent
import com.remodroid.shared.rtc.RtcFactory
import com.remodroid.shared.rtc.VideoQuality
import com.remodroid.shared.signaling.ConnectionStatus
import com.remodroid.shared.signaling.SignalingClient
import com.remodroid.shared.util.TokenBucket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class IncomingRequest(
    val sessionId: String,
    val controllerName: String?,
    val requestedPermission: PermissionLevel,
    val expiresAt: Long,
)

data class HostUiState(
    val state: SessionState = SessionState.OFFLINE,
    val sharingEnabled: Boolean = false,
    val signaling: ConnectionStatus = ConnectionStatus.STOPPED,
    val deviceId: String? = null,
    val password: String? = null,
    val passwordExpiresAt: Long? = null,
    /** The server issues temporary IDs only; persistent IDs are not implemented. */
    val idIsTemporary: Boolean = true,
    val acceptingRequests: Boolean = true,
    val incoming: IncomingRequest? = null,
    val sessionId: String? = null,
    val peerName: String? = null,
    val requestedPermission: PermissionLevel? = null,
    val grantedPermission: PermissionLevel? = null,
    val paused: Boolean = false,
    val effectiveCapabilities: Set<Capability> = emptySet(),
    val controlNote: String? = null,
    val quality: ConnectionQuality? = null,
    val relayed: Boolean? = null,
    val peerSignalingLost: Boolean = false,
    val notice: String? = null,
)

/**
 * "Allow Remote Access" role. Owns registration, approval, the outgoing screen stream and
 * enforcement of the granted permission on every received input command.
 *
 * Must run on a single-threaded [scope] (Main on Android, Swing EDT on desktop, or a
 * limitedParallelism(1) dispatcher); all state changes happen there.
 */
class HostAgent(
    private val signaling: SignalingClient,
    private val rtcFactory: RtcFactory,
    private val injector: InputInjector,
    private val scope: CoroutineScope,
    private val displayName: () -> String?,
    private val rtcSettings: () -> Pair<Boolean, VideoQuality> = { false to VideoQuality.AUTO },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val machine = SessionStateMachine(Role.HOST)
    private val details = MutableStateFlow(HostUiState())

    private val _ui = MutableStateFlow(HostUiState())
    val ui: StateFlow<HostUiState> = _ui.asStateFlow()

    private var resumeToken: String? = null
    private var peer: HostRtcPeer? = null
    private val sessionJobs = mutableListOf<Job>()
    private var mediaTimeout: Job? = null
    private var recoveryJob: Job? = null
    private var statsJob: Job? = null
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var iceServers: List<IceServer> = emptyList()
    private var inputBucket = TokenBucket(capacity = 120.0, refillPerSecond = 60.0, nowMs = nowMs)

    init {
        scope.launch {
            combine(machine.state, details, signaling.status) { s, d, sig -> d.copy(state = s, signaling = sig) }
                .collect { _ui.value = it }
        }
        scope.launch { signaling.messages.collect { onServerMessage(it) } }
        scope.launch { signaling.connected.collect { onSignalingConnected() } }
        scope.launch { signaling.status.collect { onSignalingStatus(it) } }
        scope.launch {
            combine(injector.supported, injector.note) { _, _ -> }.collect { recomputeCapabilities() }
        }
    }

    // ------------------------------------------------------------------ public actions

    fun startSharing() = scope.launch {
        if (details.value.sharingEnabled) return@launch
        details.update { it.copy(sharingEnabled = true, notice = null) }
        signaling.start()
        if (signaling.status.value == ConnectionStatus.CONNECTED) register()
    }

    /** Stops sharing: ends any session, invalidates the ID and password on the server. */
    fun stopSharing() = scope.launch {
        val sessionId = details.value.sessionId ?: details.value.incoming?.sessionId
        if (sessionId != null) {
            if (details.value.incoming != null) signaling.send(RejectConnection(sessionId))
            else signaling.send(EndSession(sessionId, "user_ended"))
        }
        signaling.send(UnregisterHost)
        teardownSession(null)
        resumeToken = null
        details.update {
            HostUiState(sharingEnabled = false, acceptingRequests = it.acceptingRequests)
        }
        forceState(SessionState.OFFLINE)
    }

    fun refreshPassword() = scope.launch {
        if (details.value.deviceId == null) return@launch
        if (!signaling.send(RefreshPassword)) notice("Not connected to the server; try again shortly.")
    }

    fun setAcceptingRequests(accepting: Boolean) = scope.launch {
        details.update { it.copy(acceptingRequests = accepting) }
        if (details.value.deviceId != null) signaling.send(SetAvailability(accepting))
    }

    /**
     * Approves the pending request. On Android the caller must have obtained MediaProjection
     * consent first; if the user declined it, call [reject] instead.
     */
    fun approve(permission: PermissionLevel) = scope.launch {
        val incoming = details.value.incoming ?: return@launch
        if (machine.current != SessionState.INCOMING_REQUEST) return@launch
        val granted = permission.atMost(incoming.requestedPermission)
        if (!signaling.send(ApproveConnection(incoming.sessionId, granted))) {
            notice("Not connected to the server; the request could not be approved.")
            return@launch
        }
        details.update {
            it.copy(
                incoming = null,
                sessionId = incoming.sessionId,
                peerName = incoming.controllerName,
                requestedPermission = incoming.requestedPermission,
                grantedPermission = granted,
                paused = false,
                notice = null,
            )
        }
        recomputeCapabilities()
        machine.transition(SessionState.NEGOTIATING)
    }

    fun reject() = scope.launch {
        val incoming = details.value.incoming ?: return@launch
        signaling.send(RejectConnection(incoming.sessionId))
        details.update { it.copy(incoming = null) }
        machine.transitionIf(setOf(SessionState.INCOMING_REQUEST), SessionState.AVAILABLE)
    }

    /** Changes the granted level. Takes effect locally immediately, before the server confirms. */
    fun setPermission(level: PermissionLevel) = scope.launch {
        val d = details.value
        val sessionId = d.sessionId ?: return@launch
        val requested = d.requestedPermission ?: return@launch
        val newLevel = level.atMost(requested)
        details.update { it.copy(grantedPermission = newLevel) }
        recomputeCapabilities()
        signaling.send(PermissionUpdate(sessionId, newLevel))
    }

    fun revokeControl() = setPermission(PermissionLevel.VIEW_ONLY)

    /** Privacy pause: the controller sees a paused notice and input is refused until resumed. */
    fun setPaused(paused: Boolean) = scope.launch {
        if (details.value.sessionId == null) return@launch
        peer?.setVideoEnabled(!paused)
        details.update { it.copy(paused = paused) }
        recomputeCapabilities()
    }

    fun endSession() = scope.launch {
        val sessionId = details.value.sessionId ?: return@launch
        signaling.send(EndSession(sessionId, "user_ended"))
        teardownSession("You ended the session.")
    }

    fun clearNotice() = scope.launch { details.update { it.copy(notice = null) } }

    // ------------------------------------------------------------------ signaling

    private fun register() {
        val token = resumeToken
        if (token != null) signaling.send(Resume(token, requestId = RESUME_TAG))
        else signaling.send(RegisterHost(displayName()))
    }

    private fun onSignalingConnected() {
        if (details.value.sharingEnabled) register()
    }

    private fun onSignalingStatus(status: ConnectionStatus) {
        if (status == ConnectionStatus.CONNECTED || !details.value.sharingEnabled) return
        // Without signaling, nobody can reach this device. A live session keeps its media path.
        machine.transitionIf(setOf(SessionState.AVAILABLE, SessionState.INCOMING_REQUEST), SessionState.RECONNECTING)
    }

    private suspend fun onServerMessage(message: ServerMessage) {
        when (message) {
            is HostRegistered -> {
                resumeToken = message.resumeToken
                details.update {
                    it.copy(
                        deviceId = message.deviceId,
                        password = message.password,
                        passwordExpiresAt = message.passwordExpiresAt,
                        idIsTemporary = message.idKind == "temporary",
                    )
                }
                if (!details.value.acceptingRequests) signaling.send(SetAvailability(false))
                becomeAvailable()
                log("host: registered")
            }
            is Resumed -> if (message.kind == "host") onResumed(message)
            is PasswordRefreshed -> details.update {
                it.copy(password = message.password, passwordExpiresAt = message.passwordExpiresAt)
            }
            // Expired passwords are useless to the user; rotate automatically.
            PasswordExpired -> signaling.send(RefreshPassword)
            RegistrationExpired -> {
                resumeToken = null
                teardownSession("The temporary ID expired.")
                details.update { it.copy(deviceId = null, password = null) }
                forceState(SessionState.OFFLINE)
                if (details.value.sharingEnabled) register()
            }
            HostUnregistered -> Unit
            is ConnectionRequestReceived -> onIncomingRequest(message)
            is RequestCancelled -> if (details.value.incoming?.sessionId == message.sessionId) {
                details.update {
                    it.copy(
                        incoming = null,
                        notice = if (message.reason == "approval_timeout") "The request timed out." else "The request was cancelled.",
                    )
                }
                machine.transitionIf(setOf(SessionState.INCOMING_REQUEST), SessionState.AVAILABLE)
            }
            is SessionApproved -> if (message.role == "host" && message.sessionId == details.value.sessionId) {
                startMedia(message.iceServers)
            }
            is AnswerReceived -> if (message.sessionId == details.value.sessionId) onAnswer(message.sdp)
            is IceCandidateReceived -> if (message.sessionId == details.value.sessionId) onRemoteCandidate(message.candidate)
            is SessionEnded -> if (message.sessionId == details.value.sessionId) {
                teardownSession(Reasons.endReason(message.reason, message.endedBy))
            }
            is PeerConnectionLost -> if (message.sessionId == details.value.sessionId) {
                details.update { it.copy(peerSignalingLost = true) }
            }
            is PeerReconnected -> if (message.sessionId == details.value.sessionId) {
                details.update { it.copy(peerSignalingLost = false) }
            }
            is ServerError -> onError(message)
            else -> Unit
        }
    }

    private fun onResumed(message: Resumed) {
        resumeToken = message.resumeToken
        details.update { it.copy(deviceId = message.deviceId ?: it.deviceId, passwordExpiresAt = message.passwordExpiresAt) }
        val local = details.value.sessionId
        val remote = message.session
        when {
            local != null && (remote == null || remote.sessionId != local) -> teardownSession("The session was lost while reconnecting.")
            local != null && remote != null && remote.state == "approved" -> {
                if (remote.iceServers.isNotEmpty()) iceServers = remote.iceServers
                // The media path may have survived the signaling drop; if not, restart ICE now.
                if (!machine.current.isConnected) scope.launch { restartIce() }
            }
            local == null && details.value.incoming == null -> becomeAvailable()
        }
        log("host: resumed")
    }

    private fun onError(error: ServerError) {
        when (error.ref) {
            "resume" -> if (error.requestId == RESUME_TAG) {
                // The server no longer knows this registration (restart or expiry). Register afresh.
                resumeToken = null
                teardownSession(if (details.value.sessionId != null) "The session was lost while reconnecting." else null)
                details.update { it.copy(deviceId = null, password = null) }
                forceState(SessionState.OFFLINE)
                if (details.value.sharingEnabled) register()
            }
            "register_host", "refresh_password", "set_availability" -> notice(Reasons.error(error.code, error.retryAfterMs))
            "approve_connection", "permission_update" -> notice(Reasons.error(error.code))
            "webrtc_offer" -> if (error.code != "peer_unavailable") notice(Reasons.error(error.code))
            else -> Unit
        }
    }

    private fun onIncomingRequest(message: ConnectionRequestReceived) {
        val current = machine.current
        val alreadyShown = details.value.incoming?.sessionId == message.sessionId
        if (alreadyShown) return
        if (current != SessionState.AVAILABLE && current != SessionState.RECONNECTING || details.value.sessionId != null) {
            signaling.send(RejectConnection(message.sessionId, "busy"))
            return
        }
        details.update {
            it.copy(
                incoming = IncomingRequest(message.sessionId, message.controllerName, message.requestedPermission, message.expiresAt),
                notice = null,
            )
        }
        if (current == SessionState.RECONNECTING) machine.transition(SessionState.AVAILABLE)
        machine.transition(SessionState.INCOMING_REQUEST)
    }

    // ------------------------------------------------------------------ media

    private suspend fun startMedia(servers: List<IceServer>) {
        val sessionId = details.value.sessionId ?: return
        iceServers = servers
        val (relayOnly, quality) = rtcSettings()
        val created = try {
            rtcFactory.createHostPeer(RtcConfig(servers, relayOnly, quality))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("host: could not start capture: ${e.message}")
            signaling.send(EndSession(sessionId, "error"))
            failSession("Screen capture could not start: ${e.message ?: "unknown error"}")
            return
        }
        if (details.value.sessionId != sessionId) {
            created.close()
            return
        }
        peer = created
        remoteDescriptionSet = false
        inputBucket = TokenBucket(capacity = 120.0, refillPerSecond = 60.0, nowMs = nowMs)
        sessionJobs += scope.launch { created.events.collect { onRtcEvent(it) } }
        sessionJobs += scope.launch { created.screen.collect { sendHostState() } }

        val sdp = try {
            created.createOffer(iceRestart = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            signaling.send(EndSession(sessionId, "error"))
            failSession("Could not create the video offer.")
            return
        }
        signaling.send(SendOffer(sessionId, sdp))
        machine.transition(SessionState.CONNECTING_MEDIA)
        mediaTimeout = scope.launch {
            delay(MEDIA_TIMEOUT_MS)
            if (!machine.current.isConnected) {
                signaling.send(EndSession(sessionId, "error"))
                failSession(MEDIA_FAILURE_TEXT)
            }
        }
    }

    private suspend fun onAnswer(sdp: String) {
        val p = peer ?: return
        try {
            p.acceptAnswer(sdp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("host: invalid answer: ${e.message}")
            return
        }
        remoteDescriptionSet = true
        pendingRemoteCandidates.forEach { p.addRemoteCandidate(it) }
        pendingRemoteCandidates.clear()
    }

    private fun onRemoteCandidate(candidate: IceCandidate) {
        val p = peer
        if (p == null || !remoteDescriptionSet) pendingRemoteCandidates += candidate else p.addRemoteCandidate(candidate)
    }

    private suspend fun onRtcEvent(event: RtcEvent) {
        val sessionId = details.value.sessionId ?: return
        when (event) {
            is RtcEvent.LocalCandidate -> signaling.send(SendIceCandidate(sessionId, event.candidate))
            is RtcEvent.ConnectionChanged -> onRtcState(sessionId, event.state)
            RtcEvent.ControlChannelOpen -> sendHostState()
            RtcEvent.ControlChannelClosed -> Unit
            is RtcEvent.ControlMessageReceived -> onControlMessage(event.text)
            RtcEvent.FirstFrameRendered -> Unit
        }
    }

    private suspend fun onRtcState(sessionId: String, state: RtcConnectionState) {
        when (state) {
            RtcConnectionState.CONNECTED -> {
                mediaTimeout?.cancel()
                recoveryJob?.cancel()
                recoveryJob = null
                applyConnectedState()
                val stats = peer?.stats()
                signaling.send(MediaState(sessionId, connected = true, relayed = stats?.relayed))
                if (statsJob?.isActive != true) startStatsLoop()
            }
            RtcConnectionState.DISCONNECTED, RtcConnectionState.FAILED -> {
                if (!machine.current.isConnected && machine.current != SessionState.RECONNECTING) return
                machine.transition(SessionState.RECONNECTING)
                signaling.send(MediaState(sessionId, connected = false))
                if (recoveryJob?.isActive != true) recoveryJob = scope.launch { recover(sessionId, state) }
            }
            else -> Unit
        }
    }

    /** Waits briefly for ICE to recover on its own, then restarts ICE; gives up after a deadline. */
    private suspend fun recover(sessionId: String, initial: RtcConnectionState) {
        if (initial == RtcConnectionState.DISCONNECTED) delay(ICE_SELF_HEAL_MS)
        val deadline = nowMs() + RECOVERY_DEADLINE_MS
        while (scope.isActive && details.value.sessionId == sessionId && !machine.current.isConnected) {
            if (signaling.status.value == ConnectionStatus.CONNECTED) restartIce()
            delay(ICE_RESTART_INTERVAL_MS)
            if (nowMs() >= deadline) {
                signaling.send(EndSession(sessionId, "error"))
                failSession("The connection was lost and could not be recovered.")
                return
            }
        }
    }

    private suspend fun restartIce() {
        val p = peer ?: return
        val sessionId = details.value.sessionId ?: return
        try {
            remoteDescriptionSet = false
            val sdp = p.createOffer(iceRestart = true)
            signaling.send(SendOffer(sessionId, sdp))
            log("host: ICE restart offer sent")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("host: ICE restart failed: ${e.message}")
        }
    }

    private fun startStatsLoop() {
        statsJob = scope.launch {
            while (isActive) {
                val stats = peer?.stats()
                details.update { it.copy(quality = ConnectionQuality.from(stats), relayed = stats?.relayed ?: it.relayed) }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    // ------------------------------------------------------------------ permissions & input

    private fun recomputeCapabilities() {
        val d = details.value
        val granted = d.grantedPermission
        val caps = if (granted == null) emptySet() else PermissionPolicy.effective(granted, injector.supported.value, d.paused)
        details.update { it.copy(effectiveCapabilities = caps, controlNote = injector.note.value) }
        if (machine.current.isConnected) applyConnectedState()
        sendHostState()
    }

    private fun applyConnectedState() {
        val control = details.value.effectiveCapabilities.any { it != Capability.VIEW_SCREEN }
        val target = if (control) SessionState.CONNECTED_CONTROL_ENABLED else SessionState.CONNECTED_VIEW_ONLY
        machine.transition(target)
    }

    private fun sendHostState() {
        val p = peer ?: return
        val d = details.value
        val granted = d.grantedPermission ?: return
        val state = HostState(
            platform = injector.platform,
            permission = granted,
            capabilities = d.effectiveCapabilities,
            paused = d.paused,
            screen = p.screen.value,
            controlNote = d.controlNote,
        )
        p.sendControl(ControlCodec.encode(state))
    }

    /** Every command is checked here, on the host, regardless of what the controller UI allowed. */
    private suspend fun onControlMessage(text: String) {
        val command = ControlCodec.decode(text) as? InputCommand ?: return
        val reason: RejectReason? = when {
            !inputBucket.tryTake() -> RejectReason.INVALID
            !ControlCodec.isWellFormed(command) -> RejectReason.INVALID
            details.value.paused -> RejectReason.PAUSED
            command.requiredCapability !in details.value.effectiveCapabilities -> RejectReason.NOT_PERMITTED
            else -> null
        }
        if (reason != null) return rejectInput(command.seq, reason, null)
        when (val result = injector.inject(command, peer?.screen?.value)) {
            InjectionResult.Ok -> Unit
            is InjectionResult.Unsupported -> rejectInput(command.seq, RejectReason.UNSUPPORTED, result.detail)
            is InjectionResult.Failed -> rejectInput(command.seq, RejectReason.FAILED, result.detail)
        }
    }

    private fun rejectInput(seq: Long, reason: RejectReason, detail: String?) {
        peer?.sendControl(ControlCodec.encode(InputRejected(seq, reason, detail)))
    }

    // ------------------------------------------------------------------ teardown

    private fun failSession(reason: String) {
        machine.transition(SessionState.FAILED, reason)
        teardownSession(reason)
    }

    /** Releases the peer connection, capture and all timers, then returns to AVAILABLE or OFFLINE. */
    private fun teardownSession(reason: String?) {
        mediaTimeout?.cancel()
        mediaTimeout = null
        recoveryJob?.cancel()
        recoveryJob = null
        statsJob?.cancel()
        statsJob = null
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        peer?.close()
        peer = null
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
        val hadSession = details.value.sessionId != null || details.value.incoming != null
        details.update {
            it.copy(
                incoming = null,
                sessionId = null,
                peerName = null,
                requestedPermission = null,
                grantedPermission = null,
                paused = false,
                effectiveCapabilities = emptySet(),
                quality = null,
                relayed = null,
                peerSignalingLost = false,
                notice = reason ?: it.notice,
            )
        }
        if (hadSession || machine.current.isInSession) {
            machine.transition(SessionState.DISCONNECTING)
            if (details.value.deviceId != null && details.value.sharingEnabled) becomeAvailable()
            else forceState(SessionState.OFFLINE)
        }
    }

    /** The transition table decides validity; e.g. it refuses AVAILABLE while media is negotiating. */
    private fun becomeAvailable() {
        machine.transition(SessionState.AVAILABLE)
    }

    /** Moves to [target] through DISCONNECTING when a direct transition is not allowed. */
    private fun forceState(target: SessionState) {
        if (!machine.transition(target)) {
            machine.transition(SessionState.DISCONNECTING)
            machine.transition(target)
        }
    }

    private fun notice(text: String) = details.update { it.copy(notice = text) }


    companion object {
        const val RESUME_TAG = "host"
        const val MEDIA_TIMEOUT_MS = 45_000L
        const val ICE_SELF_HEAL_MS = 4_000L
        const val ICE_RESTART_INTERVAL_MS = 8_000L
        const val RECOVERY_DEADLINE_MS = 30_000L
        const val STATS_INTERVAL_MS = 2_000L
        const val MEDIA_FAILURE_TEXT =
            "Could not establish the video connection. Both devices need internet access; on restrictive " +
                "networks (some mobile carriers, corporate Wi-Fi) a TURN relay must be configured on the server."
    }
}
