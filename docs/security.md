# Security and consent model

RemoDroid gives another person a view of, and optionally control over, your device. The design goal is
that this only ever happens visibly, intentionally and revocably.

## Principles

1. **Consent on the device itself.** Every session needs an explicit approval on the host, plus Android's
   own screen-capture consent on Android hosts. A correct password is a prerequisite for *asking*, never a
   substitute for approval.
2. **The host enforces permissions.** The host checks every input command against its *current* granted
   level, pause state and platform support. The controller UI hides unavailable actions for convenience
   only. The signaling server never grants anything on its own; it relays the host's decision and rejects
   permission claims from controllers (`permission_update` is host-only, and approval cannot exceed what
   was requested).
3. **Always visible.** The host shows session status, the partner's name and access level; Android shows a
   persistent foreground notification while capturing. There is no hidden, background or unattended mode.
4. **Immediate revocation.** Revoke control, privacy pause, end session and stop sharing take effect on the
   host first, before any network round trip.

## Credentials

| Item | Generation | Storage | Lifetime |
|---|---|---|---|
| Device ID | 9 random digits (`crypto.randomInt`), unique among active hosts, no device information | server memory | until stop sharing, registration expiry (12 h) or 30 s after the host disappears |
| Temporary password | 8 chars from a 31-symbol unambiguous alphabet (~39.6 bits), CSPRNG | **scrypt** hash (N=16384, r=8, p=1, 16-byte salt) in server memory; plaintext only on the host's screen | `PASSWORD_TTL_SECONDS` (1 h); refresh invalidates immediately |
| Resume token | 256-bit random | SHA-256 hash in server memory; plaintext only in client memory | single use, rotated on every resume, revoked when the session/registration ends |
| Session ID | 192-bit random | server memory | the session |
| TURN credential | HMAC-SHA1 (coturn REST scheme) | never stored | `TURN_CREDENTIAL_TTL_SECONDS` |

* Clients persist **no credentials**. IDs, passwords and resume tokens exist only in process memory, so the
  Android Keystore is not needed for them. Only non-secret preferences (server URL, display name, quality)
  are stored.
* The password is sent once per connection attempt, over TLS (WSS) in production, and compared in
  constant time (`timingSafeEqual`). Unknown IDs run a dummy scrypt verification so response time does
  not reveal whether an ID exists. Unknown ID, wrong password and expired password produce the same error.
* After authentication, the session is bound to the two WebSocket connections; reconnections must present
  the single-use resume token.

## Brute-force protection

* Per IP and per target ID: after `AUTH_MAX_FAILURES` (5) failures within `AUTH_FAILURE_WINDOW_SECONDS`
  (300 s), further attempts are refused for `AUTH_LOCKOUT_BASE_SECONDS` (30 s), doubling on each further
  lockout up to `AUTH_LOCKOUT_MAX_SECONDS` (15 min). The lockout applies even if the next guess is right.
* Per IP token buckets for `register_host` (10/min) and `request_connection` (20/min).
* Per connection message rate limit; per IP connection limit; 64 KiB message cap.
* Even a correct guess only produces an approval prompt on the host.

## Transport

* Production must use **HTTPS/WSS**: terminate TLS in Node (`TLS_CERT_FILE`/`TLS_KEY_FILE`) or at a proxy
  or Cloudflare Tunnel. Android release builds refuse cleartext (`network_security_config`); debug builds
  allow `ws://` for local testing.
* Media and control data are end-to-end encrypted by WebRTC (DTLS-SRTP, DTLS/SCTP). TURN relays see only
  encrypted packets. The signaling server sees SDP and ICE candidates (which include IP addresses) but no
  media.
* Browser `Origin` headers are rejected unless allow-listed (`ALLOWED_ORIGINS`); native clients send none.
* `TRUST_PROXY` must only be enabled behind a proxy that sets `CF-Connecting-IP`/`X-Forwarded-For`,
  otherwise clients could spoof their IP for rate limiting.

## What the controller can and cannot do

* It receives the host's screen as video. Content protected by the OS (Android `FLAG_SECURE` windows, the
  Windows secure desktop) is not captured.
* RemoDroid never transmits keystrokes typed **on the host**, the clipboard, files, notifications as data,
  or accessibility tree contents. The Android accessibility service ignores accessibility events and only
  looks up the focused text field when the controller types.
* Remote typing into **password fields** on Android is refused.
* On Windows, input cannot reach elevated (administrator) windows unless RemoDroid runs elevated, nor the
  secure desktop (UAC, lock screen, Ctrl+Alt+Del). RemoDroid does not request elevation.
* A privacy pause sends black frames and refuses input; there is no automatic detection of sensitive
  screens.

## Logging

* Structured JSON logs (pino). IDs, session IDs and IPs appear only as short one-way hashes.
* Passwords, tokens, SDP and ICE candidates are never passed to the logger; redaction paths exist as a
  second line of defence.
* Validation errors list field names only, never values.

## Server data

In memory only: active host registrations, sessions, resume-token hashes, rate-limit counters. No screen
frames, input, keyboard contents or personal data. A restart clears everything (clients re-register).

## Reporting issues

Treat the signaling server and TURN secrets as production credentials: keep `.env` out of version control
(it is git-ignored), rotate `TURN_SHARED_SECRET` if leaked, and keep Node.js and dependencies patched
(`npm audit`).
