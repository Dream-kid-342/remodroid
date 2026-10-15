import type { Logger } from "pino";
import type { Config } from "../config/index.js";
import {
  generatePassword,
  hashSecret,
  isValidDeviceId,
  logLabel,
  normalizeDeviceId,
  verifyAgainstDummy,
  verifySecret,
} from "../security/credentials.js";
import { FailureTracker, KeyedRateLimiter, type Clock } from "../security/rateLimiter.js";
import { Registry, type HostRecord, type Peer, type SessionRecord } from "../sessions/registry.js";
import {
  PROTOCOL_VERSION,
  permissionRank,
  type ClientMessage,
  type ClientMessageOf,
  type EndReason,
  type ErrorCode,
  type ServerMessage,
} from "../signaling/protocol.js";
import type { IceServerService } from "./iceServers.js";

type Role = "host" | "controller";
type Body<T extends ServerMessage["type"]> = Omit<Extract<ServerMessage, { type: T }>, "v" | "type">;

function out<T extends ServerMessage["type"]>(type: T, body: Body<T>): ServerMessage {
  return { v: PROTOCOL_VERSION, type, ...body } as unknown as ServerMessage;
}

/**
 * Signaling logic: host registration, credential checks, request/approval flow, WebRTC relay,
 * reconnection and cleanup. Holds no transport code so it can be tested directly.
 *
 * The server never grants control on its own: it only relays the host's decision.
 * Hosts enforce permissions locally on every received input event.
 */
export class SignalingService {
  readonly registry = new Registry();
  private readonly authFailures: FailureTracker;
  private readonly registerLimiter: KeyedRateLimiter;
  private readonly requestLimiter: KeyedRateLimiter;

  constructor(
    private readonly config: Config,
    private readonly logger: Logger,
    private readonly ice: IceServerService,
    private readonly now: Clock = Date.now,
  ) {
    this.authFailures = new FailureTracker(
      {
        maxFailures: config.auth.maxFailures,
        windowMs: config.auth.failureWindowMs,
        lockoutBaseMs: config.auth.lockoutBaseMs,
        lockoutMaxMs: config.auth.lockoutMaxMs,
      },
      now,
    );
    // Per IP: bursts of 10 registrations / 20 connection requests, refilling over a minute.
    this.registerLimiter = new KeyedRateLimiter(10, 10 / 60, now);
    this.requestLimiter = new KeyedRateLimiter(20, 20 / 60, now);
  }

  async handle(peer: Peer, message: ClientMessage): Promise<void> {
    switch (message.type) {
      case "register_host":
        return this.registerHost(peer, message);
      case "refresh_password":
        return this.refreshPassword(peer);
      case "set_availability":
        return this.setAvailability(peer, message);
      case "unregister_host":
        return this.unregisterHost(peer);
      case "request_connection":
        return this.requestConnection(peer, message);
      case "approve_connection":
        return this.approveConnection(peer, message);
      case "reject_connection":
        return this.rejectConnection(peer, message);
      case "cancel_request":
        return this.cancelRequest(peer, message);
      case "webrtc_offer":
        return this.relay(peer, message, "host", { type: "webrtc_offer", sdp: message.sdp });
      case "webrtc_answer":
        return this.relay(peer, message, "controller", { type: "webrtc_answer", sdp: message.sdp });
      case "ice_candidate":
        return this.relay(peer, message, "either", { type: "ice_candidate", candidate: message.candidate });
      case "permission_update":
        return this.permissionUpdate(peer, message);
      case "media_state":
        return this.mediaState(peer, message);
      case "end_session":
        return this.endSession(peer, message);
      case "resume":
        return this.resume(peer, message);
      case "session_heartbeat":
        peer.send(out("heartbeat_ack", { serverTime: this.now() }));
        return;
    }
  }

  // ---------------------------------------------------------------- host role

