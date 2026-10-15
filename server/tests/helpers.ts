import { pino } from "pino";
import WebSocket from "ws";
import { createApp, type App } from "../src/app.js";
import { loadConfig, type Config } from "../src/config/index.js";
import type { Peer } from "../src/sessions/registry.js";
import type { ServerMessage } from "../src/signaling/protocol.js";

export const silentLogger = pino({ level: "silent" });

export function testConfig(overrides: Record<string, string> = {}): Config {
  return loadConfig({
    NODE_ENV: "test",
    HOST: "127.0.0.1",
    PORT: "0",
    LOG_LEVEL: "silent",
    STUN_URLS: "stun:stun.example.test:3478",
    SWEEP_INTERVAL_MS: "50",
    ...overrides,
  });
}

export async function startApp(overrides: Record<string, string> = {}): Promise<{ app: App; url: string; httpUrl: string }> {
  const app = createApp(testConfig(overrides), silentLogger);
  const addr = await app.listen();
  return { app, url: `ws://127.0.0.1:${addr.port}/ws`, httpUrl: `http://127.0.0.1:${addr.port}` };
}

type Msg = ServerMessage & Record<string, unknown>;

/** WebSocket test client that buffers incoming messages and lets tests await specific types. */
export class TestClient {
  readonly messages: Msg[] = [];
  private waiters: { type: string; resolve: (m: Msg) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }[] = [];
  closeInfo: { code: number; reason: string } | undefined;
  private closeWaiters: ((info: { code: number; reason: string }) => void)[] = [];

  private constructor(readonly ws: WebSocket) {
    ws.on("message", (data) => {
      const msg = JSON.parse(data.toString()) as Msg;
      const idx = this.waiters.findIndex((w) => w.type === msg.type);
      if (idx >= 0) {
        const [w] = this.waiters.splice(idx, 1);
        clearTimeout(w!.timer);
        w!.resolve(msg);
      } else {
        this.messages.push(msg);
      }
    });
    ws.on("close", (code, reason) => {
      this.closeInfo = { code, reason: reason.toString() };
      for (const w of this.closeWaiters) w(this.closeInfo);
    });
  }

  static connect(url: string, options: WebSocket.ClientOptions = {}): Promise<TestClient> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(url, options);
      const client = new TestClient(ws);
      ws.once("open", () => resolve(client));
      ws.once("error", reject);
    });
  }

  send(message: Record<string, unknown>): void {
    this.ws.send(JSON.stringify({ v: 1, ...message }));
  }

  sendRaw(data: string | Buffer): void {
    this.ws.send(data);
  }

  next<T extends ServerMessage["type"]>(type: T, timeoutMs = 3000): Promise<Extract<ServerMessage, { type: T }>> {
    const idx = this.messages.findIndex((m) => m.type === type);
    if (idx >= 0) return Promise.resolve(this.messages.splice(idx, 1)[0] as never);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((w) => w.timer !== timer);
        reject(new Error(`Timed out waiting for ${type}; buffered: ${this.messages.map((m) => m.type).join(", ")}`));
      }, timeoutMs);
      this.waiters.push({ type, resolve: resolve as never, reject, timer });
    });
  }

  /** Asserts that no message of the given type arrives within the window. */
  async expectNone(type: ServerMessage["type"], windowMs = 200): Promise<void> {
    await new Promise((r) => setTimeout(r, windowMs));
    if (this.messages.some((m) => m.type === type)) throw new Error(`Unexpected ${type}`);
  }

  closed(): Promise<{ code: number; reason: string }> {
    if (this.closeInfo) return Promise.resolve(this.closeInfo);
    return new Promise((resolve) => this.closeWaiters.push(resolve));
  }

  close(): void {
    this.ws.close();
  }
}

/** Registers a host and returns its credentials. */
export async function registerHost(client: TestClient, displayName = "Phone A") {
  client.send({ type: "register_host", displayName });
  return client.next("host_registered");
}

/** Drives a session to the approved state and returns both approval messages. */
export async function establishSession(
  host: TestClient,
  controller: TestClient,
  permission = "guided_support",
  grant = permission,
) {
  const reg = await registerHost(host);
  controller.send({
    type: "request_connection",
    requestId: "req1",
    targetId: reg.deviceId,
    password: reg.password,
    permission,
    controllerName: "Phone B",
  });
  const pending = await controller.next("connection_pending");
  const incoming = await host.next("connection_request_received");
  host.send({ type: "approve_connection", sessionId: incoming.sessionId, permission: grant });
  const hostApproved = await host.next("session_approved");
  const controllerApproved = await controller.next("session_approved");
  return { reg, pending, incoming, hostApproved, controllerApproved, sessionId: incoming.sessionId };
}

/** In-process fake peer for service-level tests. */
export class FakePeer implements Peer {
  static seq = 0;
  readonly id = `fake${FakePeer.seq++}`;
  readonly sent: Msg[] = [];
  hostId: string | undefined;
  controllerSessionId: string | undefined;
  closed = false;
  closedWith: { code: number; reason: string } | undefined;

  constructor(readonly ip = "10.0.0.1") {}

  send(message: ServerMessage): void {
    if (!this.closed) this.sent.push(message as Msg);
  }

  close(code: number, reason: string): void {
    this.closed = true;
    this.closedWith = { code, reason };
  }

  last<T extends ServerMessage["type"]>(type: T): Extract<ServerMessage, { type: T }> | undefined {
    return [...this.sent].reverse().find((m) => m.type === type) as never;
  }

  types(): string[] {
    return this.sent.map((m) => m.type);
  }
}

export class FakeClock {
  t = 1_700_000_000_000;
  now = (): number => this.t;
  advance(ms: number): void {
    this.t += ms;
  }
}
