import { createHmac } from "node:crypto";
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ConfigError, loadConfig } from "../src/config/index.js";
import {
  PASSWORD_ALPHABET,
  generateDeviceId,
  generatePassword,
  generateToken,
  hashSecret,
  isValidDeviceId,
  normalizeDeviceId,
  verifySecret,
} from "../src/security/credentials.js";
import { FailureTracker, TokenBucket } from "../src/security/rateLimiter.js";
import { IceServerService } from "../src/services/iceServers.js";
import { parseClientMessage } from "../src/validation/messages.js";
import { FakeClock, testConfig } from "./helpers.js";

describe("credentials", () => {
  it("generates 9-digit IDs with a non-zero first digit", () => {
    for (let i = 0; i < 500; i++) assert.ok(isValidDeviceId(generateDeviceId()));
  });

  it("normalizes IDs typed with spaces or dashes", () => {
    assert.equal(normalizeDeviceId("123 456-789"), "123456789");
  });

  it("generates passwords from the unambiguous alphabet", () => {
    const pw = generatePassword();
    assert.equal(pw.length, 8);
    for (const c of pw) assert.ok(PASSWORD_ALPHABET.includes(c));
    const many = new Set(Array.from({ length: 200 }, () => generatePassword()));
    assert.equal(many.size, 200);
  });

  it("hashes with a random salt and verifies case/space-insensitively", async () => {
    const a = await hashSecret("abcd2345");
    const b = await hashSecret("abcd2345");
    assert.equal(a.salt.equals(b.salt), false);
    assert.equal(a.hash.equals(b.hash), false);
    assert.equal(await verifySecret("abcd2345", a), true);
    assert.equal(await verifySecret("ABCD 2345", a), true);
    assert.equal(await verifySecret("abcd2346", a), false);
  });

  it("generates URL-safe high-entropy tokens", () => {
    assert.match(generateToken(), /^[A-Za-z0-9_-]{43}$/);
  });
});

describe("rate limiting", () => {
  it("token bucket refills over time", () => {
    const clock = new FakeClock();
    const bucket = new TokenBucket(2, 1, clock.now);
    assert.equal(bucket.take(), true);
    assert.equal(bucket.take(), true);
    assert.equal(bucket.take(), false);
    clock.advance(1000);
    assert.equal(bucket.take(), true);
    assert.equal(bucket.take(), false);
  });

  it("locks a key after repeated failures and escalates the lockout", () => {
    const clock = new FakeClock();
    const tracker = new FailureTracker(
      { maxFailures: 3, windowMs: 60_000, lockoutBaseMs: 10_000, lockoutMaxMs: 25_000 },
      clock.now,
    );
    tracker.recordFailure("k");
    tracker.recordFailure("k");
    assert.equal(tracker.lockedFor("k"), 0);
    tracker.recordFailure("k");
    assert.equal(tracker.lockedFor("k"), 10_000);

    clock.advance(10_001);
    assert.equal(tracker.lockedFor("k"), 0);
    for (let i = 0; i < 3; i++) tracker.recordFailure("k");
    assert.equal(tracker.lockedFor("k"), 20_000);

    clock.advance(20_001);
    for (let i = 0; i < 3; i++) tracker.recordFailure("k");
    assert.equal(tracker.lockedFor("k"), 25_000); // capped
  });

  it("forgets failures outside the window", () => {
    const clock = new FakeClock();
    const tracker = new FailureTracker({ maxFailures: 2, windowMs: 1000, lockoutBaseMs: 5000, lockoutMaxMs: 5000 }, clock.now);
    tracker.recordFailure("k");
    clock.advance(1500);
    tracker.recordFailure("k");
    assert.equal(tracker.lockedFor("k"), 0);
  });
});

describe("protocol validation", () => {
  const parse = (m: unknown) => parseClientMessage(typeof m === "string" ? m : JSON.stringify(m));

  it("accepts a well-formed message", () => {
    assert.equal(parse({ v: 1, type: "register_host", displayName: "Pixel" }).ok, true);
  });

  it("rejects invalid JSON, non-objects and unknown types", () => {
    assert.partialDeepStrictEqual(parse("{not json"), { ok: false, code: "malformed_message" });
    assert.partialDeepStrictEqual(parse("[]"), { ok: false, code: "malformed_message" });
    assert.partialDeepStrictEqual(parse({ v: 1, type: "grant_everything" }), { ok: false, code: "malformed_message" });
  });

  it("rejects other protocol versions", () => {
    assert.partialDeepStrictEqual(parse({ v: 2, type: "register_host" }), { ok: false, code: "unsupported_version" });
  });

  it("rejects unknown fields, so clients cannot smuggle permission claims", () => {
    const r = parse({ v: 1, type: "webrtc_answer", sessionId: "a".repeat(32), sdp: "x", permission: "maximum_supported" });
    assert.partialDeepStrictEqual(r, { ok: false, code: "malformed_message" });
  });

  it("rejects invalid permission levels", () => {
    const r = parse({ v: 1, type: "request_connection", requestId: "r", targetId: "123456789", password: "x", permission: "root" });
    assert.partialDeepStrictEqual(r, { ok: false, code: "malformed_message" });
  });

  it("never echoes field values in validation errors", () => {
    const r = parse({ v: 1, type: "request_connection", requestId: "r", targetId: "1", password: "S3CRET-VALUE", permission: "x" });
    assert.equal(r.ok, false);
    if (!r.ok) assert.ok(!r.message.includes("S3CRET"));
  });

  it("rejects control characters in display names", () => {
    assert.equal(parse({ v: 1, type: "register_host", displayName: "evil‮name" }).ok, false);
  });
});

describe("ICE servers", () => {
  it("returns STUN only when TURN is not configured", () => {
    const svc = new IceServerService(testConfig().ice);
    assert.deepEqual(svc.forSession(), [{ urls: ["stun:stun.example.test:3478"] }]);
    assert.equal(svc.turnConfigured, false);
  });

  it("issues time-limited TURN REST credentials", () => {
    const clock = new FakeClock();
    const cfg = testConfig({
      TURN_URLS: "turn:turn.example.test:3478?transport=udp,turns:turn.example.test:5349?transport=tcp",
      TURN_SHARED_SECRET: "test-secret",
      TURN_CREDENTIAL_TTL_SECONDS: "600",
    });
    const turn = new IceServerService(cfg.ice, clock.now).forSession()[1]!;
    const [expiry] = turn.username!.split(":");
    assert.equal(Number(expiry), Math.floor((clock.t + 600_000) / 1000));
    assert.equal(turn.credential, createHmac("sha1", "test-secret").update(turn.username!).digest("base64"));
    assert.equal(turn.urls.length, 2);
  });
});

describe("configuration", () => {
  it("applies defaults", () => {
    const c = loadConfig({});
    assert.equal(c.port, 8080);
    assert.deepEqual(c.ice.stunUrls, ["stun:stun.l.google.com:19302"]);
    assert.equal(c.passwordTtlMs, 3_600_000);
  });

  it("requires TURN credentials when TURN URLs are set", () => {
    assert.throws(() => loadConfig({ TURN_URLS: "turn:t.example:3478" }), ConfigError);
  });

  it("rejects malformed values", () => {
    assert.throws(() => loadConfig({ PORT: "abc" }), ConfigError);
    assert.throws(() => loadConfig({ STUN_URLS: "http://nope" }), ConfigError);
  });
});
