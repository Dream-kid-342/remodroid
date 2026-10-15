package com.remodroid.shared.session

/** User-facing explanations for protocol reason and error codes. Never includes credentials. */
object Reasons {
    fun endReason(reason: String, endedBy: String? = null): String = when (reason) {
        "user_ended" -> when (endedBy) {
            "host" -> "The host ended the session."
            "controller" -> "The controller ended the session."
            else -> "The session was ended."
        }
        "permission_revoked" -> "Access was revoked."
        "peer_lost" -> "The other device lost its connection and did not come back."
        "host_stopped_sharing" -> "The host stopped sharing."
        "registration_expired" -> "The host's temporary ID expired."
        "session_expired" -> "The session reached its maximum duration."
        "server_shutdown" -> "The signaling server restarted. Connect again."
        "media_failed" -> "The video connection could not be established or was lost."
        "error" -> "The session ended because of an error."
        else -> "The session ended."
    }

    fun rejection(reason: String): String = when (reason) {
        "rejected_by_host" -> "The partner declined the request."
        "busy" -> "The partner is busy or not accepting requests."
        "approval_timeout" -> "The partner did not answer in time."
        "peer_lost" -> "The partner went offline."
        else -> "The request was not accepted."
    }

    fun error(code: String, retryAfterMs: Long? = null): String = when (code) {
        "invalid_credentials" -> "The partner ID or password is incorrect, or the password has expired."
        "rate_limited" -> buildString {
            append("Too many attempts.")
            if (retryAfterMs != null && retryAfterMs > 0) append(" Try again in ${(retryAfterMs + 999) / 1000} s.")
        }
        "host_unavailable" -> "The partner device is not accepting connections right now."
        "host_busy" -> "The partner device is already in a session."
        "cannot_connect_to_self" -> "That is this device's own ID."
        "already_in_session" -> "A session is already in progress."
        "peer_unavailable" -> "The other device is reconnecting."
        "resume_failed" -> "The previous session could not be resumed."
        "unsupported_version" -> "This app version is not compatible with the server. Please update."
        else -> "Something went wrong ($code)."
    }
}
