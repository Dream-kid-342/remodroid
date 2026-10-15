import { ClientMessageSchema, PROTOCOL_VERSION, type ClientMessage } from "../signaling/protocol.js";

export type ParseResult =
  | { ok: true; message: ClientMessage }
  | { ok: false; code: "malformed_message" | "unsupported_version"; message: string; ref?: string };

export function parseClientMessage(raw: string): ParseResult {
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return { ok: false, code: "malformed_message", message: "Message is not valid JSON" };
  }
  if (typeof data !== "object" || data === null || Array.isArray(data)) {
    return { ok: false, code: "malformed_message", message: "Message must be a JSON object" };
  }
  const record = data as Record<string, unknown>;
  const ref = typeof record.type === "string" ? record.type.slice(0, 64) : undefined;
  if (record.v !== PROTOCOL_VERSION) {
    return {
      ok: false,
      code: "unsupported_version",
      message: `Unsupported protocol version; this server speaks v${PROTOCOL_VERSION}`,
      ...(ref ? { ref } : {}),
    };
  }
  const parsed = ClientMessageSchema.safeParse(data);
  if (!parsed.success) {
    // Report field paths only, never values (values may contain credentials).
    const fields = [...new Set(parsed.error.issues.map((i) => i.path.join(".") || "(root)"))].join(", ");
    return { ok: false, code: "malformed_message", message: `Invalid message fields: ${fields}`, ...(ref ? { ref } : {}) };
  }
  return { ok: true, message: parsed.data };
}
