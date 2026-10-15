# Deployment

RemoDroid needs two internet-facing services:

1. The **signaling server** (`server/`, Node.js) at a stable `wss://` URL.
2. A **TURN server** (strongly recommended; required for many mobile-data and corporate networks).

No Docker or containers are used. The server runs directly on Node.js.

## Option A: direct Node.js hosting

Any provider that runs a long-lived Node.js 24 process and supports WebSockets (a VPS, a PaaS "web
service", etc.). Serverless/function platforms that cut connections after a few seconds are **not**
suitable.

| Setting | Value |
|---|---|
| Root directory | `server` |
| Node version | 24.x (see `engines` in `server/package.json`) |
| Build command | `npm ci && npm run build` |
| Start command | `npm start` (runs `node --env-file-if-exists=.env dist/index.js`) |
| Health check | `GET /health` → 200 `{"status":"ok",…}` (503 while shutting down) |
| Port | `PORT` env var (most platforms inject it) |
| Environment | see `server/.env.example`; set `NODE_ENV=production` |
| TLS | provided by the platform → set `TRUST_PROXY=true`; or terminate in Node with `TLS_CERT_FILE`/`TLS_KEY_FILE` |
| Logs | JSON on stdout; collect with the platform's log viewer |
| Restarts | let the platform restart on exit; the process handles `SIGTERM` gracefully (notifies sessions, closes sockets, exits within 10 s) |
| Instances | **exactly one** (the session registry is in memory) |

Clients then use `wss://<your-host>/ws`.

### On your own Linux VPS (systemd)

```ini
# /etc/systemd/system/remodroid-signaling.service
[Unit]
Description=RemoDroid signaling server
After=network-online.target

[Service]
WorkingDirectory=/opt/remodroid/server
ExecStart=/usr/bin/node --env-file-if-exists=.env dist/index.js
Restart=always
RestartSec=2
User=remodroid
Environment=NODE_ENV=production
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now remodroid-signaling
journalctl -u remodroid-signaling -f
```

Put a TLS reverse proxy (e.g. Caddy or nginx with WebSocket upgrade enabled) in front and set
`HOST=127.0.0.1`, `TRUST_PROXY=true`.

## Option B: self-hosted Node.js + Cloudflare Tunnel

Use this when the machine has no public inbound port (home server, office PC). `cloudflared` makes an
outbound connection to Cloudflare, which serves `https://signal.example.com` and forwards to your local
server. Cloudflare Tunnel supports WebSockets.

**The machine must stay on.** If it sleeps or shuts down, nobody can connect.

1. Install Node.js 24 and build the server:
   ```bash
   cd server
   npm ci
   npm run build
   cp .env.example .env   # edit: NODE_ENV=production, HOST=127.0.0.1, PORT=8080, TRUST_PROXY=true
   ```
2. Install `cloudflared` from Cloudflare's documentation, then:
   ```bash
   cloudflared tunnel login
   cloudflared tunnel create remodroid
   cloudflared tunnel route dns remodroid signal.example.com
   ```
3. Tunnel config (`~/.cloudflared/config.yml` on Linux/macOS, `%USERPROFILE%\.cloudflared\config.yml` on Windows):
   ```yaml
   tunnel: remodroid
   credentials-file: /path/to/<tunnel-id>.json
   ingress:
     - hostname: signal.example.com
       service: http://127.0.0.1:8080
     - service: http_status:404
   ```
4. Run both as services:
   * Linux: the systemd unit above for Node, and `sudo cloudflared service install` for the tunnel.
   * Windows: run `cloudflared service install` (as administrator). For Node, use a service wrapper such as
     NSSM or the Windows Task Scheduler ("At startup", "Restart on failure") running
     `node --env-file-if-exists=.env dist\index.js` in the `server` folder.
5. Clients use `wss://signal.example.com/ws`. `TRUST_PROXY=true` makes rate limiting use Cloudflare's
   `CF-Connecting-IP` header.

**Cloudflare Tunnel does not carry WebRTC media and is not a TURN server.** Media goes peer-to-peer or
through your TURN server.

## TURN server

### coturn on a VPS with a public IP

```ini
# /etc/turnserver.conf
listening-port=3478
tls-listening-port=5349
fingerprint
use-auth-secret
static-auth-secret=<long random secret, same as TURN_SHARED_SECRET>
realm=turn.example.com
total-quota=200
no-multicast-peers
no-cli
# TLS (needed for turns: and for networks that only allow 443/TLS)
cert=/etc/letsencrypt/live/turn.example.com/fullchain.pem
pkey=/etc/letsencrypt/live/turn.example.com/privkey.pem
# Relay port range; open it in the firewall (UDP)
min-port=49160
max-port=49200
# If behind 1:1 NAT (cloud VMs): external-ip=<public-ip>/<private-ip>
```

Firewall: UDP+TCP 3478, TCP 5349 (optionally 443 for TURN over TLS), UDP 49160–49200.

Server `.env`:
```
TURN_URLS=turn:turn.example.com:3478?transport=udp,turn:turn.example.com:3478?transport=tcp,turns:turn.example.com:5349?transport=tcp
TURN_SHARED_SECRET=<same secret>
TURN_CREDENTIAL_TTL_SECONDS=3600
```

The signaling server derives a short-lived username/credential per session (`<expiry>:<random>` /
base64 HMAC-SHA1), which coturn verifies with the shared secret. Nothing long-lived is sent to clients.

### Managed TURN

Providers that issue static credentials can be used with `TURN_USERNAME`/`TURN_CREDENTIAL` (these do
not expire, so prefer providers that support the shared-secret REST scheme or rotate them regularly).
Check that the provider offers UDP, TCP and TLS transports.

### Verify TURN

1. `curl https://signal.example.com/health` → `"turnConfigured": true`.
2. Test the relay with a WebRTC ICE test tool using credentials generated the same way, or directly in the
   app: enable **Settings → Relay only (TURN)** on one device and start a session. Success, with "relay"
   shown in the session toolbar, proves the relay path.
3. Repeat with both devices on mobile data.

## Pointing clients at the server

* **Android**: Settings → *Signaling server*. The default baked into a build comes from the Gradle
  property `remodroid.signalingUrl`, e.g.
  `./gradlew :android:app:assembleRelease -Premodroid.signalingUrl=wss://signal.example.com/ws`.
  Release builds accept only `wss://`.
* **Windows**: Settings dialog, or `REMODROID_SIGNALING_URL` env var / `-Dremodroid.signalingUrl` for the
  default.

## Operational notes

* Restarting the server ends all sessions (clients show "The signaling server restarted") and issues new
  IDs on re-registration.
* Monitor `/health` (`hosts`, `sessions`, `connections`) and log lines at `warn` (auth failures, rate limits).
* Keep secrets in `.env` or the platform's secret store; never commit them.