  private async registerHost(peer: Peer, m: ClientMessageOf<"register_host">): Promise<void> {
    if (peer.hostId) return this.error(peer, "already_registered", "This connection is already registered", m.type);
    if (!this.registerLimiter.take(peer.ip)) {
      return this.error(peer, "rate_limited", "Too many registrations; try again shortly", m.type, {
        retryAfterMs: 6000,
      });
    }
    const password = generatePassword();
    const stored = await hashSecret(password);
    if (peer.closed || peer.hostId) return;

    const t = this.now();
    const deviceId = this.registry.allocateDeviceId();
    const resume = this.registry.issueResumeToken({ kind: "host", deviceId }, null);
    const host: HostRecord = {
      deviceId,
      peer,
      displayName: m.displayName ?? null,
      password: stored,
      passwordExpiresAt: t + this.config.passwordTtlMs,
      passwordExpiryNotified: false,
      registrationExpiresAt: t + this.config.hostRegistrationTtlMs,
      resumeTokenHash: resume.hash,
      available: true,
      activeSessionId: null,
      disconnectedAt: null,
    };
    this.registry.hosts.set(deviceId, host);
    peer.hostId = deviceId;
    peer.send(
      out("host_registered", {
        deviceId,
        password,
        idKind: "temporary",
        passwordExpiresAt: host.passwordExpiresAt,
        registrationExpiresAt: host.registrationExpiresAt,
        resumeToken: resume.token,
      }),
    );
    this.logger.info({ host: logLabel(deviceId), peer: peer.id }, "host registered");
  }

  private async refreshPassword(peer: Peer): Promise<void> {
    const host = this.requireHost(peer, "refresh_password");
    if (!host) return;
    const password = generatePassword();
    const stored = await hashSecret(password);
    if (this.registry.hosts.get(host.deviceId) !== host || host.peer !== peer) return;
    // Replacing the hash invalidates the previous password immediately.
    host.password = stored;
    host.passwordExpiresAt = this.now() + this.config.passwordTtlMs;
    host.passwordExpiryNotified = false;
    peer.send(out("password_refreshed", { password, passwordExpiresAt: host.passwordExpiresAt }));
    this.logger.info({ host: logLabel(host.deviceId) }, "password refreshed");
  }

  private setAvailability(peer: Peer, m: ClientMessageOf<"set_availability">): void {
    const host = this.requireHost(peer, m.type);
    if (!host) return;
    host.available = m.available;
    if (!m.available && host.activeSessionId) {
      const session = this.registry.sessions.get(host.activeSessionId);
      if (session?.state === "pending") this.rejectPending(session, "busy");
    }
    peer.send(out("availability_updated", { available: host.available }));
  }

  private unregisterHost(peer: Peer): void {
    const host = this.requireHost(peer, "unregister_host");
    if (!host) return;
    this.removeHost(host, "host_stopped_sharing", "host");
    peer.send(out("host_unregistered", {}));
  }

  private approveConnection(peer: Peer, m: ClientMessageOf<"approve_connection">): void {
    const session = this.requireOwnedSession(peer, m.sessionId, "host", m.type);
    if (!session) return;
    if (session.state !== "pending") return this.error(peer, "invalid_state", "Session is not awaiting approval", m.type);
    if (permissionRank(m.permission) > permissionRank(session.requestedPermission)) {
      return this.error(peer, "permission_exceeds_request", "Cannot grant more than the controller requested", m.type, {
        sessionId: session.sessionId,
      });
    }
    const controller = session.controller;
    if (!controller) return this.error(peer, "peer_unavailable", "The controller is no longer connected", m.type);
    const host = this.registry.hostOf(session);

    const t = this.now();
    session.state = "approved";
    session.grantedPermission = m.permission;
    session.approvedAt = t;
    session.expiresAt = t + this.config.sessionMaxDurationMs;
    const resume = this.registry.issueResumeToken({ kind: "controller", sessionId: session.sessionId }, null);
    session.controllerResumeTokenHash = resume.hash;

    const common = {
      sessionId: session.sessionId,
      permission: m.permission,
      requestedPermission: session.requestedPermission,
      expiresAt: session.expiresAt,
    };
    peer.send(
      out("session_approved", {
        ...common,
        role: "host",
        peerName: session.controllerName,
        iceServers: this.ice.forSession(),
      }),
    );
    controller.send(
      out("session_approved", {
        ...common,
        role: "controller",
        peerName: host?.displayName ?? null,
        iceServers: this.ice.forSession(),
        resumeToken: resume.token,
      }),
    );
    this.logger.info({ session: logLabel(session.sessionId), permission: m.permission }, "session approved");
  }

