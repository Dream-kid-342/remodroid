import { z } from "zod";

/** Treat empty strings from .env files as "not set". */
const optionalString = z.preprocess(
  (v) => (typeof v === "string" && v.trim() === "" ? undefined : v),
  z.string().trim().optional(),
);

const csv = z.preprocess(
  (v) =>
    typeof v === "string"
      ? v
          .split(",")
          .map((s) => s.trim())
          .filter((s) => s.length > 0)
      : [],
  z.array(z.string()),
);

const bool = (def: boolean) =>
  z.preprocess((v) => {
    if (v === undefined || v === "") return def;
    if (typeof v === "string") return ["1", "true", "yes", "on"].includes(v.toLowerCase());
    return v;
  }, z.boolean());

const int = (def: number, min: number, max: number) =>
  z.preprocess((v) => (v === undefined || v === "" ? def : Number(v)), z.number().int().min(min).max(max));

const iceUrl = z.string().regex(/^(stun|stuns|turn|turns):[^\s]+$/, "ICE URLs must start with stun:, stuns:, turn: or turns:");

const EnvSchema = z.object({
  NODE_ENV: z.preprocess(
    (v) => (v === undefined || v === "" ? "development" : v),
    z.enum(["development", "production", "test"]),
  ),
  HOST: z.preprocess((v) => (v === undefined || v === "" ? "0.0.0.0" : v), z.string()),
  PORT: int(8080, 0, 65535),
  PUBLIC_SIGNALING_URL: optionalString.pipe(
    z.string().regex(/^wss?:\/\/[^\s]+$/, "PUBLIC_SIGNALING_URL must start with ws:// or wss://").optional(),
  ),
  ALLOWED_ORIGINS: csv,
  TRUST_PROXY: bool(false),

  TLS_CERT_FILE: optionalString,
  TLS_KEY_FILE: optionalString,

  STUN_URLS: z.preprocess(
    (v) => (v === undefined ? "stun:stun.l.google.com:19302" : v),
    csv.pipe(z.array(iceUrl)),
  ),
  TURN_URLS: csv.pipe(z.array(iceUrl)),
  TURN_SHARED_SECRET: optionalString,
  TURN_USERNAME: optionalString,
  TURN_CREDENTIAL: optionalString,
  TURN_CREDENTIAL_TTL_SECONDS: int(3600, 60, 86400),

  PASSWORD_TTL_SECONDS: int(3600, 30, 86400),
  HOST_REGISTRATION_TTL_SECONDS: int(43200, 60, 7 * 86400),
  APPROVAL_TIMEOUT_SECONDS: int(60, 5, 600),
  RESUME_GRACE_SECONDS: int(30, 1, 600),
  SESSION_MAX_DURATION_SECONDS: int(8 * 3600, 60, 7 * 86400),
  HEARTBEAT_INTERVAL_SECONDS: int(20, 1, 300),

  AUTH_MAX_FAILURES: int(5, 1, 100),
  AUTH_FAILURE_WINDOW_SECONDS: int(300, 1, 86400),
  AUTH_LOCKOUT_BASE_SECONDS: int(30, 1, 3600),
  AUTH_LOCKOUT_MAX_SECONDS: int(900, 1, 86400),

  MAX_MESSAGE_BYTES: int(64 * 1024, 1024, 1024 * 1024),
  MAX_CONNECTIONS_PER_IP: int(20, 1, 10000),
  MESSAGES_PER_SECOND: int(50, 1, 10000),
  MESSAGE_BURST: int(200, 1, 100000),

  SWEEP_INTERVAL_MS: int(1000, 10, 60000),
  LOG_LEVEL: z.preprocess(
    (v) => (v === undefined || v === "" ? "info" : v),
    z.enum(["fatal", "error", "warn", "info", "debug", "trace", "silent"]),
  ),
});

