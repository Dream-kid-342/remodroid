# RemoDroid signaling and control protocol (v1)

Two channels are involved:

| Channel | Transport | Carries | Sees |
|---|---|---|---|
| Signaling | WebSocket (`wss://…/ws`) to the RemoDroid server | registration, credentials check, approval, SDP, ICE, session lifecycle | the server |
| Control | WebRTC data channel `remodroid-control`, peer to peer (DTLS-encrypted) | input commands, host state | only the two devices |

Screen video travels as a WebRTC video track (SRTP), never through the signaling server.

Source of truth: `server/src/signaling/protocol.ts` (runtime validation with zod) and
`shared/src/main/kotlin/com/remodroid/shared/protocol/SignalingMessages.kt` / `control/ControlMessages.kt`.

## Signaling envelope

Every message is a JSON object `{"v": 1, "type": "<name>", ...fields}`.

* Unknown types, unknown fields, wrong field types, oversized strings → `error` with `code: "malformed_message"`. Field **values** are never echoed back.
* `v` other than `1` → `error` with `code: "unsupported_version"`.
* Binary frames → `malformed_message`. Frames larger than `MAX_MESSAGE_BYTES` (64 KiB default) close the socket with code 1009.
* More than `MESSAGE_BURST` messages at once (refilling at `MESSAGES_PER_SECOND`) closes the socket with code 4008.
* Messages from one socket are processed strictly in order.

### Error message

```json
{"v":1,"type":"error","code":"invalid_credentials","message":"…","ref":"request_connection","requestId":"…","retryAfterMs":30000}
```

`ref` is the `type` of the message that caused the error. `requestId`/`sessionId` are included when known.
Codes: `malformed_message`, `unsupported_version`, `message_too_large`, `rate_limited`, `already_registered`,
`not_registered`, `invalid_credentials`, `host_unavailable`, `host_busy`, `cannot_connect_to_self`,
`already_in_session`, `unknown_session`, `not_authorized`, `invalid_state`, `permission_exceeds_request`,
`peer_unavailable`, `resume_failed`, `internal_error`.

## Host messages (client → server)

| type | fields | rules |
|---|---|---|
| `register_host` | `displayName?` (≤64 printable chars) | One registration per socket. Rate-limited per IP. Reply: `host_registered`. |
| `refresh_password` | – | Registered host only. Old password stops working immediately. Reply: `password_refreshed`. |
| `set_availability` | `available: bool` | When false, new requests get `host_unavailable`; a pending request is rejected with `busy`. |
| `unregister_host` | – | "Stop sharing": ends the session, deletes ID, password and resume token. Reply: `host_unregistered`. |
| `approve_connection` | `sessionId`, `permission` | Only the host that owns the pending session. `permission` must not exceed the requested level. |
| `reject_connection` | `sessionId`, `reason?` (`rejected_by_host`/`busy`) | Pending sessions only. |
| `permission_update` | `sessionId`, `permission` | Host only, approved session, ≤ requested level. Relayed to the controller as `permission_updated`. The host applies it locally *before* sending. |
| `webrtc_offer` | `sessionId`, `sdp` (≤32 KiB) | Host only, approved session. Relayed unchanged. Also used for ICE restarts. |

## Controller messages (client → server)

| type | fields | rules |
|---|---|---|
| `request_connection` | `requestId`, `targetId`, `password`, `permission`, `controllerName?` | Credentials checked with scrypt; unknown ID, wrong password and expired password all return the same `invalid_credentials`. Failures count against the IP and the target ID (lockout with exponential back-off). One session per socket. Reply: `connection_pending`. |
| `cancel_request` | `sessionId` | Controller of a pending session only. |
| `webrtc_answer` | `sessionId`, `sdp` | Controller only, approved session. |

## Either role

| type | fields | rules |
|---|---|---|
| `ice_candidate` | `sessionId`, `candidate: {candidate, sdpMid, sdpMLineIndex}` | Participants of an approved session only. |
| `media_state` | `sessionId`, `connected`, `relayed?` | Informational (logging). |
| `end_session` | `sessionId`, `reason?` (`user_ended`/`permission_revoked`/`error`) | Either participant. Both receive `session_ended`. |
| `resume` | `token`, `requestId?` | Re-attaches a host registration or a controller session to a new socket. The token is single-use; the reply carries a new one. `requestId` is echoed so one socket can resume both roles. Failure: `resume_failed` → register again. |
| `session_heartbeat` | – | Reply `heartbeat_ack`. The server also sends WebSocket pings every `HEARTBEAT_INTERVAL_SECONDS` and drops sockets that miss one. |

## Server → client