  private rejectConnection(peer: Peer, m: ClientMessageOf<"reject_connection">): void {
    const session = this.requireOwnedSession(peer, m.sessionId, "host", m.type);
    if (!session) return;
    if (session.state !== "pending") return this.error(peer, "invalid_state", "Session is not awaiting approval", m.type);
    this.rejectPending(session, m.reason ?? "rejected_by_host");
    this.logger.info({ session: logLabel(session.sessionId) }, "session rejected by host");
  }

  private permissionUpdate(peer: Peer, m: ClientMessageOf<"permission_update">): void {
    const session = this.requireOwnedSession(peer, m.sessionId, "host", m.type);
    if (!session) return;
    if (session.state !== "approved") return this.error(peer, "invalid_state", "Session is not active", m.type);
    if (permissionRank(m.permission) > permissionRank(session.requestedPermission)) {
      return this.error(peer, "permission_exceeds_request", "Cannot grant more than the controller requested", m.type, {
        sessionId: session.sessionId,
      });
    }
    session.grantedPermission = m.permission;
    const msg = out("permission_updated", { sessionId: session.sessionId, permission: m.permission });
    peer.send(msg);
    session.controller?.send(msg);
    this.logger.info({ session: logLabel(session.sessionId), permission: m.permission }, "permission updated by host");
  }

  // ---------------------------------------------------------------- controller role

