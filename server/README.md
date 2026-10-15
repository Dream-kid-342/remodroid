# RemoDroid signaling server

WebSocket signaling server for RemoDroid: host registration with temporary ID/password, connection
requests and approvals, WebRTC offer/answer/ICE relay, resume after reconnects, expiry and cleanup.
It never sees screen video and cannot grant remote control by itself.

Independent of the Android and Windows builds: everything here works with only Node.js and npm.

## Requirements

* **Node.js 24 LTS** (`engines: >=24 <25`). Uses the built-in `--env-file-if-exists` flag.
* npm 10+.

Runtime dependencies: `ws` (WebSocket), `zod` (message/config validation), `pino` (JSON logging).
Dev: `typescript`, `tsx` (dev runner and test loader), `@types/*`. Tests use Node's built-in test runner.

## Commands

```bash
npm install          # or `npm ci` in CI/production (uses package-lock.json)
npm run dev          # watch mode with tsx, reads .env if present
npm run typecheck    # tsc --noEmit over src and tests
npm test             # unit, service and WebSocket integration tests (node:test)
npm run build        # compile to dist/
npm start            # run dist/index.js (production), reads .env if present
```

## Configuration

Copy `.env.example` to `.env` and adjust; every variable is documented there. Invalid values stop the
server at startup with a message naming the variable.

Most important:

| Variable | Default | Purpose |
|---|---|---|
| `PORT`, `HOST` | `8080`, `0.0.0.0` | Listen address |
| `NODE_ENV` | `development` | `production` enables startup warnings for missing TLS |
| `TLS_CERT_FILE`, `TLS_KEY_FILE` | – | Serve HTTPS/WSS directly |
| `TRUST_PROXY` | `false` | Use `CF-Connecting-IP`/`X-Forwarded-For` for client IPs (only behind a proxy/tunnel) |
| `ALLOWED_ORIGINS` | empty | Browser origins allowed to connect (native apps send no Origin) |
| `STUN_URLS` | Google STUN | STUN servers handed to clients |
| `TURN_URLS` + `TURN_SHARED_SECRET` | – | TURN relay with time-limited credentials (recommended) |
| `PASSWORD_TTL_SECONDS` | `3600` | Temporary password lifetime |
| `APPROVAL_TIMEOUT_SECONDS` | `60` | Time the host has to approve |
| `RESUME_GRACE_SECONDS` | `30` | How long a dropped socket may take to resume |
| `AUTH_MAX_FAILURES` / `AUTH_LOCKOUT_*` | 5 / 30 s doubling to 15 min | Brute-force lockout |
| `LOG_LEVEL` | `info` | pino level |

## Endpoints

* `GET /health` → `200 {"status":"ok","protocolVersion":1,"uptimeSeconds":…,"turnConfigured":…,"hosts":…,"sessions":…,"connections":…}`; `503` while shutting down.
* `GET /` → service description.
* `WS /ws` → signaling. Protocol: [`docs/protocol.md`](../docs/protocol.md).

## Production startup

```bash
npm ci
npm run build
NODE_ENV=production npm start
```

`SIGINT`/`SIGTERM` trigger a graceful shutdown: every session gets `session_ended` (`server_shutdown`),
sockets close with 1001, and the process exits (forced after 10 s).

State is **in memory**. A restart ends all sessions and clears registrations; clients detect this through a
failed `resume` and register again with a new ID. Run a single instance (see `docs/architecture.md` for
scaling notes).

## Deployment

See [`docs/deployment.md`](../docs/deployment.md) for:
* direct Node.js hosting (build/start commands, env, TLS, health checks, logs, restarts),
* self-hosting behind **Cloudflare Tunnel** (`cloudflared` config routing a hostname to `http://127.0.0.1:8080`),
* **TURN** setup with coturn (`use-auth-secret`) and how to verify relay-only connectivity.

## Troubleshooting

| Symptom | Check |
|---|---|
| Server exits with `Invalid configuration: …` | The named variable in `.env`. TURN URLs need a secret or username+credential. |
| Clients show "Cannot reach the signaling server" | URL scheme (`wss://` in production, path `/ws`), firewall, tunnel status, `GET /health`. |
| Upgrade rejected with 403 | A browser `Origin` not in `ALLOWED_ORIGINS`. |
| 429 on connect | `MAX_CONNECTIONS_PER_IP` reached (many devices behind one NAT? raise it). |
| "Too many attempts" | Lockout after failed passwords; wait `retryAfterMs` or restart for testing. |
| Request accepted but "Could not establish the video connection" | No direct path and no TURN: configure `TURN_URLS`/`TURN_SHARED_SECRET`; verify TURN ports. |
| Rate limits apply to everyone at once behind Cloudflare | Set `TRUST_PROXY=true`. |
| `npm test` fails to load TypeScript | Run `npm install` (tsx is a dev dependency). |
