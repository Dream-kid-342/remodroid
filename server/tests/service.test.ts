import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { IceServerService } from "../src/services/iceServers.js";
import { SignalingService } from "../src/services/signalingService.js";
import type { ClientMessage } from "../src/signaling/protocol.js";
import { FakeClock, FakePeer, silentLogger, testConfig } from "./helpers.js";

const cfg = testConfig({
  PASSWORD_TTL_SECONDS: "60",
  APPROVAL_TIMEOUT_SECONDS: "10",
  RESUME_GRACE_SECONDS: "5",
  SESSION_MAX_DURATION_SECONDS: "120",
  HOST_REGISTRATION_TTL_SECONDS: "600",
  AUTH_MAX_FAILURES: "3",
  AUTH_LOCKOUT_BASE_SECONDS: "30",
});

let clock: FakeClock;
let svc: SignalingService;

const send = (peer: FakePeer, m: Record<string, unknown>) => svc.handle(peer, { v: 1, ...m } as ClientMessage);
const errorCode = (peer: FakePeer) => peer.last("error")?.code;

async function register(peer: FakePeer) {
  await send(peer, { type: "register_host" });
  return peer.last("host_registered")!;
}

async function request(controller: FakePeer, targetId: string, password: string, permission = "guided_support") {
  await send(controller, { type: "request_connection", requestId: "r1", targetId, password, permission });
}

async function approved(host: FakePeer, controller: FakePeer, permission = "guided_support") {
  const reg = await register(host);
  await request(controller, reg.deviceId, reg.password, permission);
  const sessionId = host.last("connection_request_received")!.sessionId;
  await send(host, { type: "approve_connection", sessionId, permission });
  return { reg, sessionId };
}

beforeEach(() => {
  clock = new FakeClock();
  svc = new SignalingService(cfg, silentLogger, new IceServerService(cfg.ice, clock.now), clock.now);
});

describe("registration", () => {
  it("assigns unique IDs to concurrent hosts", async () => {
    const ids = new Set<string>();
    for (let i = 0; i < 50; i++) ids.add((await register(new FakePeer(`10.0.${i}.1`))).deviceId);
    assert.equal(ids.size, 50);
  });

  it("does not store the plaintext password", async () => {
    const reg = await register(new FakePeer());
    // Exclude the peer: it is the transport, whose outbox legitimately carries host_registered.
    const { peer: _peer, ...record } = svc.registry.hosts.get(reg.deviceId)!;
    const dump = JSON.stringify(record, (_k, v) =>
      v && typeof v === "object" && v.type === "Buffer" ? Buffer.from(v.data).toString("latin1") : v,
    );
    assert.ok(!dump.includes(reg.password));
    assert.ok(!dump.includes(reg.resumeToken));
  });

  it("refuses a second registration on the same connection", async () => {
    const host = new FakePeer();
    await register(host);
    await send(host, { type: "register_host" });
    assert.equal(errorCode(host), "already_registered");
  });
});

describe("credentials", () => {
  it("rejects wrong passwords and unknown IDs identically", async () => {
    const host = new FakePeer();
    const reg = await register(host);
    const c1 = new FakePeer("10.1.0.1");
    await request(c1, reg.deviceId, "wrongpass");
    const c2 = new FakePeer("10.1.0.2");
    await request(c2, reg.deviceId === "999999999" ? "888888888" : "999999999", reg.password);
    assert.equal(errorCode(c1), "invalid_credentials");
    assert.equal(errorCode(c2), "invalid_credentials");
    assert.equal(c1.last("error")!.message, c2.last("error")!.message);
    assert.ok(!host.types().includes("connection_request_received"));
  });

  it("rejects an expired password", async () => {
    const host = new FakePeer();
    const reg = await register(host);
    clock.advance(61_000);
    svc.sweep();
    assert.ok(host.last("password_expired"));
    const c = new FakePeer("10.1.0.3");
    await request(c, reg.deviceId, reg.password);
    assert.equal(errorCode(c), "invalid_credentials");
  });

  it("refreshing the password invalidates the old one", async () => {
    const host = new FakePeer();
    const reg = await register(host);
    await send(host, { type: "refresh_password" });
    const fresh = host.last("password_refreshed")!;
    assert.notEqual(fresh.password, reg.password);

    const c1 = new FakePeer("10.1.0.4");
    await request(c1, reg.deviceId, reg.password);
    assert.equal(errorCode(c1), "invalid_credentials");

    const c2 = new FakePeer("10.1.0.5");
    await request(c2, reg.deviceId, fresh.password);
    assert.ok(c2.last("connection_pending"));
  });

  it("locks out an IP after repeated failures, even with the right password", async () => {
    const reg = await register(new FakePeer());
    const attacker = new FakePeer("10.9.9.9");
    for (let i = 0; i < 3; i++) await request(attacker, reg.deviceId, `wrong${i}xx`);
    await request(attacker, reg.deviceId, reg.password);
    const err = attacker.last("error")!;
    assert.equal(err.code, "rate_limited");
    assert.ok((err.retryAfterMs ?? 0) > 0);

    clock.advance(31_000);
    await request(attacker, reg.deviceId, reg.password);
    assert.ok(attacker.last("connection_pending"));
  });

  it("locks the target ID when attacked from many IPs", async () => {
    const reg = await register(new FakePeer());
    for (let i = 0; i < 3; i++) await request(new FakePeer(`10.8.0.${i}`), reg.deviceId, "badguess");
    const legit = new FakePeer("10.7.0.1");
    await request(legit, reg.deviceId, reg.password);
    assert.equal(errorCode(legit), "rate_limited");
  });
});