  private async requestConnection(peer: Peer, m: ClientMessageOf<"request_connection">): Promise<void> {
    const reqId = { requestId: m.requestId };
    if (peer.controllerSessionId) {
      return this.error(peer, "already_in_session", "This connection already has a session", m.type, reqId);
    }
    if (!this.requestLimiter.take(peer.ip)) {
      return this.error(peer, "rate_limited", "Too many connection attempts; try again shortly", m.type, {
        ...reqId,
        retryAfterMs: 3000,
      });
    }

    const targetId = normalizeDeviceId(m.targetId);
    const ipKey = `ip:${peer.ip}`;
    const idKey = `id:${targetId}`;
    const lockedFor = Math.max(this.authFailures.lockedFor(ipKey), this.authFailures.lockedFor(idKey));
    if (lockedFor > 0) {
      return this.error(peer, "rate_limited", "Too many failed attempts; try again later", m.type, {
        ...reqId,
        retryAfterMs: lockedFor,
      });
    }

    const host = isValidDeviceId(targetId) ? this.registry.hosts.get(targetId) : undefined;
    let valid = false;
    if (host) {
      const stored = host.password;
      const matches = await verifySecret(m.password, stored);
      // The password must still be current (not refreshed during verification) and not expired.
      valid = matches && host.password === stored && host.passwordExpiresAt > this.now();
    } else {
      await verifyAgainstDummy(m.password);
    }
    if (peer.closed) return;

    if (!valid || !host || this.registry.hosts.get(targetId) !== host) {
      this.authFailures.recordFailure(ipKey);
      if (host) this.authFailures.recordFailure(idKey);
      this.logger.warn({ ip: logLabel(peer.ip), peer: peer.id }, "connection request with invalid credentials");
      // Same response for unknown IDs, wrong passwords and expired passwords.
      return this.error(peer, "invalid_credentials", "The partner ID or password is incorrect", m.type, reqId);
    }
    this.authFailures.recordSuccess(ipKey);

    if (peer.hostId === targetId) {
      return this.error(peer, "cannot_connect_to_self", "You cannot connect to your own device", m.type, reqId);
    }
    if (peer.controllerSessionId) {
      return this.error(peer, "already_in_session", "This connection already has a session", m.type, reqId);
    }
    if (!host.peer || !host.available) {
      return this.error(peer, "host_unavailable", "The partner device is not accepting connections", m.type, reqId);
    }
    if (host.activeSessionId) {
      return this.error(peer, "host_busy", "The partner device is already in a session", m.type, reqId);
    }

    const t = this.now();
    const session: SessionRecord = {
      sessionId: this.registry.allocateSessionId(),
      requestId: m.requestId,
      hostId: host.deviceId,
      controller: peer,
      controllerName: m.controllerName ?? null,
      state: "pending",
      requestedPermission: m.permission,
      grantedPermission: null,
      createdAt: t,
      approvalExpiresAt: t + this.config.approvalTimeoutMs,
      approvedAt: null,
      expiresAt: t + this.config.approvalTimeoutMs,
      controllerResumeTokenHash: null,
      controllerDisconnectedAt: null,
      mediaConnected: false,
    };
    this.registry.sessions.set(session.sessionId, session);
    host.activeSessionId = session.sessionId;
    peer.controllerSessionId = session.sessionId;

    peer.send(
      out("connection_pending", {
        requestId: m.requestId,
        sessionId: session.sessionId,
        approvalExpiresAt: session.approvalExpiresAt,
      }),
    );
    host.peer.send(
      out("connection_request_received", {
        sessionId: session.sessionId,
        controllerName: session.controllerName,
        requestedPermission: session.requestedPermission,
        expiresAt: session.approvalExpiresAt,
      }),
    );
    this.logger.info(
      { session: logLabel(session.sessionId), host: logLabel(host.deviceId), permission: m.permission },
      "connection request forwarded to host",
    );
  }

  private cancelRequest(peer: Peer, m: ClientMessageOf<"cancel_request">): void {
    const session = this.requireOwnedSession(peer, m.sessionId, "controller", m.type);
    if (!session) return;
    if (session.state !== "pending") return this.error(peer, "invalid_state", "Session is not awaiting approval", m.type);
    this.registry.hostOf(session)?.peer?.send(
      out("request_cancelled", { sessionId: session.sessionId, reason: "cancelled_by_controller" }),
    );
    this.cleanupSession(session);
  }

  // ---------------------------------------------------------------- either role

  private relay(
    peer: Peer,
    m: { type: string; sessionId: string },
    allowed: Role | "either",
    payload:
      | { type: "webrtc_offer"; sdp: string }
      | { type: "webrtc_answer"; sdp: string }
      | Pick<Extract<ServerMessage, { type: "ice_candidate" }>, "type" | "candidate">,
  ): void {
    const session = this.registry.sessions.get(m.sessionId);
    const role = session ? this.roleIn(peer, session) : undefined;
    if (!session || !role) return this.error(peer, "unknown_session", "Unknown session", m.type);
    if (allowed !== "either" && role !== allowed) {
      return this.error(peer, "not_authorized", `Only the ${allowed} may send ${m.type}`, m.type);
    }
    if (session.state !== "approved") return this.error(peer, "invalid_state", "Session has not been approved", m.type);
    const target = role === "host" ? session.controller : this.registry.hostOf(session)?.peer;
    if (!target) {
      return this.error(peer, "peer_unavailable", "The other device is reconnecting", m.type, {
        sessionId: session.sessionId,
      });
    }
    target.send({ v: PROTOCOL_VERSION, sessionId: session.sessionId, ...payload } as ServerMessage);
  }

