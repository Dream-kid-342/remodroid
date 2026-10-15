# Android capabilities and limitations

RemoDroid uses only public, documented Android APIs. This page lists what that allows, what it does
not, and what depends on the Android version or the manufacturer.

## Screen sharing (MediaProjection)

* Every session requires **Android's own screen-capture consent dialog**, after you approve the request
  in RemoDroid. On Android 14 and later a consent can be used for one session only, so the dialog appears
  each time. This is an Android rule and cannot be skipped.
* On Android 14+ RemoDroid asks to capture the **entire screen** (not a single app), because remote input
  coordinates must match the whole display.
* While sharing, a **foreground-service notification** ("Your screen is being shared") is always visible,
  with a *Stop sharing* action. The system status-bar cast indicator is also shown on recent versions.
* **Not capturable:** windows that set `FLAG_SECURE` (banking apps, password managers, some video apps,
  incognito tabs) appear black to the partner. The lock screen, some system dialogs and DRM-protected
  video are likewise hidden or black. RemoDroid does not and cannot bypass this.
* The capture stops if you tap "Stop" in the system UI or another app starts screen capture; RemoDroid
  then ends the session.
* Rotation is followed automatically (the virtual display is recreated with the new orientation).
* Battery: capture and encoding run only during an active session.

## Remote control (AccessibilityService)

Remote input requires you to turn on **"RemoDroid remote control"** in *Settings → Accessibility*. Until
you do, every session is **view only**, whatever access level was requested, and both sides are told why.

With the service enabled and the matching permission level granted, a partner can:

| Action | API used | Notes |
|---|---|---|
| Tap, long press, swipe | `dispatchGesture` (API 24+) | Single-finger gestures. Multi-touch (pinch) is not implemented. |
| Back, Home, Recents | `performGlobalAction` | *Extended support* or higher. |
| Notification shade, Quick settings | `performGlobalAction` | *Maximum supported access* only. |
| Type text | `ACTION_SET_TEXT` on the focused field | Appends to the focused text field. **Password fields are refused.** Some apps with custom text widgets ignore it. |
| Backspace | `ACTION_SET_TEXT` | Removes the last character of the focused field. |
| Enter | `ACTION_IME_ENTER` | Android 11+ only. |
| Other keys, mouse events | – | Not available on Android; refused with an explanation. |

Things no normal Android app can do, and RemoDroid therefore does not offer:

* Unlock or operate the **lock screen** or enter the device PIN/password.
* Interact with windows of the system "secure" layer (some permission dialogs, the screen-capture
  consent dialog itself) on many Android versions.
* Grant itself permissions, install apps silently, or act without the user enabling the service.
* Operate apps that block accessibility input.

## Manufacturer and version differences

* **Background restrictions.** Some manufacturers (Xiaomi/MIUI, Huawei/EMUI, Oppo/ColorOS, Samsung
  "sleeping apps") aggressively stop background apps. Keep RemoDroid open, or exempt it from battery
  optimisation, while you want to receive requests. During an active session the foreground service keeps
  it running.
* **Receiving requests in the background.** RemoDroid does not run a permanent background service while
  idle (to save battery). Leave the app open, or in recent apps, while "Allow Remote Access" is on.
  If Android freezes the app, the server keeps the ID for 30 s; after that a new ID is issued.
* **Accessibility service disabled by the system.** Some devices turn accessibility services off after an
  app update or a crash. RemoDroid detects this and falls back to view only.
* **Gesture timing.** Very long or fast swipes can be shortened by the system (`getMaxGestureDuration`).
* **Hardware video encoders** differ between devices; WebRTC falls back to software VP8 when needed.
  Older or low-end devices may not reach 720p/30 fps — choose *Balanced* or *Data saver* in Settings.
* **Android 13+**: the notification permission is requested when you turn on sharing; without it the
  sharing notification may be hidden from the shade (Android still shows it in the task manager).
* **Android 9 and older** (e.g. many emulators): no foreground-service type checks; Enter key is not
  available (needs Android 11).

## Privacy controls on the host

* **Approve/decline** every request; choose a lower access level than requested.
* **Privacy pause**: stops sending real video (the partner sees a pause notice) and refuses all input.
* **Revoke control**: drops to view-only immediately; enforced on the device itself.
* **End session**, or *Stop sharing* from the notification, at any time.
* **Stop sharing** (the main switch) also invalidates the ID and password on the server.

RemoDroid cannot detect "sensitive" screens automatically; use privacy pause before opening anything
private.
