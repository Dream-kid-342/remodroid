package com.remodroid.shared.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SessionState {
    OFFLINE,
    AVAILABLE,
    REQUESTING_CONNECTION,
    INCOMING_REQUEST,
    WAITING_FOR_APPROVAL,
    NEGOTIATING,
    CONNECTING_MEDIA,
    CONNECTED_VIEW_ONLY,
    CONNECTED_CONTROL_ENABLED,
    RECONNECTING,
    DISCONNECTING,
    DISCONNECTED,
    FAILED,
    ;

    val isConnected get() = this == CONNECTED_VIEW_ONLY || this == CONNECTED_CONTROL_ENABLED

    /** A session exists (pending, negotiating or live) and must be torn down before another starts. */
    val isInSession
        get() = this in setOf(
            REQUESTING_CONNECTION, INCOMING_REQUEST, WAITING_FOR_APPROVAL, NEGOTIATING,
            CONNECTING_MEDIA, CONNECTED_VIEW_ONLY, CONNECTED_CONTROL_ENABLED, RECONNECTING, DISCONNECTING,
        )
}

enum class Role { HOST, CONTROLLER }

/**
 * Explicit state machine for one role. Invalid transitions are refused (and reported) rather
 * than applied, which prevents duplicate sessions and late callbacks from resurrecting a session.
 */
class SessionStateMachine(val role: Role, initial: SessionState = SessionState.OFFLINE) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Last failure or end reason, for display. */
    @Volatile
    var lastReason: String? = null
        private set

    val current: SessionState get() = _state.value

    fun canTransition(to: SessionState): Boolean = to in allowed(role, current)

    /** Applies the transition if valid. Returns false (and leaves the state unchanged) otherwise. */
    @Synchronized
    fun transition(to: SessionState, reason: String? = null): Boolean {
        val from = _state.value
        if (from == to) return true
        if (to !in allowed(role, from)) return false
        if (reason != null || to == SessionState.FAILED || to == SessionState.DISCONNECTED) lastReason = reason
        _state.value = to
        return true
    }

    /** Applies the transition only when currently in [from]. Used to avoid races with asynchronous callbacks. */
    @Synchronized
    fun transitionIf(from: Set<SessionState>, to: SessionState, reason: String? = null): Boolean =
        if (_state.value in from) transition(to, reason) else false

    companion object {
        private val CONNECTED = setOf(SessionState.CONNECTED_VIEW_ONLY, SessionState.CONNECTED_CONTROL_ENABLED)

        private val hostTransitions: Map<SessionState, Set<SessionState>> = mapOf(
            SessionState.OFFLINE to setOf(SessionState.AVAILABLE, SessionState.FAILED),
            SessionState.AVAILABLE to setOf(SessionState.INCOMING_REQUEST, SessionState.OFFLINE, SessionState.RECONNECTING),
            SessionState.INCOMING_REQUEST to setOf(
                SessionState.NEGOTIATING, SessionState.AVAILABLE, SessionState.OFFLINE, SessionState.RECONNECTING,
            ),
            SessionState.NEGOTIATING to setOf(
                SessionState.CONNECTING_MEDIA, SessionState.DISCONNECTING, SessionState.RECONNECTING, SessionState.FAILED,
            ),
            SessionState.CONNECTING_MEDIA to CONNECTED + setOf(
                SessionState.DISCONNECTING, SessionState.RECONNECTING, SessionState.FAILED,
            ),
            SessionState.CONNECTED_VIEW_ONLY to setOf(
                SessionState.CONNECTED_CONTROL_ENABLED, SessionState.RECONNECTING, SessionState.DISCONNECTING, SessionState.FAILED,
            ),
            SessionState.CONNECTED_CONTROL_ENABLED to setOf(
                SessionState.CONNECTED_VIEW_ONLY, SessionState.RECONNECTING, SessionState.DISCONNECTING, SessionState.FAILED,
            ),
            SessionState.RECONNECTING to CONNECTED + setOf(
                SessionState.AVAILABLE, SessionState.NEGOTIATING, SessionState.CONNECTING_MEDIA,
                SessionState.INCOMING_REQUEST, SessionState.DISCONNECTING, SessionState.OFFLINE, SessionState.FAILED,
            ),
            // After a host session ends the device returns to AVAILABLE (still registered) or OFFLINE.
            SessionState.DISCONNECTING to setOf(SessionState.AVAILABLE, SessionState.OFFLINE, SessionState.FAILED),
            SessionState.FAILED to setOf(SessionState.OFFLINE, SessionState.AVAILABLE),
        )

        private val controllerTransitions: Map<SessionState, Set<SessionState>> = mapOf(
            SessionState.OFFLINE to setOf(SessionState.REQUESTING_CONNECTION),
            SessionState.DISCONNECTED to setOf(SessionState.REQUESTING_CONNECTION, SessionState.OFFLINE),
            SessionState.FAILED to setOf(SessionState.REQUESTING_CONNECTION, SessionState.OFFLINE),
            SessionState.REQUESTING_CONNECTION to setOf(
                SessionState.WAITING_FOR_APPROVAL, SessionState.FAILED, SessionState.DISCONNECTED,
            ),
            SessionState.WAITING_FOR_APPROVAL to setOf(
                SessionState.NEGOTIATING, SessionState.FAILED, SessionState.DISCONNECTED,
            ),
            SessionState.NEGOTIATING to setOf(
                SessionState.CONNECTING_MEDIA, SessionState.DISCONNECTING, SessionState.RECONNECTING, SessionState.FAILED,
            ),
            SessionState.CONNECTING_MEDIA to CONNECTED + setOf(
                SessionState.DISCONNECTING, SessionState.RECONNECTING, SessionState.FAILED,
            ),
            SessionState.CONNECTED_VIEW_ONLY to setOf(
                SessionState.CONNECTED_CONTROL_ENABLED, SessionState.RECONNECTING, SessionState.DISCONNECTING, SessionState.FAILED,
            ),
            SessionState.CONNECTED_CONTROL_ENABLED to setOf(
                SessionState.CONNECTED_VIEW_ONLY, SessionState.RECONNECTING, SessionState.DISCONNECTING, SessionState.FAILED,
            ),
            SessionState.RECONNECTING to CONNECTED + setOf(
                SessionState.NEGOTIATING, SessionState.CONNECTING_MEDIA, SessionState.DISCONNECTING, SessionState.FAILED,
            ),
            SessionState.DISCONNECTING to setOf(SessionState.DISCONNECTED, SessionState.FAILED),
        )

        fun allowed(role: Role, from: SessionState): Set<SessionState> =
            (if (role == Role.HOST) hostTransitions else controllerTransitions)[from].orEmpty()
    }
}
