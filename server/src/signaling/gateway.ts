import type { IncomingMessage } from "node:http";
import type { Duplex } from "node:stream";
import { randomBytes } from "node:crypto";
import type { Logger } from "pino";
import { WebSocket, WebSocketServer, type RawData } from "ws";
import type { Config } from "../config/index.js";
import { TokenBucket } from "../security/rateLimiter.js";
import { logLabel } from "../security/credentials.js";
import type { Peer } from "../sessions/registry.js";
import type { SignalingService } from "../services/signalingService.js";
import { PROTOCOL_VERSION, type ServerMessage } from "./protocol.js";
import { parseClientMessage } from "../validation/messages.js";

export const SIGNALING_PATH = "/ws";

/** WebSocket close codes used by the server (4000-4999 are application-defined). */
export const CloseCodes = {
  replaced: 4001,
  rateLimited: 4008,
  heartbeatTimeout: 4009,
  shutdown: 1001,
  tooLarge: 1009,
} as const;

class WsPeer implements Peer {
  hostId: string | undefined;
  controllerSessionId: string | undefined;
  alive = true;
  /** Messages from one connection are processed strictly in order. */
  queue: Promise<void> = Promise.resolve();
  readonly bucket: TokenBucket;

  constructor(
    readonly id: string,
    readonly ip: string,
    private readonly ws: WebSocket,
    config: Config,
  ) {
    this.bucket = new TokenBucket(config.messageBurst, config.messagesPerSecond);
  }

  get closed(): boolean {
    return this.ws.readyState === WebSocket.CLOSING || this.ws.readyState === WebSocket.CLOSED;
  }

  send(message: ServerMessage): void {
    if (this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(message));
  }

  close(code: number, reason: string): void {
    if (!this.closed) this.ws.close(code, reason);
  }

  terminate(): void {
    this.ws.terminate();
  }

  ping(): void {
    if (this.ws.readyState === WebSocket.OPEN) this.ws.ping();
  }
}

export class SignalingGateway {
  private readonly wss: WebSocketServer;
  private readonly peers = new Set<WsPeer>();
  private readonly connectionsPerIp = new Map<string, number>();
  private heartbeat: NodeJS.Timeout | undefined;

  constructor(
    private readonly config: Config,
    private readonly logger: Logger,
    private readonly service: SignalingService,
  ) {
    this.wss = new WebSocketServer({
      noServer: true,
      maxPayload: config.maxMessageBytes,
      perMessageDeflate: false,
    });
  }

  get connectionCount(): number {
    return this.peers.size;
  }

  start(): void {
    this.heartbeat = setInterval(() => this.checkHeartbeats(), this.config.heartbeatIntervalMs);
    this.heartbeat.unref();
  }

  /** Handles the HTTP upgrade. Rejects wrong paths, disallowed origins and over-limit IPs before the handshake. */
  handleUpgrade(req: IncomingMessage, socket: Duplex, head: Buffer): void {
    const url = new URL(req.url ?? "/", "http://localhost");
    if (url.pathname !== SIGNALING_PATH) return rejectUpgrade(socket, 404, "Not Found");

    // Native clients do not send Origin. Browsers do; only allow-listed browser origins may connect.
    const origin = req.headers.origin;
    if (origin !== undefined && !this.config.allowedOrigins.includes(origin)) {
      this.logger.warn({ origin }, "rejected websocket upgrade from disallowed origin");
      return rejectUpgrade(socket, 403, "Forbidden");
    }

    const ip = clientIp(req, this.config.trustProxy);
    const current = this.connectionsPerIp.get(ip) ?? 0;
    if (current >= this.config.maxConnectionsPerIp) {
      this.logger.warn({ ip: logLabel(ip) }, "too many connections from one address");
      return rejectUpgrade(socket, 429, "Too Many Requests");
    }

    this.wss.handleUpgrade(req, socket, head, (ws) => this.onConnection(ws, ip));
  }

