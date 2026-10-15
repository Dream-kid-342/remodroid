# Architecture

```
                    ┌──────────────────────────────────────────┐
                    │  server/  (Node.js 24 + TypeScript)      │
                    │  HTTP /health   WebSocket /ws            │
                    │  in-memory registry, auth, relay only    │
                    └───────▲──────────────────────▲───────────┘
            signaling (WSS) │                      │ signaling (WSS)
                            │                      │
┌───────────────────────────┴───┐          ┌───────┴──────────────────────────┐
│ Host (Android or Windows)     │  WebRTC  │ Controller (Android or Windows)  │
│ screen capture → video track ═╪══════════╪═► video renderer                 │
│ input injector ◄─ data channel╪══════════╪══ gestures / mouse / keys        │
└───────────────────────────────┘ direct   └──────────────────────────────────┘
                                  or TURN
```

## Repository layout

| Path | What |
|---|---|
| `server/` | Standalone signaling server: `config/` (env parsing), `http/` (health), `signaling/` (protocol schema, WebSocket gateway), `services/` (signaling logic, ICE/TURN credentials), `sessions/` (in-memory registry), `security/` (credentials, rate limiting), `validation/` (message parsing). Its own `package.json`; independent of Gradle. |
| `shared/` | Kotlin/JVM library used by both clients: `protocol/` (message models), `models/` (permissions), `validation/`, `signaling/` (OkHttp WebSocket client with reconnect), `session/` (state machine, `HostAgent`, `ControllerAgent`), `control/` (data-channel protocol, gestures, coordinate mapping), `rtc/` (platform-neutral WebRTC interfaces, stats parsing). |
| `android/app/` | Android app: Compose UI, `rtc/` (Stream WebRTC: MediaProjection capture, renderer), `input/` (AccessibilityService injector), `service/` (mediaProjection foreground service), `host/` (approval sequencing), `data/` (settings). |
| `windows/` | Windows app (Compose Desktop): `rtc/` (webrtc-java: desktop capture, frame conversion), `input/` (Win32 SendInput via JNA), `ui/`, `data/`. |
| `docs/` | This documentation. |
| `scripts/` | Icon generation from the logo. |

There are no separate `core`/`data`/`domain` Gradle modules for Android: the platform-independent core
lives in `:shared`, which both clients depend on, and the Android app keeps its platform code in packages.

## Responsibilities

* **Server**: who may talk to whom. Registers hosts, checks credentials, forwards requests, relays the
  host's decision and WebRTC setup messages, expires and cleans up. It cannot see media and cannot grant
  control.
* **HostAgent** (shared): registration and resume, incoming requests, approval, creating the host peer,
  offer/ICE, recovery (ICE restarts), and **enforcing** the granted permission on every input command.
* **ControllerAgent** (shared): request, cancel, answer, renders state honestly (Connected only after the
  media path is up and the first frame rendered), sends input.
* **Platform adapters**: `RtcFactory`/`RtcPeer`/`HostRtcPeer` (WebRTC per platform) and `InputInjector`
  (AccessibilityService / SendInput).

Agents run on a single-threaded dispatcher (Android main thread, Swing EDT), so their state needs no locks.

## Session state machine

Defined in `shared/.../session/SessionStateMachine.kt`; invalid transitions are refused.

Host:
```
OFFLINE → AVAILABLE ⇄ INCOMING_REQUEST → NEGOTIATING → CONNECTING_MEDIA → CONNECTED_VIEW_ONLY ⇄ CONNECTED_CONTROL_ENABLED
             ▲  ▲                                            │                    │
             │  └──────────── DISCONNECTING ◄────────────────┴──── RECONNECTING ◄─┘
             └── FAILED
```
Controller:
```
OFFLINE/DISCONNECTED/FAILED → REQUESTING_CONNECTION → WAITING_FOR_APPROVAL → NEGOTIATING → CONNECTING_MEDIA
   → CONNECTED_VIEW_ONLY ⇄ CONNECTED_CONTROL_ENABLED ⇄ RECONNECTING → DISCONNECTING → DISCONNECTED
   (any active state → FAILED with a reason)
```

`CONNECTED_CONTROL_ENABLED` means the host's *effective* capabilities include more than viewing.

## Resource lifecycle

Teardown (`HostAgent.teardownSession`, `ControllerAgent.teardown`) cancels timers and collectors and calls
`RtcPeer.close()`, which releases:
* Android host: display listener, `ScreenCapturerAndroid` (virtual display + MediaProjection), video track
  and source, `SurfaceTextureHelper`, data channel, peer connection; the foreground service stops itself.
* Android viewer: renderer sink, peer connection.
* Windows: desktop source, track, data channel, peer connection, Skia bitmaps.

This runs on: user end, partner end, server `session_ended`, permission/capture loss, media failure,
and app shutdown (Windows window close sends `end_session`/`unregister_host` first).

## Scaling beyond one server

The registry is in memory, so one process serves all sessions. Running several instances requires a shared
session store and message routing (e.g. Redis pub/sub) or sticky routing by device ID. Not implemented,
because one Node process handles thousands of idle WebSockets and the signaling traffic per session is tiny.
