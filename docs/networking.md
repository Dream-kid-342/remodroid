# How RemoDroid connects devices across the internet

RemoDroid has to connect two devices that may be on completely different networks: a phone on mobile
data and a laptop on home Wi-Fi, two phones on different carriers, and so on. This page explains how
it does that and what it needs from you.

## The short version

1. Both devices open an **outbound** connection to the RemoDroid **signaling server**. Nothing has to be
   reachable from the internet on either device: no open ports, no port forwarding, no same-router requirement.
2. The signaling server introduces the devices: it checks the ID and password, asks the host to approve,
   and passes short setup messages between them.
3. The devices then try to build a **direct** encrypted WebRTC connection for the screen video and the
   control channel.
4. If a direct path is impossible (common on mobile networks), they fall back to a **TURN relay** server
   that forwards the encrypted traffic.
5. If neither works, the app shows a failure message. It never pretends to be connected.

## The pieces

### Signaling server (WebSocket)

The Node.js server in `server/`. It is a meeting point, not a video relay: it carries small JSON
messages (registration, approval, connection setup) and never sees the screen. Clients connect to
`wss://your-host/ws` (TLS) in production.

Because the devices dial *out* to it, it works from behind home routers, corporate firewalls and
carrier-grade NAT (CGNAT), which most mobile networks use.

### WebRTC

WebRTC is the standard real-time media stack used by browsers and video-call apps. RemoDroid uses
Google's libwebrtc on both platforms (Stream WebRTC on Android, webrtc-java on Windows), so they speak
the same wire protocol. It provides:

* **SRTP/DTLS encryption** for all media and data between the devices.
* **Congestion control**: bitrate, frame rate and resolution adapt automatically to the available
  bandwidth (important on mobile data).
* **ICE** (Interactive Connectivity Establishment): finds a working network path.

### NAT, and why direct connections sometimes fail

Most devices do not have a public IP address. A router or carrier NAT shares one public address among
many devices and only lets in traffic that answers something the device sent first. Two devices behind
two NATs therefore cannot simply "call" each other.

### STUN

A STUN server tells a device what its public address and port look like from the internet. ICE uses this
to try "hole punching": both devices send packets to each other's public address at the same time so
each NAT sees outgoing traffic and lets the reply in. This works for many home networks.

**STUN alone does not guarantee connectivity.** Symmetric NATs (frequent on mobile carriers and corporate
networks) assign a different public port for every destination, so hole punching fails.

### TURN

A TURN server relays the (still end-to-end encrypted) WebRTC traffic between the devices. It works
whenever both devices can reach the TURN server, including over TCP or TLS on port 443 for very
restrictive networks. It costs server bandwidth, so ICE only uses it when direct paths fail.

**For reliable service across mobile data and arbitrary networks, configure a TURN server.** Without it,
some combinations (for example two phones on different carriers) will fail with
"Could not establish the video connection".

The signaling server hands out TURN credentials per session (see `server/README.md`). They are
time-limited when `TURN_SHARED_SECRET` is used.

## Wi-Fi and mobile data

* Mobile data is a normal internet connection for RemoDroid; Wi-Fi is not required.
* Any mix works (both Wi-Fi, both mobile, one of each, Android ↔ Windows) as long as each device has
  internet access and, where hole punching fails, a TURN server is reachable.
* **Switching networks during a session** (e.g. leaving Wi-Fi): the Android app detects the network change
  and reconnects the signaling socket immediately. WebRTC keeps gathering candidates continuously; if the
  media path breaks, the host performs an **ICE restart** over the reconnected signaling channel. Both
  sides show "Reconnecting…" in the meantime. If nothing recovers within about 30 seconds the session
  ends with a clear message.
* If only the signaling socket drops but the media path survives, the session continues; the server keeps
  the session for `RESUME_GRACE_SECONDS` (30 s default) so the device can resume with its resume token.

## What "Connected" means

The controller shows *Connected* only after **both** the WebRTC peer connection reports `connected`
**and** the first video frame has been decoded and rendered. Before that it shows the actual stage:
contacting partner → waiting for approval → negotiating → connecting video.

The toolbar shows connection quality (from round-trip time and packet loss), and whether the path is
**direct** or via **relay**.

## Data usage

At the default "Auto" quality the encoder is capped at 2.5 Mbit/s (720p, 30 fps); a mostly static screen
uses far less. "Balanced" (1.2 Mbit/s) and "Data saver" (0.6 Mbit/s, 15 fps) lower the caps for
metered connections. WebRTC lowers quality further on its own when bandwidth is short.

## Testing relay connectivity

1. Configure TURN on the server and restart it; `GET /health` reports `"turnConfigured": true`.
2. In the app settings on **one** device, enable **Relay only (TURN)**. This sets
   `iceTransportPolicy = relay`, so no direct path can be used.
3. Start a session. If video appears, TURN works; the session toolbar shows "relay".
4. If it fails only in relay-only mode, check the TURN URLs, ports (UDP/TCP 3478, TLS 5349 or 443),
   firewall rules and the shared secret. See `server/README.md` → *TURN setup and verification*.