  private onConnection(ws: WebSocket, ip: string): void {
    const peer = new WsPeer(randomBytes(6).toString("hex"), ip, ws, this.config);
    this.peers.add(peer);
    this.connectionsPerIp.set(ip, (this.connectionsPerIp.get(ip) ?? 0) + 1);
    this.logger.debug({ peer: peer.id, ip: logLabel(ip) }, "connection opened");

    ws.on("pong", () => {
      peer.alive = true;
    });

    ws.on("message", (data: RawData, isBinary: boolean) => {
      peer.alive = true;
      if (!peer.bucket.take()) {
        this.logger.warn({ peer: peer.id }, "message rate exceeded; closing connection");
        peer.send({ v: PROTOCOL_VERSION, type: "error", code: "rate_limited", message: "Message rate exceeded" });
        peer.close(CloseCodes.rateLimited, "Rate limited");
        return;
      }
      if (isBinary) {
        peer.send({ v: PROTOCOL_VERSION, type: "error", code: "malformed_message", message: "Binary frames are not supported" });
        return;
      }
      const parsed = parseClientMessage(rawToString(data));
      if (!parsed.ok) {
        peer.send({
          v: PROTOCOL_VERSION,
          type: "error",
          code: parsed.code,
          message: parsed.message,
          ...(parsed.ref ? { ref: parsed.ref } : {}),
        });
        return;
      }
      const message = parsed.message;
      peer.queue = peer.queue
        .then(() => (peer.closed && message.type !== "end_session" ? undefined : this.service.handle(peer, message)))
        .catch((err: unknown) => {
          this.logger.error({ err, peer: peer.id, type: message.type }, "unhandled error while processing message");
          peer.send({ v: PROTOCOL_VERSION, type: "error", code: "internal_error", message: "Internal server error", ref: message.type });
        });
    });

    ws.on("close", (code: number) => {
      this.peers.delete(peer);
      const remaining = (this.connectionsPerIp.get(ip) ?? 1) - 1;
      if (remaining <= 0) this.connectionsPerIp.delete(ip);
      else this.connectionsPerIp.set(ip, remaining);
      // Let any in-flight handler finish first so its state is visible to the disconnect logic.
      void peer.queue.then(() => this.service.onDisconnect(peer));
      this.logger.debug({ peer: peer.id, code }, "connection closed");
    });

    ws.on("error", (err: Error & { code?: string }) => {
      // ws reports oversize frames as errors and closes with 1009.
      this.logger.warn({ peer: peer.id, code: err.code }, "websocket error");
    });
  }

  private checkHeartbeats(): void {
    for (const peer of this.peers) {
      if (!peer.alive) {
        this.logger.info({ peer: peer.id }, "heartbeat timeout; terminating connection");
        peer.terminate();
        continue;
      }
      peer.alive = false;
      peer.ping();
    }
  }

  async close(): Promise<void> {
    if (this.heartbeat) clearInterval(this.heartbeat);
    for (const peer of this.peers) peer.close(CloseCodes.shutdown, "Server shutting down");
    await new Promise<void>((resolve) => this.wss.close(() => resolve()));
    // Give clients a moment to receive the close frame, then drop anything left.
    await new Promise((r) => setTimeout(r, 200));
    for (const peer of this.peers) peer.terminate();
  }
}

function rawToString(data: RawData): string {
  if (Buffer.isBuffer(data)) return data.toString("utf8");
  if (Array.isArray(data)) return Buffer.concat(data).toString("utf8");
  return Buffer.from(data).toString("utf8");
}

function rejectUpgrade(socket: Duplex, status: number, text: string): void {
  socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  socket.destroy();
}

/**
 * Client IP for rate limiting. Proxy headers are honoured only when TRUST_PROXY=true
 * (e.g. behind Cloudflare Tunnel or a hosting provider's load balancer); otherwise they are spoofable.
 */
export function clientIp(req: IncomingMessage, trustProxy: boolean): string {
  if (trustProxy) {
    const cf = req.headers["cf-connecting-ip"];
    if (typeof cf === "string" && cf.length > 0) return cf.trim();
    const xff = req.headers["x-forwarded-for"];
    const first = (Array.isArray(xff) ? xff[0] : xff)?.split(",")[0]?.trim();
    if (first) return first;
  }
  return req.socket.remoteAddress ?? "unknown";
}