describe("approval flow", () => {
  it("forwards the request and only the host can approve", async () => {
    const host = new FakePeer();
    const reg = await register(host);
    const controller = new FakePeer("10.2.0.1");
    await request(controller, reg.deviceId, reg.password, "extended_support");
    const incoming = host.last("connection_request_received")!;
    assert.equal(incoming.requestedPermission, "extended_support");

    await send(controller, { type: "approve_connection", sessionId: incoming.sessionId, permission: "extended_support" });
    assert.equal(errorCode(controller), "not_authorized");

    await send(host, { type: "approve_connection", sessionId: incoming.sessionId, permission: "view_only" });
    assert.partialDeepStrictEqual(host.last("session_approved"), { role: "host", permission: "view_only" });
    const cApproved = controller.last("session_approved")!;
    assert.partialDeepStrictEqual(cApproved, { role: "controller", permission: "view_only" });
    assert.equal(typeof cApproved.resumeToken, "string");
    assert.ok(cApproved.iceServers.length > 0);
  });

  it("does not let the host grant more than requested", async () => {
    const host = new FakePeer();
    const reg = await register(host);
    const controller = new FakePeer("10.2.0.2");
    await request(controller, reg.deviceId, reg.password, "view_only");
    const sessionId = host.last("connection_request_received")!.sessionId;
    await send(host, { type: "approve_connection", sessionId, permission: "maximum_supported" });
    assert.equal(errorCode(host), "permission_exceeds_request");
    assert.equal(controller.last("session_approved"), undefined);
  });

  it("rejection notifies the controller and frees the host", async () => {
    const host = new FakePeer();
    const reg = await register(host);
    const controller = new FakePeer("10.2.0.3");
    await request(controller, reg.deviceId, reg.password);
    const sessionId = host.last("connection_request_received")!.sessionId;
    await send(host, { type: "reject_connection", sessionId });
    assert.partialDeepStrictEqual(controller.last("session_rejected"), { reason: "rejected_by_host", requestId: "r1" });
    assert.equal(svc.registry.sessions.size, 0);
    assert.equal(svc.registry.hosts.get(reg.deviceId)!.activeSessionId, null);
  });

  it("times out unanswered requests", async () => {
    const host = new FakePeer();
    const reg = await register(host);
    const controller = new FakePeer("10.2.0.4");
    await request(controller, reg.deviceId, reg.password);
    clock.advance(10_001);
    svc.sweep();
    assert.equal(controller.last("session_rejected")?.reason, "approval_timeout");
    assert.equal(host.last("request_cancelled")?.reason, "approval_timeout");
  });

  it("reports busy hosts and unavailable hosts", async () => {
    const reg = await register(new FakePeer());
    await request(new FakePeer("10.3.0.1"), reg.deviceId, reg.password);
    const second = new FakePeer("10.3.0.2");
    await request(second, reg.deviceId, reg.password);
    assert.equal(errorCode(second), "host_busy");

    const host2 = new FakePeer();
    const reg2 = await register(host2);
    await send(host2, { type: "set_availability", available: false });
    const third = new FakePeer("10.3.0.3");
    await request(third, reg2.deviceId, reg2.password);
    assert.equal(errorCode(third), "host_unavailable");
  });

  it("controller may cancel a pending request", async () => {
    const host = new FakePeer();
    const reg = await register(host);
    const controller = new FakePeer("10.2.0.5");
    await request(controller, reg.deviceId, reg.password);
    const sessionId = controller.last("connection_pending")!.sessionId;
    await send(controller, { type: "cancel_request", sessionId });
    assert.equal(host.last("request_cancelled")?.reason, "cancelled_by_controller");
    assert.equal(svc.registry.sessions.size, 0);
  });

  it("refuses connecting to yourself", async () => {
    const host = new FakePeer();
    const reg = await register(host);
    await request(host, reg.deviceId, reg.password);
    assert.equal(errorCode(host), "cannot_connect_to_self");
  });
});

