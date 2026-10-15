import type { IncomingMessage, ServerResponse } from "node:http";
import { PROTOCOL_VERSION } from "../signaling/protocol.js";

export interface HealthSource {
  isShuttingDown(): boolean;
  stats(): { hosts: number; sessions: number; connections: number };
  turnConfigured: boolean;
}

const startedAt = Date.now();

function json(res: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(payload),
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  res.end(payload);
}

/**
 * Minimal HTTP surface: health checks only. Node's built-in http module is sufficient,
 * so no HTTP framework is added.
 */
export function createRequestHandler(source: HealthSource) {
  return (req: IncomingMessage, res: ServerResponse): void => {
    const path = new URL(req.url ?? "/", "http://localhost").pathname;
    if (req.method !== "GET" && req.method !== "HEAD") {
      return json(res, 405, { error: "method_not_allowed" });
    }
    if (path === "/health" || path === "/healthz") {
      const shuttingDown = source.isShuttingDown();
      return json(res, shuttingDown ? 503 : 200, {
        status: shuttingDown ? "shutting_down" : "ok",
        protocolVersion: PROTOCOL_VERSION,
        uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
        turnConfigured: source.turnConfigured,
        ...source.stats(),
      });
    }
    if (path === "/") {
      return json(res, 200, { service: "remodroid-signaling", websocket: "/ws", health: "/health" });
    }
    return json(res, 404, { error: "not_found" });
  };
}
