import { z } from "zod";

/**
 * RemoDroid signaling protocol, version 1.
 * Every message is a JSON object with `v` (protocol version) and `type`.
 * The authoritative description lives in docs/protocol.md; keep the two in sync.
 */
export const PROTOCOL_VERSION = 1;

export const PERMISSION_LEVELS = ["view_only", "guided_support", "extended_support", "maximum_supported"] as const;
export type PermissionLevel = (typeof PERMISSION_LEVELS)[number];

export function permissionRank(level: PermissionLevel): number {
  return PERMISSION_LEVELS.indexOf(level);
}

const v = z.literal(PROTOCOL_VERSION);
const permission = z.enum(PERMISSION_LEVELS);
const sessionId = z.string().regex(/^[A-Za-z0-9_-]{16,64}$/);
const requestId = z.string().regex(/^[A-Za-z0-9_-]{1,64}$/);
const displayName = z
  .string()
  .trim()
  .min(1)
  .max(64)
  // Printable characters only; no control characters that could spoof UI.
  .regex(/^[^\p{Cc}\p{Cf}]+$/u);
const sdp = z.string().min(1).max(32 * 1024);

const iceCandidate = z.strictObject({
  candidate: z.string().max(2048),
  sdpMid: z.string().max(64).nullable(),
  sdpMLineIndex: z.number().int().min(0).max(64).nullable(),
});

const msg = <T extends string, S extends z.ZodRawShape>(type: T, shape: S) =>
  z.strictObject({ v, type: z.literal(type), ...shape });

export const ClientMessageSchema = z.discriminatedUnion("type", [
  // Host role
  msg("register_host", { displayName: displayName.optional() }),
  msg("refresh_password", {}),
  msg("set_availability", { available: z.boolean() }),
  msg("unregister_host", {}),
  msg("approve_connection", { sessionId, permission }),
  msg("reject_connection", { sessionId, reason: z.enum(["rejected_by_host", "busy"]).optional() }),
  msg("permission_update", { sessionId, permission }),
  msg("webrtc_offer", { sessionId, sdp }),

  // Controller role
  msg("request_connection", {
    requestId,
    targetId: z.string().max(32),
    password: z.string().min(1).max(64),
    permission,
    controllerName: displayName.optional(),
  }),
  msg("cancel_request", { sessionId }),
  msg("webrtc_answer", { sessionId, sdp }),

  // Either role
  msg("ice_candidate", { sessionId, candidate: iceCandidate }),
  msg("media_state", { sessionId, connected: z.boolean(), relayed: z.boolean().optional() }),
  msg("end_session", { sessionId, reason: z.enum(["user_ended", "permission_revoked", "error"]).optional() }),
  msg("resume", { token: z.string().min(16).max(128), requestId: requestId.optional() }),
  msg("session_heartbeat", {}),
]);

export type ClientMessage = z.infer<typeof ClientMessageSchema>;
export type ClientMessageOf<T extends ClientMessage["type"]> = Extract<ClientMessage, { type: T }>;

export interface IceServerDto {
  urls: string[];
  username?: string;
  credential?: string;
}

export type ErrorCode =
  | "malformed_message"
  | "unsupported_version"
  | "message_too_large"
  | "rate_limited"
  | "already_registered"
  | "not_registered"
  | "invalid_credentials"
  | "host_unavailable"
  | "host_busy"
  | "cannot_connect_to_self"
  | "already_in_session"
  | "unknown_session"
  | "not_authorized"
  | "invalid_state"
  | "permission_exceeds_request"
  | "peer_unavailable"
  | "resume_failed"
  | "internal_error";

export type EndReason =
  | "user_ended"
  | "permission_revoked"
  | "error"
  | "peer_lost"
  | "host_stopped_sharing"
  | "registration_expired"
  | "session_expired"
  | "server_shutdown";

/** Messages the server sends. Typed here so the gateway cannot send ad-hoc shapes. */
export type ServerMessage = { v: typeof PROTOCOL_VERSION } & (
  | {
      type: "host_registered";
      deviceId: string;
      password: string;
      idKind: "temporary";
      passwordExpiresAt: number;
      registrationExpiresAt: number;
      resumeToken: string;
    }
  | { type: "password_refreshed"; password: string; passwordExpiresAt: number }
  | { type: "password_expired" }
  | { type: "availability_updated"; available: boolean }
  | { type: "host_unregistered" }
  | { type: "registration_expired" }
  | {
      type: "connection_request_received";
      sessionId: string;
      controllerName: string | null;
      requestedPermission: PermissionLevel;
      expiresAt: number;
    }
  | { type: "request_cancelled"; sessionId: string; reason: "cancelled_by_controller" | "approval_timeout" | "peer_lost" }
  | { type: "connection_pending"; requestId: string; sessionId: string; approvalExpiresAt: number }
  | {
      type: "session_approved";
      sessionId: string;
      role: "host" | "controller";
      permission: PermissionLevel;
      requestedPermission: PermissionLevel;
      peerName: string | null;
      iceServers: IceServerDto[];
      resumeToken?: string;
      expiresAt: number;
    }
  | {
      type: "session_rejected";
      sessionId: string;
      requestId: string;
      reason: "rejected_by_host" | "busy" | "approval_timeout" | "peer_lost";
    }
  | { type: "webrtc_offer"; sessionId: string; sdp: string }
  | { type: "webrtc_answer"; sessionId: string; sdp: string }
  | {
      type: "ice_candidate";
      sessionId: string;
      candidate: { candidate: string; sdpMid: string | null; sdpMLineIndex: number | null };
    }
  | { type: "permission_updated"; sessionId: string; permission: PermissionLevel }
  | { type: "peer_connection_lost"; sessionId: string; graceMs: number }
  | { type: "peer_reconnected"; sessionId: string }
  | { type: "session_ended"; sessionId: string; reason: EndReason; endedBy: "host" | "controller" | "server" }
  | {
      type: "resumed";
      kind: "host" | "controller";
      resumeToken: string;
      requestId?: string;
      deviceId?: string;
      passwordExpiresAt?: number;
      registrationExpiresAt?: number;
      session: {
        sessionId: string;
        state: "pending" | "approved";
        permission: PermissionLevel | null;
        iceServers: IceServerDto[];
      } | null;
    }
  | { type: "heartbeat_ack"; serverTime: number }
  | {
      type: "error";
      code: ErrorCode;
      message: string;
      ref?: string;
      requestId?: string;
      sessionId?: string;
      retryAfterMs?: number;
    }
);