| type | to | fields |
|---|---|---|
| `host_registered` | host | `deviceId` (9 digits), `password` (8 chars), `idKind: "temporary"`, `passwordExpiresAt`, `registrationExpiresAt`, `resumeToken` |
| `password_refreshed` | host | `password`, `passwordExpiresAt` |
| `password_expired` | host | – (clients refresh automatically) |
| `availability_updated` | host | `available` |
| `registration_expired` | host | – |
| `connection_request_received` | host | `sessionId`, `controllerName`, `requestedPermission`, `expiresAt` |
| `request_cancelled` | host | `sessionId`, `reason` (`cancelled_by_controller`/`approval_timeout`/`peer_lost`) |
| `connection_pending` | controller | `requestId`, `sessionId`, `approvalExpiresAt` |
| `session_approved` | both | `sessionId`, `role`, `permission`, `requestedPermission`, `peerName`, `iceServers`, `resumeToken` (controller only), `expiresAt` |
| `session_rejected` | controller | `sessionId`, `requestId`, `reason` (`rejected_by_host`/`busy`/`approval_timeout`/`peer_lost`) |
| `webrtc_offer` / `webrtc_answer` / `ice_candidate` | the other peer | as sent |
| `permission_updated` | both | `sessionId`, `permission` |
| `peer_connection_lost` | the other peer | `sessionId`, `graceMs` – the other side's signaling socket dropped; media may still flow |
| `peer_reconnected` | the other peer | `sessionId` |
| `session_ended` | both | `sessionId`, `reason`, `endedBy` (`host`/`controller`/`server`) |
| `resumed` | resumer | `kind`, `resumeToken`, `requestId?`, host: `deviceId`, `passwordExpiresAt`, `registrationExpiresAt`; `session` (`sessionId`, `state`, `permission`, fresh `iceServers`) or null |
| `heartbeat_ack` | sender | `serverTime` |

`iceServers` entries: `{urls: string[], username?, credential?}`. With `TURN_SHARED_SECRET`, TURN credentials are
generated per session and expire after `TURN_CREDENTIAL_TTL_SECONDS`.

## Session flow

```
Host                         Server                          Controller
 |-- register_host ----------->|                                  |
 |<- host_registered (ID, pw) -|                                  |
 |                             |<-- request_connection (ID, pw) --|
 |                             |--- connection_pending ---------->|
 |<- connection_request_rcvd --|                                  |
 |  (user approves; Android also asks for screen-capture consent) |
 |-- approve_connection ------>|                                  |
 |<- session_approved ---------|--- session_approved ------------>|
 |-- webrtc_offer ------------>|--- webrtc_offer ---------------->|
 |<- webrtc_answer ------------|<-- webrtc_answer ----------------|
 |<========= ice_candidate (both directions, relayed) ===========>|
 |<=========== WebRTC: video + control data channel ============>|  (direct or via TURN)
 |-- end_session ------------->|--- session_ended -------------->|
```

Timeouts (server): approval `APPROVAL_TIMEOUT_SECONDS` (60), resume grace `RESUME_GRACE_SECONDS` (30),
session maximum `SESSION_MAX_DURATION_SECONDS` (8 h). Timeouts (clients): server response 15 s,
media connection 45 s, media recovery 30 s (ICE restarts every 8 s, driven by the host).

## Control channel (data channel `remodroid-control`)

JSON with discriminator `t`. Coordinates are normalised to the video frame: (0,0) top-left, (1,1) bottom-right.

Controller → host (`InputCommand`, each with `seq`):

| t | fields | capability required |
|---|---|---|
| `tap` | `point` | `pointer` |
| `long_press` | `point`, `durationMs` (≤10 s) | `pointer` |
| `swipe` | `path` (2–64 points), `durationMs` (≤10 s) | `pointer` |
| `mouse_move` / `mouse_button` / `wheel` | `point`, `button`, `down` / `dx`, `dy` | `pointer` (Windows hosts) |
| `global_action` | `action`: `back`/`home`/`recents` | `navigation` |
| `global_action` | `action`: `notifications`/`quick_settings` | `system_panels` |
| `text` | `text` (≤1024 chars) | `text_input` |
| `key` | `key` (enter, backspace, delete, tab, escape, arrows, home, end, page_up/down, shift, control, alt), `down` | `text_input` |

Host → controller:

* `host_state` – `platform`, `permission`, `capabilities` (effective set), `paused`, `screen`, `controlNote`. Sent when the channel opens and on every change.
* `input_rejected` – `seq`, `reason` (`not_permitted`/`unsupported`/`paused`/`invalid`/`failed`), `detail`.

The host validates every command in this order: rate limit (60/s, burst 120) → structure → paused →
capability ∈ effective set → platform support. Nothing the controller sends can widen the effective set.

### Permission presets

| Preset | Capabilities |
|---|---|
| View only | `view_screen` |
| Guided support | + `pointer` |
| Extended support | + `navigation`, `text_input` |
| Maximum supported access | + `system_panels` |

Effective capabilities = preset ∩ what the device supports right now (e.g. Android accessibility service
enabled; Windows supports `pointer` and `text_input`) – and nothing while paused.