  private mediaState(peer: Peer, m: ClientMessageOf<"media_state">): void {
    const session = this.registry.sessions.get(m.sessionId);
    const role = session ? this.roleIn(peer, session) : undefined;
    if (!session || !role) return this.error(peer, "unknown_session", "Unknown session", m.type);
    session.mediaConnected = m.connected;
    this.logger.info(
      { session: logLabel(session.sessionId), role, connected: m.connected, relayed: m.relayed ?? null },
      "media state reported",
    );
  }

  private endSession(peer: Peer, m: ClientMessageOf<"end_session">): void {
    const session = this.registry.sessions.get(m.sessionId);
    const role = session ? this.roleIn(peer, session) : undefined;
    if (!session || !role) return this.error(peer, "unknown_session", "Unknown session", m.type);
    this.terminate(session, m.reason ?? "user_ended", role);
  }

  private resume(peer: Peer, m: ClientMessageOf<"resume">): void {
    // requestId lets a client that resumes both roles on one socket match each reply.
    const echo = m.requestId ? { requestId: m.requestId } : {};
    const target = this.registry.lookupResumeToken(m.token);
    if (!target) return this.error(peer, "resume_failed", "Nothing to resume; register again", m.type, echo);

    if (target.kind === "host") {
      const host = this.registry.hosts.get(target.deviceId);
      if (!host) return this.error(peer, "resume_failed", "Nothing to resume; register again", m.type, echo);
      if (peer.hostId && peer.hostId !== host.deviceId) {
        return this.error(peer, "already_registered", "This connection hosts another ID", m.type, echo);
      }
      const old = host.peer;
      if (old && old !== peer) {
        // Duplicate login: the newest connection that proves the resume token wins.
        old.hostId = undefined;
        old.close(4001, "Replaced by a newer connection");
      }
      host.peer = peer;
      host.disconnectedAt = null;
      peer.hostId = host.deviceId;
      const resume = this.registry.issueResumeToken({ kind: "host", deviceId: host.deviceId }, host.resumeTokenHash);
      host.resumeTokenHash = resume.hash;

      const session = host.activeSessionId ? this.registry.sessions.get(host.activeSessionId) : undefined;
      if (session?.state === "approved") session.controller?.send(out("peer_reconnected", { sessionId: session.sessionId }));
      peer.send(
        out("resumed", {
          ...echo,
          kind: "host",
          resumeToken: resume.token,
          deviceId: host.deviceId,
          passwordExpiresAt: host.passwordExpiresAt,
          registrationExpiresAt: host.registrationExpiresAt,
          session: session
            ? {
                sessionId: session.sessionId,
                state: session.state,
                permission: session.grantedPermission,
                iceServers: session.state === "approved" ? this.ice.forSession() : [],
              }
            : null,
        }),
      );
      // A pending request may have arrived while the host was away; re-deliver it.
      if (session?.state === "pending") {
        peer.send(
          out("connection_request_received", {
            sessionId: session.sessionId,
            controllerName: session.controllerName,
            requestedPermission: session.requestedPermission,
            expiresAt: session.approvalExpiresAt,
          }),
        );
      }
      this.logger.info({ host: logLabel(host.deviceId), peer: peer.id }, "host resumed");
      return;
    }

    const session = this.registry.sessions.get(target.sessionId);
    if (!session || session.state !== "approved") {
      return this.error(peer, "resume_failed", "Session is no longer active", m.type, echo);
    }
    if (peer.controllerSessionId && peer.controllerSessionId !== session.sessionId) {
      return this.error(peer, "already_in_session", "This connection already has a session", m.type, echo);
    }
    const old = session.controller;
    if (old && old !== peer) {
      old.controllerSessionId = undefined;
      old.close(4001, "Replaced by a newer connection");
    }
    session.controller = peer;
    session.controllerDisconnectedAt = null;
    peer.controllerSessionId = session.sessionId;
    const resume = this.registry.issueResumeToken(
      { kind: "controller", sessionId: session.sessionId },
      session.controllerResumeTokenHash,
    );
    session.controllerResumeTokenHash = resume.hash;
    this.registry.hostOf(session)?.peer?.send(out("peer_reconnected", { sessionId: session.sessionId }));
    peer.send(
      out("resumed", {
        ...echo,
        kind: "controller",
        resumeToken: resume.token,
        session: {
          sessionId: session.sessionId,
          state: "approved",
          permission: session.grantedPermission,
          iceServers: this.ice.forSession(),
        },
      }),
    );
    this.logger.info({ session: logLabel(session.sessionId), peer: peer.id }, "controller resumed");
  }