export interface Config {
  env: "development" | "production" | "test";
  host: string;
  port: number;
  publicSignalingUrl: string | undefined;
  allowedOrigins: string[];
  trustProxy: boolean;
  tls: { certFile: string; keyFile: string } | undefined;
  ice: {
    stunUrls: string[];
    turnUrls: string[];
    turnSharedSecret: string | undefined;
    turnUsername: string | undefined;
    turnCredential: string | undefined;
    turnCredentialTtlMs: number;
  };
  passwordTtlMs: number;
  hostRegistrationTtlMs: number;
  approvalTimeoutMs: number;
  resumeGraceMs: number;
  sessionMaxDurationMs: number;
  heartbeatIntervalMs: number;
  auth: {
    maxFailures: number;
    failureWindowMs: number;
    lockoutBaseMs: number;
    lockoutMaxMs: number;
  };
  maxMessageBytes: number;
  maxConnectionsPerIp: number;
  messagesPerSecond: number;
  messageBurst: number;
  sweepIntervalMs: number;
  logLevel: "fatal" | "error" | "warn" | "info" | "debug" | "trace" | "silent";
}

export class ConfigError extends Error {}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const parsed = EnvSchema.safeParse(env);
  if (!parsed.success) {
    const details = parsed.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`).join("; ");
    throw new ConfigError(`Invalid configuration: ${details}`);
  }
  const e = parsed.data;

  if ((e.TLS_CERT_FILE && !e.TLS_KEY_FILE) || (!e.TLS_CERT_FILE && e.TLS_KEY_FILE)) {
    throw new ConfigError("Invalid configuration: TLS_CERT_FILE and TLS_KEY_FILE must be set together");
  }
  if (e.TURN_URLS.length > 0 && !e.TURN_SHARED_SECRET && !(e.TURN_USERNAME && e.TURN_CREDENTIAL)) {
    throw new ConfigError(
      "Invalid configuration: TURN_URLS requires TURN_SHARED_SECRET (recommended) or TURN_USERNAME + TURN_CREDENTIAL",
    );
  }

  return {
    env: e.NODE_ENV,
    host: e.HOST,
    port: e.PORT,
    publicSignalingUrl: e.PUBLIC_SIGNALING_URL,
    allowedOrigins: e.ALLOWED_ORIGINS,
    trustProxy: e.TRUST_PROXY,
    tls: e.TLS_CERT_FILE && e.TLS_KEY_FILE ? { certFile: e.TLS_CERT_FILE, keyFile: e.TLS_KEY_FILE } : undefined,
    ice: {
      stunUrls: e.STUN_URLS,
      turnUrls: e.TURN_URLS,
      turnSharedSecret: e.TURN_SHARED_SECRET,
      turnUsername: e.TURN_USERNAME,
      turnCredential: e.TURN_CREDENTIAL,
      turnCredentialTtlMs: e.TURN_CREDENTIAL_TTL_SECONDS * 1000,
    },
    passwordTtlMs: e.PASSWORD_TTL_SECONDS * 1000,
    hostRegistrationTtlMs: e.HOST_REGISTRATION_TTL_SECONDS * 1000,
    approvalTimeoutMs: e.APPROVAL_TIMEOUT_SECONDS * 1000,
    resumeGraceMs: e.RESUME_GRACE_SECONDS * 1000,
    sessionMaxDurationMs: e.SESSION_MAX_DURATION_SECONDS * 1000,
    heartbeatIntervalMs: e.HEARTBEAT_INTERVAL_SECONDS * 1000,
    auth: {
      maxFailures: e.AUTH_MAX_FAILURES,
      failureWindowMs: e.AUTH_FAILURE_WINDOW_SECONDS * 1000,
      lockoutBaseMs: e.AUTH_LOCKOUT_BASE_SECONDS * 1000,
      lockoutMaxMs: e.AUTH_LOCKOUT_MAX_SECONDS * 1000,
    },
    maxMessageBytes: e.MAX_MESSAGE_BYTES,
    maxConnectionsPerIp: e.MAX_CONNECTIONS_PER_IP,
    messagesPerSecond: e.MESSAGES_PER_SECOND,
    messageBurst: e.MESSAGE_BURST,
    sweepIntervalMs: e.SWEEP_INTERVAL_MS,
    logLevel: e.LOG_LEVEL,
  };
}
