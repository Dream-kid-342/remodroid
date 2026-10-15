import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import type { App } from "../src/app.js";
import { TestClient, establishSession, registerHost, startApp } from "./helpers.js";

let running: App[] = [];
const clients: TestClient[] = [];

async function start(overrides: Record<string, string> = {}) {
  const s = await startApp(overrides);
  running.push(s.app);
  return s;
}

async function client(url: string, options = {}) {
  const c = await TestClient.connect(url, options);
  clients.push(c);
  return c;
}

afterEach(async () => {
  for (const c of clients.splice(0)) c.close();
  await Promise.all(running.map((a) => a.close()));
  running = [];
});

describe("HTTP", () => {
  it("serves a health endpoint", async () => {
    const { httpUrl } = await start();
    const res = await fetch(`${httpUrl}/health`);
    assert.equal(res.status, 200);
    assert.partialDeepStrictEqual(await res.json(), { status: "ok", protocolVersion: 1, hosts: 0, sessions: 0 });
    assert.equal((await fetch(`${httpUrl}/nope`)).status, 404);
  });
});

describe("WebSocket signaling end to end", () => {
  it("completes registration, approval, SDP/ICE exchange and disconnect", async () => {
    const { url, httpUrl } = await start();
    const host = await client(url);
    const controller = await client(url);
    const { sessionId, controllerApproved } = await establishSession(host, controller, "extended_support");
    assert.equal(controllerApproved.permission, "extended_support");

    host.send({ type: "webrtc_offer", sessionId, sdp: "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n" });
    assert.match((await controller.next("webrtc_offer")).sdp, /^v=0/);
    controller.send({ type: "webrtc_answer", sessionId, sdp: "v=0 answer" });
    assert.equal((await host.next("webrtc_answer")).sdp, "v=0 answer");

    const candidate = {
      candidate: "candidate:842163049 1 udp 1677729535 203.0.113.5 3478 typ srflx",
      sdpMid: "0",
      sdpMLineIndex: 0,
    };
    host.send({ type: "ice_candidate", sessionId, candidate });
    assert.deepEqual((await controller.next("ice_candidate")).candidate, candidate);
    controller.send({ type: "ice_candidate", sessionId, candidate });
    await host.next("ice_candidate");

    const health = await (await fetch(`${httpUrl}/health`)).json();
    assert.partialDeepStrictEqual(health, { hosts: 1, sessions: 1, connections: 2 });

    host.send({ type: "end_session", sessionId });
    assert.equal((await controller.next("session_ended")).endedBy, "host");
    await host.next("session_ended");
  });

  it("rejection reaches the controller", async () => {
    const { url } = await start();
    const host = await client(url);
    const controller = await client(url);
    const reg = await registerHost(host);
    controller.send({ type: "request_connection", requestId: "abc", targetId: reg.deviceId, password: reg.password, permission: "view_only" });
    const incoming = await host.next("connection_request_received");
    host.send({ type: "reject_connection", sessionId: incoming.sessionId });
    assert.partialDeepStrictEqual(await controller.next("session_rejected"), { requestId: "abc", reason: "rejected_by_host" });
  });

  it("wrong password returns a generic error and does not notify the host", async () => {
    const { url } = await start();
    const host = await client(url);
    const controller = await client(url);
    const reg = await registerHost(host);
    controller.send({ type: "request_connection", requestId: "x", targetId: reg.deviceId, password: "nottheone", permission: "view_only" });
    assert.partialDeepStrictEqual(await controller.next("error"), { code: "invalid_credentials", requestId: "x" });
    await host.expectNone("connection_request_received");
  });

  it("rejects malformed messages, wrong versions and binary frames without closing", async () => {
    const { url } = await start();
    const c = await client(url);
    c.sendRaw("{nope");
    assert.equal((await c.next("error")).code, "malformed_message");
    c.sendRaw(JSON.stringify({ v: 99, type: "register_host" }));
    assert.equal((await c.next("error")).code, "unsupported_version");
    c.sendRaw(Buffer.from([1, 2, 3]));
    assert.equal((await c.next("error")).code, "malformed_message");
    c.send({ type: "session_heartbeat" });
    assert.equal(typeof (await c.next("heartbeat_ack")).serverTime, "number");
  });

  it("closes connections that send oversized messages", async () => {
    const { url } = await start({ MAX_MESSAGE_BYTES: "2048" });
    const c = await client(url);
    c.sendRaw("x".repeat(4096));
    assert.equal((await c.closed()).code, 1009);
  });

  it("closes connections that exceed the message rate", async () => {
    const { url } = await start({ MESSAGES_PER_SECOND: "1", MESSAGE_BURST: "5" });
    const c = await client(url);
    for (let i = 0; i < 20; i++) c.send({ type: "session_heartbeat" });
    assert.equal((await c.closed()).code, 4008);
  });

  it("rejects disallowed browser origins and unknown paths", async () => {
    const { url } = await start({ ALLOWED_ORIGINS: "https://allowed.example" });
    await assert.rejects(TestClient.connect(url, { origin: "https://evil.example" }), /403/);
    await assert.rejects(TestClient.connect(url.replace("/ws", "/other")), /404/);
    const ok = await client(url, { origin: "https://allowed.example" });
    assert.equal(ok.ws.readyState, 1);
  });

  it("limits concurrent connections per IP", async () => {
    const { url } = await start({ MAX_CONNECTIONS_PER_IP: "2" });
    await client(url);
    await client(url);
    await assert.rejects(TestClient.connect(url), /429/);
  });

  it("socket drop: controller resumes on a new socket within the grace period", async () => {
    const { url } = await start({ RESUME_GRACE_SECONDS: "5" });
    const host = await client(url);
    const controller = await client(url);
    const { sessionId, controllerApproved } = await establishSession(host, controller);
    controller.ws.terminate();
    assert.equal((await host.next("peer_connection_lost")).sessionId, sessionId);

    const back = await client(url);
    back.send({ type: "resume", token: controllerApproved.resumeToken });
    assert.equal((await back.next("resumed")).session?.sessionId, sessionId);
    await host.next("peer_reconnected");

    host.send({ type: "webrtc_offer", sessionId, sdp: "v=0 restart" });
    assert.equal((await back.next("webrtc_offer")).sdp, "v=0 restart");
  });

  it("socket drop: session ends when the host does not return", async () => {
    const { url } = await start({ RESUME_GRACE_SECONDS: "1" });
    const host = await client(url);
    const controller = await client(url);
    await establishSession(host, controller);
    host.ws.terminate();
    await controller.next("peer_connection_lost");
    assert.equal((await controller.next("session_ended", 4000)).reason, "peer_lost");
  });

  it("duplicate host login: the resumed connection replaces the old one", async () => {
    const { url } = await start();
    const first = await client(url);
    const reg = await registerHost(first);
    const second = await client(url);
    second.send({ type: "resume", token: reg.resumeToken });
    assert.equal((await second.next("resumed")).deviceId, reg.deviceId);
    assert.equal((await first.closed()).code, 4001);
  });

  it("server restart clears registrations; clients must re-register", async () => {
    const s1 = await start();
    const host = await client(s1.url);
    const reg = await registerHost(host);
    const ended = host.closed();
    await s1.app.close();
    assert.equal((await ended).code, 1001);

    const s2 = await start();
    const again = await client(s2.url);
    again.send({ type: "resume", token: reg.resumeToken });
    assert.equal((await again.next("error")).code, "resume_failed");
    const controller = await client(s2.url);
    controller.send({ type: "request_connection", requestId: "r", targetId: reg.deviceId, password: reg.password, permission: "view_only" });
    assert.equal((await controller.next("error")).code, "invalid_credentials");
  });

  it("server shutdown sends session_ended before closing", async () => {
    const { app, url } = await start();
    const host = await client(url);
    const controller = await client(url);
    await establishSession(host, controller);
    const ended = controller.next("session_ended");
    await app.close();
    assert.equal((await ended).reason, "server_shutdown");
  });

  it("issues TURN credentials in session approval when configured", async () => {
    const { url } = await start({ TURN_URLS: "turn:turn.example.test:3478", TURN_SHARED_SECRET: "s3cret" });
    const host = await client(url);
    const controller = await client(url);
    const { hostApproved, controllerApproved } = await establishSession(host, controller);
    for (const approved of [hostApproved, controllerApproved]) {
      const turn = approved.iceServers.find((s) => s.urls.some((u) => u.startsWith("turn:")));
      assert.match(turn?.username ?? "", /^\d+:[0-9a-f]+$/);
      assert.equal(typeof turn?.credential, "string");
    }
  });
});