  // ---------------------------------------------------------------- lifecycle

  /** Called by the transport when a connection closes. Starts the resume grace period. */
  onDisconnect(peer: Peer): void {
    const t = this.now();
    if (peer.hostId) {
      const host = this.registry.hosts.get(peer.hostId);
      if (host && host.peer === peer) {
        host.peer = null;
        host.disconnectedAt = t;
        const session = host.activeSessionId ? this.registry.sessions.get(host.activeSessionId) : undefined;
        if (session?.state === "approved") {
          session.controller?.send(
            out("peer_connection_lost", { sessionId: session.sessionId, graceMs: this.config.resumeGraceMs }),
          );
        }
        this.logger.info({ host: logLabel(host.deviceId) }, "host connection lost; awaiting resume");
      }
    }
    if (peer.controllerSessionId) {
      const session = this.registry.sessions.get(peer.controllerSessionId);
      if (session && session.controller === peer) {
        if (session.state === "pending") {
          this.registry.hostOf(session)?.peer?.send(
            out("request_cancelled", { sessionId: session.sessionId, reason: "peer_lost" }),
          );
          this.cleanupSession(session);
        } else {
          session.controller = null;
          session.controllerDisconnectedAt = t;
          this.registry.hostOf(session)?.peer?.send(
            out("peer_connection_lost", { sessionId: session.sessionId, graceMs: this.config.resumeGraceMs }),
          );
          this.logger.info({ session: logLabel(session.sessionId) }, "controller connection lost; awaiting resume");
        }
      }
    }
    peer.hostId = undefined;
    peer.controllerSessionId = undefined;
  }

  /** Expires credentials, approvals, grace periods and sessions. Called periodically. */
  sweep(): void {
    const t = this.now();
    for (const host of this.registry.hosts.values()) {
      if (host.peer === null && host.disconnectedAt !== null && t - host.disconnectedAt >= this.config.resumeGraceMs) {
        this.logger.info({ host: logLabel(host.deviceId) }, "host did not resume; removing registration");
        this.removeHost(host, "peer_lost", "server");
      } else if (t >= host.registrationExpiresAt) {
        host.peer?.send(out("registration_expired", {}));
        this.removeHost(host, "registration_expired", "server");
      } else if (!host.passwordExpiryNotified && t >= host.passwordExpiresAt) {
        host.passwordExpiryNotified = true;
        host.peer?.send(out("password_expired", {}));
      }
    }
    for (const session of this.registry.sessions.values()) {
      if (session.state === "pending" && t >= session.approvalExpiresAt) {
        session.controller?.send(
          out("session_rejected", {
            sessionId: session.sessionId,
            requestId: session.requestId,
            reason: "approval_timeout",
          }),
        );
        this.registry.hostOf(session)?.peer?.send(
          out("request_cancelled", { sessionId: session.sessionId, reason: "approval_timeout" }),
        );
        this.cleanupSession(session);
      } else if (session.state === "approved") {
        if (
          session.controller === null &&
          session.controllerDisconnectedAt !== null &&
          t - session.controllerDisconnectedAt >= this.config.resumeGraceMs
        ) {
          this.terminate(session, "peer_lost", "server");
        } else if (t >= session.expiresAt) {
          this.terminate(session, "session_expired", "server");
        }
      }
    }
    this.authFailures.prune();
    this.registerLimiter.prune(10 * 60_000);
    this.requestLimiter.prune(10 * 60_000);
  }

