import { pino, type Logger } from "pino";
import type { Config } from "./config/index.js";

/**
 * Structured JSON logger. Credential-bearing fields are redacted as a safety net, but the code
 * never passes passwords, tokens, SDP or ICE candidates to the logger in the first place.
 */
export function createLogger(config: Pick<Config, "logLevel" | "env">): Logger {
  return pino({
    level: config.logLevel,
    base: { service: "remodroid-signaling" },
    redact: {
      paths: [
        "password",
        "*.password",
        "token",
        "*.token",
        "resumeToken",
        "*.resumeToken",
        "credential",
        "*.credential",
        "sdp",
        "*.sdp",
        "candidate",
        "*.candidate",
        "req.headers.authorization",
        "req.headers.cookie",
      ],
      censor: "[redacted]",
    },
    timestamp: pino.stdTimeFunctions.isoTime,
  });
}
