package com.remodroid.shared.session

import com.remodroid.shared.signaling.ConnectionStatus

object StateLabels {
    fun host(state: SessionState, signaling: ConnectionStatus, accepting: Boolean): String = when (state) {
        SessionState.OFFLINE -> if (signaling == ConnectionStatus.CONNECTING) "Connecting to server…" else "Not sharing"
        SessionState.AVAILABLE -> if (accepting) "Ready for incoming connections" else "Online, not accepting requests"
        SessionState.INCOMING_REQUEST -> "Incoming connection request"
        SessionState.NEGOTIATING -> "Starting secure connection…"
        SessionState.CONNECTING_MEDIA -> "Connecting video…"
        SessionState.CONNECTED_VIEW_ONLY -> "Connected · view only"
        SessionState.CONNECTED_CONTROL_ENABLED -> "Connected · remote control allowed"
        SessionState.RECONNECTING -> "Reconnecting…"
        SessionState.DISCONNECTING -> "Ending session…"
        SessionState.FAILED -> "Failed"
        else -> state.name.lowercase().replace('_', ' ')
    }

    fun controller(state: SessionState): String = when (state) {
        SessionState.OFFLINE, SessionState.DISCONNECTED -> "Not connected"
        SessionState.REQUESTING_CONNECTION -> "Contacting partner…"
        SessionState.WAITING_FOR_APPROVAL -> "Waiting for the partner to approve…"
        SessionState.NEGOTIATING -> "Approved · negotiating connection…"
        SessionState.CONNECTING_MEDIA -> "Connecting video…"
        SessionState.CONNECTED_VIEW_ONLY -> "Connected · view only"
        SessionState.CONNECTED_CONTROL_ENABLED -> "Connected · control enabled"
        SessionState.RECONNECTING -> "Connection unstable · reconnecting…"
        SessionState.DISCONNECTING -> "Disconnecting…"
        SessionState.FAILED -> "Connection failed"
        else -> state.name.lowercase().replace('_', ' ')
    }

    fun signaling(status: ConnectionStatus): String = when (status) {
        ConnectionStatus.STOPPED -> "Offline"
        ConnectionStatus.CONNECTING -> "Connecting to server…"
        ConnectionStatus.CONNECTED -> "Server connected"
        ConnectionStatus.WAITING_TO_RETRY -> "Server unreachable · retrying"
    }
}
