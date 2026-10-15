import { createHmac, randomBytes } from "node:crypto";
import type { Config } from "../config/index.js";

export interface IceServer {
  urls: string[];
  username?: string;
  credential?: string;
}

/**
 * Builds the ICE server list handed to clients when a session is approved.
 *
 * With TURN_SHARED_SECRET, credentials follow the "TURN REST API" scheme supported by coturn
 * (`use-auth-secret` / `static-auth-secret`): username = "<unix-expiry>:<random>",
 * credential = base64(HMAC-SHA1(secret, username)). They expire on their own and are never stored.
 */
export class IceServerService {
  constructor(
    private readonly ice: Config["ice"],
    private readonly now: () => number = Date.now,
  ) {}

  get turnConfigured(): boolean {
    return this.ice.turnUrls.length > 0;
  }

  forSession(): IceServer[] {
    const servers: IceServer[] = [];
    if (this.ice.stunUrls.length > 0) servers.push({ urls: [...this.ice.stunUrls] });
    if (this.ice.turnUrls.length > 0) {
      if (this.ice.turnSharedSecret) {
        const expiry = Math.floor((this.now() + this.ice.turnCredentialTtlMs) / 1000);
        const username = `${expiry}:${randomBytes(8).toString("hex")}`;
        const credential = createHmac("sha1", this.ice.turnSharedSecret).update(username).digest("base64");
        servers.push({ urls: [...this.ice.turnUrls], username, credential });
      } else if (this.ice.turnUsername && this.ice.turnCredential) {
        servers.push({
          urls: [...this.ice.turnUrls],
          username: this.ice.turnUsername,
          credential: this.ice.turnCredential,
        });
      }
    }
    return servers;
  }
}