describe("relay authorization", () => {
  it("only relays offer from host and answer from controller within an approved session", async () => {
    const host = new FakePeer();
    const controller = new FakePeer("10.4.0.1");
    const { sessionId } = await approved(host, controller);

    await send(controller, { type: "webrtc_offer", sessionId, sdp: "v=0" });
    assert.equal(errorCode(controller), "not_authorized");

    await send(host, { type: "webrtc_offer", sessionId, sdp: "v=0 offer" });
    assert.partialDeepStrictEqual(controller.last("webrtc_offer"), { sessionId, sdp: "v=0 offer" });

    await send(controller, { type: "webrtc_answer", sessionId, sdp: "v=0 answer" });
    assert.equal(host.last("webrtc_answer")?.sdp, "v=0 answer");

    const candidate = { candidate: "candidate:1 1 udp 1 1.2.3.4 5 typ host", sdpMid: "0", sdpMLineIndex: 0 };
    await send(controller, { type: "ice_candidate", sessionId, candidate });
    assert.deepEqual(host.last("ice_candidate")?.candidate, candidate);
  });

  it("rejects messages referencing another user's session", async () => {
    const host = new FakePeer();
    const { sessionId } = await approved(host, new FakePeer("10.4.0.2"));
    const intruder = new FakePeer("10.4.0.3");
    await send(intruder, { type: "webrtc_answer", sessionId, sdp: "v=0" });
    await send(intruder, { type: "end_session", sessionId });
    await send(intruder, { type: "permission_update", sessionId, permission: "view_only" });
    assert.equal(intruder.sent.length, 3);
    assert.ok(intruder.sent.every((m) => m.type === "error" && m.code === "unknown_session"));
    assert.ok(svc.registry.sessions.has(sessionId));
  });

  it("refuses relay before approval", async () => {
    const host = new FakePeer();
    const reg = await register(host);
    await request(new FakePeer("10.4.0.4"), reg.deviceId, reg.password);
    const sessionId = host.last("connection_request_received")!.sessionId;
    await send(host, { type: "webrtc_offer", sessionId, sdp: "v=0" });
    assert.equal(errorCode(host), "invalid_state");
  });

  it("permission updates come only from the host and are relayed", async () => {
    const host = new FakePeer();
    const controller = new FakePeer("10.4.0.5");
    const { sessionId } = await approved(host, controller, "extended_support");
    await send(controller, { type: "permission_update", sessionId, permission: "extended_support" });
    assert.equal(errorCode(controller), "not_authorized");
    await send(host, { type: "permission_update", sessionId, permission: "view_only" });
    assert.equal(controller.last("permission_updated")?.permission, "view_only");
    assert.equal(svc.registry.sessions.get(sessionId)!.grantedPermission, "view_only");
  });
});