  /** Ends every session so clients learn about a deliberate shutdown instead of a silent drop. */
  shutdown(): void {
    for (const session of [...this.registry.sessions.values()]) this.terminate(session, "server_shutdown", "server");
  }

  stats(): { hosts: number; sessions: number } {
    return { hosts: this.registry.hosts.size, sessions: this.registry.sessions.size };
  }

  // ---------------------------------------------------------------- helpers

  private requireHost(peer: Peer, ref: string): HostRecord | undefined {
    const host = peer.hostId ? this.registry.hosts.get(peer.hostId) : undefined;
    if (!host || host.peer !== peer) {
      this.error(peer, "not_registered", "Register as a host first", ref);
      return undefined;
    }
    return host;
  }

  private requireOwnedSession(peer: Peer, sessionId: string, role: Role, ref: string): SessionRecord | undefined {
    const session = this.registry.sessions.get(sessionId);
    const actual = session ? this.roleIn(peer, session) : undefined;
    if (!session || !actual) {
      this.error(peer, "unknown_session", "Unknown session", ref);
      return undefined;
    }
    if (actual !== role) {
      this.error(peer, "not_authorized", `Only the ${role} may send ${ref}`, ref);
      return undefined;
    }
    return session;
  }

  private roleIn(peer: Peer, session: SessionRecord): Role | undefined {
    if (session.controller === peer) return "controller";
    if (this.registry.hostOf(session)?.peer === peer) return "host";
    return undefined;
  }

  private rejectPending(session: SessionRecord, reason: "rejected_by_host" | "busy" | "peer_lost"): void {
    session.controller?.send(
      out("session_rejected", { sessionId: session.sessionId, requestId: session.requestId, reason }),
    );
    this.cleanupSession(session);
  }

  private terminate(session: SessionRecord, reason: EndReason, endedBy: Role | "server"): void {
    if (session.state === "pending") {
      session.controller?.send(
        out("session_rejected", { sessionId: session.sessionId, requestId: session.requestId, reason: "peer_lost" }),
      );
      this.registry.hostOf(session)?.peer?.send(
        out("request_cancelled", { sessionId: session.sessionId, reason: "peer_lost" }),
      );
    } else {
      const msg = out("session_ended", { sessionId: session.sessionId, reason, endedBy });
      this.registry.hostOf(session)?.peer?.send(msg);
      session.controller?.send(msg);
    }
    this.cleanupSession(session);
    this.logger.info({ session: logLabel(session.sessionId), reason, endedBy }, "session ended");
  }

  private cleanupSession(session: SessionRecord): void {
    this.registry.sessions.delete(session.sessionId);
    const host = this.registry.hostOf(session);
    if (host && host.activeSessionId === session.sessionId) host.activeSessionId = null;
    if (session.controller && session.controller.controllerSessionId === session.sessionId) {
      session.controller.controllerSessionId = undefined;
    }
    this.registry.revokeResumeToken(session.controllerResumeTokenHash);
  }

  private removeHost(host: HostRecord, reason: EndReason, endedBy: Role | "server"): void {
    const session = host.activeSessionId ? this.registry.sessions.get(host.activeSessionId) : undefined;
    if (session) {
      if (session.state === "pending") this.rejectPending(session, "peer_lost");
      else this.terminate(session, reason, endedBy);
    }
    this.registry.revokeResumeToken(host.resumeTokenHash);
    this.registry.hosts.delete(host.deviceId);
    if (host.peer && host.peer.hostId === host.deviceId) host.peer.hostId = undefined;
  }

  private error(
    peer: Peer,
    code: ErrorCode,
    message: string,
    ref?: string,
    extra: { requestId?: string; sessionId?: string; retryAfterMs?: number } = {},
  ): void {
    peer.send(out("error", { code, message, ...(ref ? { ref } : {}), ...extra }));
  }
}
