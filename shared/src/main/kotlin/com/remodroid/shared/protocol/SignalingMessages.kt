package com.remodroid.shared.protocol

import com.remodroid.shared.models.PermissionLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kotlin mirror of the signaling protocol defined in server/src/signaling/protocol.ts
 * and documented in docs/protocol.md. Keep the three in sync.
 */
const val PROTOCOL_VERSION = 1

@Serializable
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class IceCandidate(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int?,
)

// ------------------------------------------------------------------ client -> server

@Serializable
sealed interface ClientMessage

@Serializable @SerialName("register_host")
data class RegisterHost(val displayName: String? = null) : ClientMessage

@Serializable @SerialName("refresh_password")
data object RefreshPassword : ClientMessage

@Serializable @SerialName("set_availability")
data class SetAvailability(val available: Boolean) : ClientMessage

@Serializable @SerialName("unregister_host")
data object UnregisterHost : ClientMessage

@Serializable @SerialName("approve_connection")
data class ApproveConnection(val sessionId: String, val permission: PermissionLevel) : ClientMessage

@Serializable @SerialName("reject_connection")
data class RejectConnection(val sessionId: String, val reason: String? = null) : ClientMessage

@Serializable @SerialName("permission_update")
data class PermissionUpdate(val sessionId: String, val permission: PermissionLevel) : ClientMessage

@Serializable @SerialName("webrtc_offer")
data class SendOffer(val sessionId: String, val sdp: String) : ClientMessage

@Serializable @SerialName("request_connection")
data class RequestConnection(
    val requestId: String,
    val targetId: String,
    val password: String,
    val permission: PermissionLevel,
    val controllerName: String? = null,
) : ClientMessage {
    override fun toString() = "RequestConnection(requestId=$requestId, permission=$permission)"
}

@Serializable @SerialName("cancel_request")
data class CancelRequest(val sessionId: String) : ClientMessage

@Serializable @SerialName("webrtc_answer")
data class SendAnswer(val sessionId: String, val sdp: String) : ClientMessage

@Serializable @SerialName("ice_candidate")
data class SendIceCandidate(val sessionId: String, val candidate: IceCandidate) : ClientMessage

@Serializable @SerialName("media_state")
data class MediaState(val sessionId: String, val connected: Boolean, val relayed: Boolean? = null) : ClientMessage

@Serializable @SerialName("end_session")
data class EndSession(val sessionId: String, val reason: String? = null) : ClientMessage

@Serializable @SerialName("resume")
data class Resume(val token: String, val requestId: String? = null) : ClientMessage {
    override fun toString() = "Resume(token=***)"
}

@Serializable @SerialName("session_heartbeat")
data object SessionHeartbeat : ClientMessage

// ------------------------------------------------------------------ server -> client

@Serializable
sealed interface ServerMessage

@Serializable @SerialName("host_registered")
data class HostRegistered(
    val deviceId: String,
    val password: String,
    val idKind: String,
    val passwordExpiresAt: Long,
    val registrationExpiresAt: Long,
    val resumeToken: String,
) : ServerMessage {
    override fun toString() = "HostRegistered(idKind=$idKind)"
}

@Serializable @SerialName("password_refreshed")
data class PasswordRefreshed(val password: String, val passwordExpiresAt: Long) : ServerMessage {
    override fun toString() = "PasswordRefreshed(***)"
}

@Serializable @SerialName("password_expired")
data object PasswordExpired : ServerMessage

@Serializable @SerialName("availability_updated")
data class AvailabilityUpdated(val available: Boolean) : ServerMessage

@Serializable @SerialName("host_unregistered")
data object HostUnregistered : ServerMessage

@Serializable @SerialName("registration_expired")
data object RegistrationExpired : ServerMessage

@Serializable @SerialName("connection_request_received")
data class ConnectionRequestReceived(
    val sessionId: String,
    val controllerName: String?,
    val requestedPermission: PermissionLevel,
    val expiresAt: Long,
) : ServerMessage

@Serializable @SerialName("request_cancelled")
data class RequestCancelled(val sessionId: String, val reason: String) : ServerMessage

@Serializable @SerialName("connection_pending")
data class ConnectionPending(val requestId: String, val sessionId: String, val approvalExpiresAt: Long) : ServerMessage

@Serializable @SerialName("session_approved")
data class SessionApproved(
    val sessionId: String,
    val role: String,
    val permission: PermissionLevel,
    val requestedPermission: PermissionLevel,
    val peerName: String?,
    val iceServers: List<IceServer>,
    val resumeToken: String? = null,
    val expiresAt: Long,
) : ServerMessage {
    override fun toString() = "SessionApproved(role=$role, permission=$permission)"
}

@Serializable @SerialName("session_rejected")
data class SessionRejected(val sessionId: String, val requestId: String, val reason: String) : ServerMessage

@Serializable @SerialName("webrtc_offer")
data class OfferReceived(val sessionId: String, val sdp: String) : ServerMessage

@Serializable @SerialName("webrtc_answer")
data class AnswerReceived(val sessionId: String, val sdp: String) : ServerMessage

@Serializable @SerialName("ice_candidate")
data class IceCandidateReceived(val sessionId: String, val candidate: IceCandidate) : ServerMessage

@Serializable @SerialName("permission_updated")
data class PermissionUpdated(val sessionId: String, val permission: PermissionLevel) : ServerMessage

@Serializable @SerialName("peer_connection_lost")
data class PeerConnectionLost(val sessionId: String, val graceMs: Long) : ServerMessage

@Serializable @SerialName("peer_reconnected")
data class PeerReconnected(val sessionId: String) : ServerMessage

@Serializable @SerialName("session_ended")
data class SessionEnded(val sessionId: String, val reason: String, val endedBy: String) : ServerMessage

@Serializable
data class ResumedSession(
    val sessionId: String,
    val state: String,
    val permission: PermissionLevel?,
    val iceServers: List<IceServer>,
)

@Serializable @SerialName("resumed")
data class Resumed(
    val kind: String,
    val resumeToken: String,
    val requestId: String? = null,
    val deviceId: String? = null,
    val passwordExpiresAt: Long? = null,
    val registrationExpiresAt: Long? = null,
    val session: ResumedSession? = null,
) : ServerMessage {
    override fun toString() = "Resumed(kind=$kind, session=${session?.state})"
}

@Serializable @SerialName("heartbeat_ack")
data class HeartbeatAck(val serverTime: Long) : ServerMessage

@Serializable @SerialName("error")
data class ServerError(
    val code: String,
    val message: String,
    val ref: String? = null,
    val requestId: String? = null,
    val sessionId: String? = null,
    val retryAfterMs: Long? = null,
) : ServerMessage

object SignalingCodec {
    val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(message: ClientMessage): String {
        val body = json.encodeToJsonElement(ClientMessage.serializer(), message).jsonObject
        return JsonObject(mapOf("v" to JsonPrimitive(PROTOCOL_VERSION)) + body).toString()
    }

    /** Returns null for messages of an unknown type or a different protocol version. */
    fun decode(text: String): ServerMessage? = try {
        val obj = json.parseToJsonElement(text).jsonObject
        if (obj["v"]?.jsonPrimitive?.intOrNull != PROTOCOL_VERSION) {
            null
        } else {
            json.decodeFromJsonElement(ServerMessage.serializer(), obj)
        }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Test helper: decode a client message as the server would see it. */
    internal fun decodeClient(text: String): ClientMessage =
        json.decodeFromString(ClientMessage.serializer(), text)
}