describe("disconnect, resume and cleanup", () => {
  it("either side can end the session", async () => {
    const host = new FakePeer();
    const controller = new FakePeer("10.5.0.1");
    const { sessionId } = await approved(host, controller);
    await send(controller, { type: "end_session", sessionId });
    assert.partialDeepStrictEqual(host.last("session_ended"), { reason: "user_ended", endedBy: "controller" });
    assert.ok(controller.last("session_ended"));
    assert.equal(svc.registry.sessions.size, 0);
    assert.equal(svc.registry.resumeTokenCount, 1); // only the host registration token remains
  });

  it("controller can resume within the grace period", async () => {
    const host = new FakePeer();
    const controller = new FakePeer("10.5.0.2");
    const { sessionId } = await approved(host, controller);
    const token = controller.last("session_approved")!.resumeToken!;
    controller.closed = true;
    svc.onDisconnect(controller);
    assert.partialDeepStrictEqual(host.last("peer_connection_lost"), { sessionId, graceMs: 5000 });

    clock.advance(3000);
    svc.sweep();
    const again = new FakePeer("10.5.0.3");
    await send(again, { type: "resume", token });
    const resumed = again.last("resumed")!;
    assert.partialDeepStrictEqual(resumed, { kind: "controller", session: { sessionId, state: "approved" } });
    assert.notEqual(resumed.resumeToken, token);
    assert.ok(host.last("peer_reconnected"));

    // The old token is single-use.
    const third = new FakePeer("10.5.0.4");
    await send(third, { type: "resume", token });
    assert.equal(errorCode(third), "resume_failed");
  });

  it("ends the session when the controller does not come back", async () => {
    const host = new FakePeer();
    const controller = new FakePeer("10.5.0.5");
    await approved(host, controller);
    controller.closed = true;
    svc.onDisconnect(controller);
    clock.advance(5001);
    svc.sweep();
    assert.partialDeepStrictEqual(host.last("session_ended"), { reason: "peer_lost", endedBy: "server" });
    assert.equal(svc.registry.sessions.size, 0);
  });

  it("host can resume and keeps its ID; old connection is replaced", async () => {
    const host = new FakePeer();
    const { reg, sessionId } = await approved(host, new FakePeer("10.5.0.6"));
    const second = new FakePeer();
    await send(second, { type: "resume", token: reg.resumeToken });
    assert.equal(host.closedWith?.code, 4001);
    assert.partialDeepStrictEqual(second.last("resumed"), { kind: "host", deviceId: reg.deviceId, session: { sessionId } });
    // The replaced connection's later disconnect must not tear anything down.
    svc.onDisconnect(host);
    clock.advance(10_000);
    svc.sweep();
    assert.ok(svc.registry.sessions.has(sessionId));
  });

  it("removes the host registration after the grace period and ends its session", async () => {
    const host = new FakePeer();
    const controller = new FakePeer("10.5.0.7");
    const { reg } = await approved(host, controller);
    host.closed = true;
    svc.onDisconnect(host);
    assert.ok(controller.last("peer_connection_lost"));
    clock.advance(5001);
    svc.sweep();
    assert.equal(controller.last("session_ended")?.reason, "peer_lost");
    assert.equal(svc.registry.hosts.has(reg.deviceId), false);
  });

  it("stop sharing invalidates credentials and ends the session", async () => {
    const host = new FakePeer();
    const controller = new FakePeer("10.5.0.8");
    const { reg } = await approved(host, controller);
    await send(host, { type: "unregister_host" });
    assert.equal(controller.last("session_ended")?.reason, "host_stopped_sharing");
    assert.ok(host.last("host_unregistered"));
    const c2 = new FakePeer("10.5.0.9");
    await request(c2, reg.deviceId, reg.password);
    assert.equal(errorCode(c2), "invalid_credentials");
    const c3 = new FakePeer("10.5.0.10");
    await send(c3, { type: "resume", token: reg.resumeToken });
    assert.equal(errorCode(c3), "resume_failed");
  });

  it("pending request is cancelled if the controller disconnects", async () => {
    const host = new FakePeer();
    const reg = await register(host);
    const controller = new FakePeer("10.5.0.11");
    await request(controller, reg.deviceId, reg.password);
    controller.closed = true;
    svc.onDisconnect(controller);
    assert.equal(host.last("request_cancelled")?.reason, "peer_lost");
    assert.equal(svc.registry.sessions.size, 0);
  });

  it("expires sessions after the maximum duration and registrations after their TTL", async () => {
    const host = new FakePeer();
    const controller = new FakePeer("10.5.0.12");
    const { reg } = await approved(host, controller);
    clock.advance(120_001);
    svc.sweep();
    assert.equal(controller.last("session_ended")?.reason, "session_expired");
    clock.advance(600_000);
    svc.sweep();
    assert.ok(host.last("registration_expired"));
    assert.equal(svc.registry.hosts.has(reg.deviceId), false);
  });

  it("shutdown notifies both participants", async () => {
    const host = new FakePeer();
    const controller = new FakePeer("10.5.0.13");
    await approved(host, controller);
    svc.shutdown();
    assert.equal(host.last("session_ended")?.reason, "server_shutdown");
    assert.equal(controller.last("session_ended")?.reason, "server_shutdown");
  });
});

describe("resume request correlation", () => {
  it("echoes requestId on resume success and failure", async () => {
    const host = new FakePeer();
    const reg = await register(host);
    const again = new FakePeer();
    await send(again, { type: "resume", token: reg.resumeToken, requestId: "host" });
    assert.equal(again.last("resumed")?.requestId, "host");
    await send(again, { type: "resume", token: "x".repeat(43), requestId: "controller" });
    assert.partialDeepStrictEqual(again.last("error"), { code: "resume_failed", requestId: "controller" });
  });
});
