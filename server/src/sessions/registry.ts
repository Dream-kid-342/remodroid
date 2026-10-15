import type { StoredSecret } from "../security/credentials.js";
import { generateDeviceId, generateToken, hashToken } from "../security/credentials.js";
import type { PermissionLevel, ServerMessage } from "../signaling/protocol.js";

/** Transport-independent view of a connected client. Implemented by the WebSocket gateway. */
export interface Peer {
  readonly id: string;
  readonly ip: string;
  /** True once the underlying transport has closed. */
  readonly closed: boolean;
  send(message: ServerMessage): void;
  close(code: number, reason: string): void;
  /** Device ID this connection hosts, if any. */
  hostId: string | undefined;
  /** Session this connection is the controller of, if any. */
  controllerSessionId: string | undefined;
}

export interface HostRecord {
  deviceId: string;
  peer: Peer | null;
  displayName: string | null;
  password: StoredSecret;
  passwordExpiresAt: number;
  passwordExpiryNotified: boolean;
  registrationExpiresAt: number;
  resumeTokenHash: string;
  available: boolean;
  activeSessionId: string | null;
  disconnectedAt: number | null;
}

export type SessionState = "pending" | "approved";

export interface SessionRecord {
  sessionId: string;
  requestId: string;
  hostId: string;
  controller: Peer | null;
  controllerName: string | null;
  state: SessionState;
  requestedPermission: PermissionLevel;
  grantedPermission: PermissionLevel | null;
  createdAt: number;
  approvalExpiresAt: number;
  approvedAt: number | null;
  expiresAt: number;
  controllerResumeTokenHash: string | null;
  controllerDisconnectedAt: number | null;
  mediaConnected: boolean;
}

export type ResumeTarget = { kind: "host"; deviceId: string } | { kind: "controller"; sessionId: string };

/**
 * In-memory registry of hosts and sessions. Contents are lost when the process restarts;
 * clients detect this through a failed `resume` and re-register.
 *
 * Only what is needed for signaling is stored: no frames, no plaintext passwords or tokens,
 * no keyboard data, no personal information.
 */
export class Registry {
  readonly hosts = new Map<string, HostRecord>();
  readonly sessions = new Map<string, SessionRecord>();
  private readonly resumeIndex = new Map<string, ResumeTarget>();

  allocateDeviceId(): string {
    for (let attempt = 0; attempt < 1000; attempt++) {
      const id = generateDeviceId();
      if (!this.hosts.has(id)) return id;
    }
    throw new Error("Unable to allocate a unique device ID");
  }

  allocateSessionId(): string {
    for (;;) {
      const id = generateToken().slice(0, 32);
      if (!this.sessions.has(id)) return id;
    }
  }

  /** Issues a new resume token for a target, replacing any previous one. Returns the plaintext token. */
  issueResumeToken(target: ResumeTarget, previousHash: string | null): { token: string; hash: string } {
    if (previousHash) this.resumeIndex.delete(previousHash);
    const token = generateToken();
    const hash = hashToken(token);
    this.resumeIndex.set(hash, target);
    return { token, hash };
  }

  lookupResumeToken(token: string): ResumeTarget | undefined {
    return this.resumeIndex.get(hashToken(token));
  }

  revokeResumeToken(hash: string | null): void {
    if (hash) this.resumeIndex.delete(hash);
  }

  get resumeTokenCount(): number {
    return this.resumeIndex.size;
  }

  hostOf(session: SessionRecord): HostRecord | undefined {
    return this.hosts.get(session.hostId);
  }
}
